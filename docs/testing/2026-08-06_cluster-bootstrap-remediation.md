# 集群自举问题梳理与整改验收

## 目标

把首次 SSH 引导、远端 Center 与 Relay sidecar 启动、第二节点部署和多 Agent 协同收敛为可重复执行、失败可诊断、无需开发人员登录机器修复的标准 Skill 流程。

## 问题清单

| 编号 | 级别 | 问题 | 影响 | 整改要求 | 状态 |
|---|---|---|---|---|---|
| CB-01 | P0 | 用户选择执行模式前曾允许身份初始化产生远端变更 | 用户尚未授权时可能创建账号或写入密钥 | 凭据、账号模式、目录和执行模式全部确认后才允许远端变更 | 已修复，待端到端复验 |
| CB-02 | P0 | 资源探测前曾临时把首个节点当作 Center | Center 选择不稳定，可能选择低资源节点 | 先探测全部候选，再按确定性评分选 Center 和两个可用端口 | 已修复，待端到端复验 |
| CB-03 | P0 | Relay `/health` 返回 `SUCCESS`，门禁只接受 `UP` | Relay 实际已启动但 bootstrap 必然超时并回滚 | Center 与 Relay 健康协议统一为 `status=UP` | 已修复，待远端复验 |
| CB-04 | P0 | bootstrap 失败后未保存活动 Center，`center logs` 无法定位上次远端目录 | 后续只能人工 SSH 查日志 | 单独持久化最近一次 bootstrap 尝试；成功后清除，失败后供 `center logs` 查询 | 已实现，待自动化和远端复验 |
| CB-05 | P0 | 部分启动、可达性和注册失败分支先清理后缺少完整诊断 | 失败结果无法区分进程、端口、健康和注册问题 | 所有启动后失败分支先采集 Center/Relay 诊断，再清理并返回结构化结果 | 已实现，待自动化复验 |
| CB-06 | P1 | Center 宿主与 Center Relay 的角色边界曾混淆 | 可能出现中心向自身重复复制制品 | 同一目录一次上传 runtime bundle，分别启动 Center 和 Relay 两个进程；本机注册和心跳，不创建自复制任务 | 代码已符合，待远端复验 |
| CB-07 | P1 | 通用 SSH 用户曾被默认假定，密码输入可能暴露在对话中 | 缺乏通用性并存在凭据泄露风险 | 用户明确输入用户名；密码优先终端隐藏输入，DPAPI 或权限受限文件保存；节点凭据仅作覆盖 | 已修复，待全新配置复验 |
| CB-08 | P1 | 身份状态曾要求在 Center 启动前同步 SQLite | 正确的身份初始化会因 Center 尚未存在而失败 | 本地暂存为 `CENTER_IDENTITY_SYNC_DEFERRED`，Center 成功后执行 verify 同步 | 已修复，待端到端复验 |
| CB-09 | P1 | 自动模式之后仍向用户暴露注册地址、协议和端口 | 用户被迫理解底层部署参数 | 自动模式由代码完成资源选择、端口探测和端点生成；仅手动检视模式展示底层配置 | 设计和引导已修复，待交互复验 |
| CB-10 | P0 | 调用方超时后远端已就绪但本地未完成状态持久化 | 重试会换端口、重复传输并产生冲突进程 | 每阶段持久化；匹配最近尝试后验证健康、注册、心跳和可达性并直接认领 | 已实现，待远端恢复复验 |
| CB-11 | P1 | Center 同步 Relay 制品时同时上传 Claude 压缩包和约 275 MB 已解压二进制 | 首次自举耗时过长，外层命令可能先超时 | 远端只同步压缩归档并在安装时恢复二进制；完整预算默认 900 秒 | 已实现，待传输耗时复验 |
| CB-12 | P0 | 第二节点自复制曾归档整个 Center 工作目录，包含已解压 JRE、Claude、SQLite 和运行日志 | 传输超时，低磁盘目标无法完成安装 | 自复制载荷排除运行态与已解压大文件，保留跨平台压缩归档；中心保留归档，普通目标解压后删除归档 | 已实现，自动化通过，待真实第二节点复验 |
| CB-13 | P0 | `WAITING_USER_INPUT/SSH_CREDENTIAL_REQUIRED` 仍被普通任务超时扫描覆盖为 `TASK_TIMEOUT` | 用户补齐凭据后无法恢复原任务，审计链断裂 | 等待用户输入时暂停执行超时；恢复后继续同一任务，其他执行态仍正常超时 | 已实现，Java 回归通过，待真实恢复复验 |
| CB-14 | P1 | `task create` 缺少 `sessionId` 时由服务端返回 HTTP 500 | 错误晚且难定位，可能触发无意义预检 | CLI 在任何部署预检或 HTTP 请求前校验 `sessionId` 并返回明确错误 | 已实现，Python 回归通过 |
| CB-15 | P0 | Center 的签名服务优先读取数据库固定开发默认值，忽略部署时传播的 HMAC secret | Center 签发的 grant token 与 Relay 校验密钥不一致，所有真实 A2A 请求失败 | 统一 HMAC 解析优先级为环境变量、系统属性、显式运行时配置；删除固定开发默认值，并用跨实例签名回归测试覆盖 | 已修复；Java、Skill 构建和双节点真实 A2A 通过 |
| CB-16 | P0 | 覆盖部署先替换运行目录，旧 PID 文件随目录消失；受限 PATH 又可能找不到 `ps` | 健康检查命中旧 Relay，部署任务误报成功；旧 JVM 随后因运行时文件被替换而无法启动 Claude | 覆盖前由部署器用绝对路径精准停止命令行同时匹配 `RemoteCcRelayServer` 和目标端口的进程；安装脚本固定基础 PATH，并以新进程 `/health=status=UP` 作为成功门禁 | 已修复，真实 Center 与目标 Relay 滚动更新通过 |
| CB-17 | P0 | Claude 压缩包提取失败曾被 `|| true` 忽略，且启动前不验证二进制 | Relay 可健康注册，但首个真实 AI 任务才暴露 `posix_spawn error=2` | bundled Claude 缺失时必须成功提取，随后执行 `claude --version`；失败立即终止部署，不允许进入注册和健康阶段 | 已修复，目标节点真实 Claude A2A 通过 |
| CB-18 | P0 | 旧 Relay 扫描的多行 `awk` 条件在部分 Linux `awk` 实现上语法失败，且错误被兜底吞掉 | 覆盖部署可能漏停旧进程，健康门禁仍有命中旧实例的风险 | 使用单行 POSIX `awk` 精确匹配 Relay 类名和端口；进程扫描解析失败必须使安装失败 | 已修复，Center 二次重建无 stdout/stderr 告警 |
| CB-19 | P0 | runtime bundle 构建先删除输出目录，再从该目录复制模型配置 | 新制品丢失真实模型配置，部署后只能健康但不能完成 AI 对话 | 构建清理前按原始字节保留既有配置，清理后恢复到 bundle；配置继续被 Git 忽略 | 已修复，重建包包含配置且双节点真实 AI 通过 |
| CB-20 | P0 | 自复制任务未自动注入目标可达端点，Relay 注册为 `0.0.0.0` | 节点健康但中心无法发起后续 A2A | CLI 根据目标主机和实际 Relay 端口自动注入 node host 与对外 relay endpoint | 已修复，待真实滚动复验 |
| CB-21 | P0 | 部署前使用 IP+端口，注册后 `nodeId` 使用机器名+端口 | Relay 已注册健康但部署任务永久停在 `WAIT_REGISTER` | 中心按端口和规范化工作目录关联部署记录与最终注册节点，并保存最终 `nodeId` | 已修复，待真实滚动复验 |
| CB-22 | P1 | 目录制品复制后额外嵌套源目录名 | 实际工作目录与配置预览不一致，后续覆盖和观测路径漂移 | 目录内容直接落入渲染后的目标目录，脚本以该目录作为 bundle root | 已修复，待真实滚动复验 |
| CB-23 | P1 | 健康远端 Center 会被 `center bootstrap` 直接复用 | 本地新 JAR 无法通过标准 CLI 滚动到远端，只能人工替换 | 新增 `center bootstrap --force-redeploy`，固定当前节点和端口并保留 SQLite 原位更新 | 已修复，待真实滚动复验 |

## 实施顺序

1. 运行 Python 单元测试，覆盖健康失败、注册失败、失败尝试持久化和日志回取。
2. 运行 Java 定向测试，确认 Relay 健康响应为标准 `UP`。
3. 执行标准 Skill 构建、Skill Creator 校验和覆盖安装。
4. 在干净远端状态执行首次引导，确认执行模式门禁之前无远端变更。
5. 自动选择 Center，启动 Center 与同机 Relay，验证注册和首次心跳。
6. 从 Center Relay 自复制部署第二个 Relay，验证精简载荷、目标归档释放、注册、健康和心跳。
7. 重复执行 bootstrap，验证不会重复创建账号、复制自身或产生冲突进程。
8. 人为制造一次 SSH 凭据缺失，验证任务保持 `WAITING_USER_INPUT` 超过原超时边界，补齐条件后恢复同一任务。
9. 人为制造一次 Relay 健康失败，验证 `center logs` 可直接读取最近失败尝试，不使用人工 SSH。
10. 通过一个会话向两个节点异步 fanout，分别查询状态和事件，最终汇总结果。

## 完成标准

- 所有 P0 问题通过自动化测试和真实双节点测试。
- Center、Center Relay、普通 Relay 均可通过 CLI 查询状态、日志、注册和心跳。
- 任一标准失败都返回失败阶段、原因、诊断和清理结果，不要求开发人员修改远端文件或进程。
- 重试可恢复，且不覆盖已有可用 Center，不重复创建专用账号，不向自身创建复制任务。
- 多 Agent 协同只使用 `ccrelay-cli` 的会话、授权、任务和观测命令完成；SSH 只允许出现在首次部署或专门的故障注入验收中。

## 2026-08-06 HMAC 与多 Agent 真实复验

### 修复前现象

- Center `/config/secret/get` 返回的指纹是固定开发默认值，而 Skill 部署到 Center Relay 和目标 Relay 的是随机共享密钥。
- `agent run` 能完成会话和授权申请，但远端返回 `Grant token validation failed`，因此不能把 Relay 健康误判为 A2A 可用。

### 修复后验证

- 使用 Skill 内置 `center remote-stop` 和 `center bootstrap` 重新分发当前制品；没有手工 SSH、手工 HTTP 或人工修改远端文件。
- Center、同机 Center Relay 和目标 Relay 均通过 health、注册、首次心跳，`relay scan` 返回 2/2 `AVAILABLE`。
- 单节点自然语言请求返回真实远端服务巡检结果，状态 `SUCCESS`，执行模式 `ReAct`。
- 双节点 `agent fanout` 在同一会话内异步创建两个任务；逐节点状态查询最终均为 `SUCCESS`，两端均报告 `JAVA_ENFORCED_REACT`。
- 对仍运行的目标任务执行 `agent inject` 和 `agent adjust`，两个控制请求均返回 `SUCCESS` 并写入审计事件；随后目标任务正常收敛为 `SUCCESS`。

### 结论

本轮已闭环验证 `Skill CLI -> Center -> grant/HMAC -> Relay -> 真实 AI -> 多节点 fanout -> 运行中控制 -> 状态查询`。HMAC 不一致问题已纳入代码、单测、构建和验收文档，不依赖开发人员进入远端修复。

## 2026-08-06 覆盖部署假阳性复验

- 远端 Center 与同机 sidecar 使用新 bundle 重建，`relayStart` 退出码为 0，stdout/stderr 均为空，Center health、sidecar health、注册、首次心跳和 `FULL_MESH` 身份同步全部通过。
- 会话 `72b872a8-9cd5-4753-8ff1-55677ed5a3de` 内创建 `DEPLOY_RELAY` 任务 `62a90c18-222c-422c-974e-6e6386ba08c8`，由 `47.93.195.246:18192` 对 `111.229.32.85:18191` 执行 `SELF_REPLICATE`。
- 部署任务最终为 `SUCCESS / REGISTERED`，退出码 0，中心确认目标注册和心跳；随后 `relay scan` 返回 2/2 `AVAILABLE`。
- 目标节点真实 Claude ReAct 请求成功返回本机监听服务证据，不再出现 `posix_spawn error=2`。
- 同一会话双节点 fanout 任务 `agent-task-be4102fc-b515-426c-a743-f5c5bca7ebcc` 与 `agent-task-e1144d96-975a-4d51-b6af-08d63712a408` 均为 `SUCCESS`，两端均报告 `JAVA_ENFORCED_REACT / JAVA_REACT_RUNNER_ENABLED`。
