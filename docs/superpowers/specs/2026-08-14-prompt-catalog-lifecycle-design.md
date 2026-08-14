# CC Relay Prompt Catalog 与生命周期设计

## 背景

CC Relay 已支持由 CC Center 管理标准 Skill，并由其他 Relay 随心跳同步。固定 Prompt 与 Skill 的分发需求相似，但其生效位置与会话一致性要求不同：Prompt 会直接改变模型上下文，必须明确注入时机、顺序、版本固定和失败行为，且不能污染 Center 保存的共享会话内容。

## 目标

- CC Center 提供 Prompt 的安装、查询、失效和内容下载能力。
- 非 Center Relay 定期同步 Prompt 元数据与内容，并仅使用校验通过的本地副本。
- 支持 `UNIFIED`、`PRE`、`POST` 三类 Prompt，类型内按 `order ASC, promptId ASC` 稳定排序。
- 同一任务固定使用同一个 Prompt revision，任务执行期间的目录更新只影响后续任务。
- Center 共享上下文继续只保存已接受的最终 Agent 回复和共享事实，不保存 Prompt、候选草稿或私有 ReAct/tool 过程。
- POST 通过同一个模型 Session 的二次调用完成最终定稿，候选回复在成功前不对外暴露。

## 非目标

- 不把 Prompt 内容写入 Center 的共享会话事件或消息表。
- 不在任务执行中的每次模型子调用前重新查询 Center。
- 不修改已完成任务或旧私有模型 Session 的历史内容。
- 不承诺跨机器、跨模型 Session 的 KV cache 命中，只保持可复用前缀尽量稳定。
- 不为 Prompt 引入草稿、审核、灰度或多租户版本链。

## Prompt 元数据

CC Center 持久化以下字段：

```text
promptId
type: UNIFIED | PRE | POST
order
sha256
status: ACTIVE | INVALID
contentPath
contentSize
createdAt
updatedAt
```

约束：

- `promptId` 全局唯一，使用与 Skill ID 相同的安全命名规则：`[a-z0-9][a-z0-9-]{0,63}`。
- `order` 为有符号整数，排序时先比较 `order`，再比较 `promptId`。
- 安装同一 `promptId` 表示替换其内容、类型和顺序，并重新置为 `ACTIVE`。
- 删除操作不物理删除记录，先置为 `INVALID`；同步完成后 Relay 删除本地内容。
- `sha256` 对原始 UTF-8 Prompt 字节计算，内容大小受独立上限保护。

## Center API 与 CLI

Center 提供：

```text
POST   /api/prompt/catalog/install
GET    /api/prompt/catalog
GET    /api/prompt/catalog/digest
GET    /api/prompt/catalog/{promptId}/content
DELETE /api/prompt/catalog/{promptId}
```

安装接口使用请求头传递 `promptId`、`type` 和 `order`，请求体为 UTF-8 文本。目录响应包含全部 `ACTIVE` 和 `INVALID` 元数据；只有 `ACTIVE` 项提供内容下载地址。

CLI 提供：

```text
ccrelay-cli prompt install prompt.md --id <id> --type UNIFIED|PRE|POST --order <n>
ccrelay-cli prompt list
ccrelay-cli prompt remove <id>
```

CLI 在上传前校验 ID、类型、顺序、文件大小和 UTF-8 编码，不在客户端自行推断 Prompt 类型或顺序。

## Relay 同步

Prompt 同步沿用 Skill 心跳触发、单线程异步执行和原子替换模式，但使用独立的 Prompt 元数据与缓存目录，避免 Prompt 与 Skill 的安装状态耦合。

同步步骤：

1. Relay 获取 Center Prompt catalog digest；与本地 digest 一致时结束。
2. 获取完整目录，按 `type/order/promptId` 生成可重复的 revision digest。
3. 对缺失或 SHA-256 不一致的 `ACTIVE` Prompt 下载内容，在暂存目录校验摘要后原子替换本地文件。
4. 对 Center 中 `INVALID` 或已经不存在的 Prompt，将本地元数据置为 `INVALID` 并删除内容文件。
5. 只有所有需要的 `ACTIVE` 内容均存在且校验通过后，才原子提交新的本地 catalog revision。
6. 部分下载失败时保留上一个完整 revision，并在后续心跳重试；不得发布混合版本。

Relay 执行任务前必须拿到可用的本地 revision。若已知 Center revision 更新但尚未完整同步，任务保持排队并触发同步；不能使用一半新、一半旧的 Prompt。

## 任务级 Prompt 快照

任务开始时从本地完整目录创建不可变快照，包含：

```text
revision
unifiedDigest
preDigest
postDigest
orderedUnified
orderedPre
orderedPost
```

同一任务的 PRE、ReAct 和 POST 必须使用该快照。任务开始后的安装、替换或失效只对新任务生效。

## 上下文边界

Center 共享上下文只有已接受的最终 Agent 回复和明确共享的事实。每个 Agent 节点维护自己的持久模型 Session，其逻辑结构为：

```text
[CC Relay 固定安全与职责 Prompt]
[按序 UNIFIED]
[Center 共享最终回复上下文]
[按序 PRE]
[私有 ReAct/tool 过程]
[按序 POST]
[最终回复]
```

其中固定安全与职责 Prompt 始终位于用户安装的 UNIFIED 之前，用户 Prompt 不能替换或越过该边界。

## UNIFIED 生命周期

- Center 管理内容与顺序，Relay 在任务边界检查本地模型 Session 已应用的 Prompt revision。
- 比较 Prompt ID、顺序、SHA-256 和聚合 digest；任一不同均视为 UNIFIED 变化。
- UNIFIED 变化时不能在旧 Session 中追加修补，因为旧 Prompt 仍会留在前缀。Relay 必须创建新的模型 Session，按“固定职责 + 新 UNIFIED + Center 共享上下文”重放。
- 新模型 Session 成功建立后才持久化已应用 revision；建立失败时保留旧状态并拒绝执行新任务。
- 没有 UNIFIED 变化时复用原模型 Session，保持共享前缀稳定。

## PRE 生命周期

- PRE 由执行任务的 Agent 管理触发时机，内容来自该任务固定的 Prompt 快照。
- Agent 被要求正式回复时，在进入本轮 ReAct 前向私有模型 Session 追加一个按序组合的 PRE block。
- PRE 不写入 Center 共享上下文，也不在同一执行尝试中重复追加。
- 幂等键为 `PRE:<sessionId>:<taskId>:<nodeId>:<attempt>`；只有开始新的 attempt 才允许重新追加。

## POST 生命周期

- 第一阶段模型完成正常 ReAct 后产生候选回复，该回复只保存在 Relay 私有内存中。
- 如果快照中没有 ACTIVE POST，候选回复直接成为最终回复，不增加模型调用。
- 如果存在 POST，Relay 将按序组合的 POST block 追加到同一个私有模型 Session，再发起一次 finalization 调用。
- finalization 明确要求基于候选回复输出最终答案，并禁用工具调用和节点协作；它不得启动新一轮 ReAct。
- 只有 finalization 返回成功后的第二阶段答案才写入 Center 共享上下文、任务结果和观察事件。
- 候选回复不得产生 `AGENT_MESSAGE`、`AGENT_RESULT` 或其他对用户可见事件；工具过程事件仍可按现有规则观测。
- finalization 失败返回 `POST_FINALIZATION_FAILED`，不发布候选回复，也不把失败调用内容写入共享上下文。

## 私有 Session 状态

每个节点会话状态在现有 cursor 和 model Session ID 之外增加：

```text
appliedPromptRevision
appliedUnifiedDigest
```

任务 attempt 级 PRE 幂等状态只需要覆盖当前执行和恢复场景，不作为 Center 共享上下文的一部分。Prompt revision 仅在对应模型 Session 成功创建或恢复后提交，避免 Relay 状态领先于真实模型状态。

## 一致性与并发

- Prompt catalog 的 revision 是目录级 SHA-256，计算输入必须包含 `promptId/type/order/sha256/status`，并使用长度前缀避免拼接歧义。
- 同一 Relay 同一节点会话的任务继续串行执行，Prompt Session 旋转与模型调用共享该串行边界。
- 同一 Prompt revision 可以被多个并发节点任务读取，快照对象不可变。
- Center 目录变更与旧任务并发时，旧任务完成其固定 revision，新任务等待本地同步到最新完整 revision。

## 失败语义

- Prompt 安装校验失败：拒绝安装，不变更旧 ACTIVE 记录。
- Relay 内容同步失败：保留旧完整 revision，不执行要求新 revision 的任务。
- UNIFIED Session 旋转失败：不更新已应用 revision，任务失败或继续排队。
- PRE 注入失败：本轮模型调用不开始，返回 Prompt 注入错误。
- POST finalization 失败：返回 `POST_FINALIZATION_FAILED`，候选回复保持私有并丢弃。
- 任何 Prompt 失败都不得降级为静默忽略，因为这会让不同 Relay 在同一目录版本下产生不一致行为。

## 安全限制

- Prompt 内容只接受有效 UTF-8 文本，拒绝空文件、NUL 字符和超过大小上限的内容。
- Prompt 内容文件路径由服务端根据 `promptId` 生成，不使用上传文件名。
- 内容下载路径必须验证仍位于 Prompt artifact 根目录内。
- 日志和错误响应只记录 Prompt ID、类型、revision 和摘要，不记录完整 Prompt 内容。

## 测试策略

- Catalog 单元测试覆盖安装、替换、排序、失效、摘要稳定性、UTF-8 与大小限制。
- Controller 测试覆盖 API 请求头、内容下载和错误输入。
- Python CLI 测试覆盖命令解析、请求路径、请求头、UTF-8 文件和错误校验。
- Relay 同步测试覆盖摘要短路、下载替换、失效删除、部分失败保留旧 revision。
- Session 测试覆盖 UNIFIED 未变化复用、变化后旋转与重放、失败不提交 revision。
- Runner 测试覆盖 PRE 幂等注入、任务快照固定、无 POST 单阶段、有 POST 两阶段、禁用工具和协作。
- Stream collector 测试覆盖候选模式不发出用户可见消息和结果，仅最终阶段发布。
- 集成测试验证多 Relay 在同一 revision 下得到相同排序，并确认 Center 共享上下文不包含 UNIFIED/PRE/POST 或候选回复。
