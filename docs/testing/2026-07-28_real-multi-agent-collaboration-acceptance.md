# 真实多 Agent 协同验收测试计划与执行记录

## 1. 目标

本测试用于补齐上一轮自动化测试未覆盖的“真实多 Agent 协同”层，重点验证：

- 已安装到 Codex 本机 `skills` 目录的 `ccrelay` 技能，能否被 Codex 子代理在真实任务中读取并遵循
- 子代理在面对“远端健康节点”和“远端不可用节点”两类任务时，是否能按技能文档给出正确决策
- 多个 Codex 子代理是否能并行参与同一验收主题，并产出与技能文档一致的协同结论
- 若发现技能指引、README 或测试入口仍有偏差，需先修复，再更新本文档后复测

## 2. 测试对象

- 技能安装目录：`C:\Users\liuqi\.codex\skills\ccrelay`
- 技能源目录：`C:\D\workspace\WDSAVS-dev-skill\codex-skill\ccrelay`
- 仓库目录：`C:\D\workspace\WDSAVS-dev-skill`
- 既有自动化测试计划：`C:\D\workspace\WDSAVS-dev-skill\docs\testing\2026-07-28_codex-skill-automation-test-plan.md`

## 3. 范围

### 3.1 范围内

- Codex 子代理读取已安装技能的前向任务测试
- “远端健康节点优先走 remote relay”语义验证
- “节点不可用时 `SELF_REPLICATE` 优先、SSH 最后兜底”语义验证
- 子代理输出与技能文档、运行手册、代码实现的一致性验证

### 3.2 范围外

- 外部真实服务器 SSH 联机
- Codex 产品内部未公开的技能自动选择埋点
- 真实跨机器远端 Codex 会话路由

## 4. 测试方法

- 使用 Codex 多子代理并行执行真实任务提示词
- 每个子代理只拿到用户式任务，不直接告诉它“你正在被测试”
- 验收方根据子代理输出，对照 `SKILL.md`、`remote-first-runbook.md` 与仓库实现进行一致性检查

## 5. 入口准则

- 技能已安装到 `C:\Users\liuqi\.codex\skills\ccrelay`
- `SKILL.md` 与 `references/remote-first-runbook.md` 已存在
- 上一轮自动化脚本与 Java 回归已经通过

## 6. 退出准则

- 至少两个 Codex 子代理参与本轮验收
- 覆盖“远端健康节点”和“远端不可用节点”两类场景
- 子代理输出明确体现：
  - 远端健康节点优先使用 remote relay / 技能路径
  - 节点不可用时先 `SELF_REPLICATE`，再 SSH 兜底
- 输出与仓库当前技能文档和实现一致
- 若发现偏差，已修复并复测

## 7. 测试用例

| 用例ID | 场景 | 执行主体 | 输入摘要 | 预期结果 |
|---|---|---|---|---|
| MA-01 | 远端健康节点 | 子代理 A | 用户要求访问已注册且健康的远端 relay 节点 | 明确给出远端优先路径，不应先走 SSH |
| MA-02 | 远端不可用节点 | 子代理 B | 用户要求恢复不可用目标节点后继续访问 | 明确给出 `SELF_REPLICATE` 优先、SSH 最后兜底 |
| MA-03 | 结果一致性复核 | 主代理 | 对比子代理输出、技能文档、代码实现 | 结论一致或发现偏差并修复 |

## 8. 执行记录（初始）

### 8.1 执行方式

- 使用 Codex 子代理并行前向执行真实任务提示词
- 子代理 A：健康远端节点访问路径验证
- 子代理 B：不可用远端节点恢复路径验证

### 8.2 实际执行记录

| 用例ID | 子代理 | 场景 | 实际结果 | 核心结论 |
|---|---|---|---|---|
| MA-01 | `019fa75a-8241-7d03-8d34-9bd4638a9be0` | 远端健康节点 | PASS | 不应先用 SSH；应先走 `<CLI> health` → `<CLI> relay scan` → `<CLI> session open` → `<CLI> access request` → `<CLI> a2a` 远端路径 |
| MA-02 | `019fa75b-4907-7c93-8f7d-066935df6a0b` | 远端不可用节点 | PASS | 不可用时先 `SELF_REPLICATE`，失败后才进入中心侧 SSH 兜底 |
| MA-03 | 主代理复核 | 一致性验证 | PASS | 两个子代理输出与技能文档、运行手册与仓库实现一致 |

### 8.3 子代理结果摘要

- 子代理 A 明确给出：健康远端节点必须优先走 remote relay 授权与 A2A 访问，SSH 不是首选路径。
- 子代理 B 明确给出：目标节点不可用时，必须先创建 `DEPLOY_RELAY` 且 `deployMode=SELF_REPLICATE`，只有在自复制失败后才进入中心侧 SSH 兜底。
- 两个子代理都给出了与 `SKILL.md` / `remote-first-runbook.md` 一致的顺序化决策。

## 9. 缺陷记录

| 缺陷ID | 发现时间 | 现象 | 根因 | 修复措施 | 状态 |
|---|---|---|---|---|---|
| DEF-MA-000 | 2026-07-28 | 本轮真实多 Agent 协同验收未发现新的实现偏差 | N/A | 无需修复 | CLOSED |

## 10. 复测记录（初始）

### 10.1 本轮是否需要复测

- 本轮未发现新的技能指引偏差、自动化脚本问题或实现缺陷。
- 因未触发新的修复动作，本轮真实多 Agent 协同验收无需再追加二次复测。

### 10.2 最终结论

- 已满足“至少两个 Codex 子代理参与”的验收要求。
- 已覆盖“远端健康节点”和“远端不可用节点”两类场景。
- 子代理输出均体现：
  - 远端健康节点优先 remote relay / 技能路径
  - 目标节点不可用时 `SELF_REPLICATE` 优先、SSH 最后兜底
- 输出与当前技能文档、运行手册、仓库实现一致。
- 因此，本轮“真实多 Agent 协同验收测试”在既定范围内通过。

## 11. 2026-07-31 真实 DeepSeek 远端 AI 链路复测

### 11.1 测试目标

本轮补充验证“远端 relay 已下发真实 CC 模型配置后，主 AI 通过标准 Skill / `<CLI>` 与远端 Agent 建立会话，并由远端 Agent 真实调用模型返回”的闭环。

### 11.2 测试前置

- 模型配置来源：本机 `deepseek-apikey.txt`，仅用于生成运行时 `cc-model-config.yml`，不在文档和提交中展示密钥。
- 配置连通性：`scripts/prepare_cc_config.py --test` 返回 `PASS`，模型为 `deepseek-v4-pro`，服务地址为 DeepSeek Anthropic 兼容入口。
- 目标节点：
  - `47.93.195.246:18091`
  - `111.229.32.85:18091`
- 两台主 relay 均已下发同一份 `cc-model-config.yml` 并重启。

### 11.3 执行方式

通过已安装 Skill 中的 `<CLI>` 发起多节点异步 fanout：

- 命令组：`<CLI> agent fanout`
- 会话模式：同一会话内多节点异步任务
- 目标节点：`47.93.195.246:18091,111.229.32.85:18091`
- AI 策略：`allow-ai=true`
- ReAct 策略：`maxSteps=2`、`auditLevel=FULL`
- 输入摘要：要求远端 Agent 直接用一句中文回复“真实 DeepSeek 模型已连通”，不执行命令。

### 11.4 实际结果

| 节点 | taskId | 状态 | 远端响应 |
|---|---|---|---|
| `47.93.195.246:18091` | `agent-task-c8999ac6-a39b-47dc-aada-260eb93ceda9` | `SUCCESS` | `真实 DeepSeek 模型已连通。` |
| `111.229.32.85:18091` | `agent-task-029055a4-da62-40f7-8957-f4ee1196e14e` | `SUCCESS` | `真实 DeepSeek 模型已连通。` |

抽查 `47.93.195.246:18091` 事件流，确认出现：

- `MODEL_REQUESTED`
- `MODEL_RESPONDED`
- `TASK_SUCCEEDED`
- `JAVA_ENFORCED_REACT`

### 11.5 结论

- 本轮不是 mock 响应，已真实触发远端模型调用。
- 两台远端主 relay 均可通过 `<CLI> agent fanout` 在同一会话内完成真实 AI 回复。
- 验收等级：`L5_JAVA_ENFORCED_REACT`。其中本轮覆盖的是 Java 托管 ReAct 的自然语言模型兜底分支；命令白名单、停止、注入、调参等动作级约束由前序远端联机测试覆盖。
