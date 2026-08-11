# Codex 技能自动化测试计划与执行记录

## 1. 文档目的

本文档用于定义并执行 `ccrelay` 技能的自动化测试闭环，覆盖以下目标：

- Codex 技能可被安装到 Codex 本机 `skills` 目录
- `SKILL.md` 能明确引导 Codex 在访问远端节点时优先使用该技能
- 当远端不可用时，部署策略满足“`SELF_REPLICATE` 优先，SSH 最后兜底”
- 自动化脚本、Java 回归测试、集成测试与文档入口保持一致
- 若测试过程中发现问题，先修复实现，再更新本文档，然后重新执行测试

## 2. 测试对象

- 仓库：`C:\D\workspace\WDSAVS-dev-skill`
- 技能源目录：`C:\D\workspace\WDSAVS-dev-skill\codex-skill\ccrelay`
- Codex 安装目录：`C:\Users\liuqi\.codex\skills\ccrelay`
- 自动化脚本：
  - `C:\D\workspace\WDSAVS-dev-skill\scripts\install_codex_skill.py`
  - `C:\D\workspace\WDSAVS-dev-skill\scripts\install_codex_skill.ps1`
  - `C:\D\workspace\WDSAVS-dev-skill\scripts\test_codex_skill_flow.py`
  - `C:\D\workspace\WDSAVS-dev-skill\scripts\test_codex_skill_flow.ps1`
- 关键测试类：
  - `C:\D\workspace\WDSAVS-dev-skill\src\test\java\com\webank\wedatasphere\wdsavs\aiagent\service\A2aAgentServiceMultiNodeLocalTest.java`
  - `C:\D\workspace\WDSAVS-dev-skill\src\test\java\com\webank\wedatasphere\wdsavs\aiagent\service\A2aTaskRelayServerMultiNodeLocalTest.java`
  - `C:\D\workspace\WDSAVS-dev-skill\src\test\java\com\webank\wedatasphere\wdsavs\aiagent\service\AiRelayDeployRemoteFirstFallbackTest.java`
  - `C:\D\workspace\WDSAVS-dev-skill\src\test\java\com\webank\wedatasphere\wdsavs\aiagentskill\AiAgentSkillRuntimeStandaloneIntegrationTest.java`

## 3. 测试范围

### 3.1 范围内

- 技能文档 frontmatter 与说明内容正确性
- 技能本地安装路径与安装产物一致性
- README 给出的自动化命令可直接执行
- 技能引导语义是否覆盖“远端优先 + SSH 兜底”
- Java 编译、单测、集成测试、脚本测试
- 部署策略：`SELF_REPLICATE` 失败后中心 SSH 兜底
- 本地运行时与远端 relay 的 A2A 基本能力
- 非开发者子 agent 通过自然语言场景执行功能与稳定性验收

### 3.2 范围外

- 真实线上远端服务器部署
- 真实外部 SSH 免密环境联机验证
- 真实 Codex 产品内部技能选择器黑盒埋点验证

## 4. 测试环境

- 操作系统：Windows
- 日期：2026-07-28
- 仓库根目录：`C:\D\workspace\WDSAVS-dev-skill`
- Codex Home：`C:\Users\liuqi\.codex`
- Python：优先使用非 `WindowsApps` 解释器
- Gradle 包装脚本：`C:\D\workspace\WDSAVS-dev-skill\gradlew.bat`

## 5. 入口准则

- `codex-skill/ccrelay/SKILL.md` 已存在
- 自动化脚本已纳入仓库
- 关键 Java 测试类已存在且可编译

## 6. 退出准则

- 技能可安装到 `C:\Users\liuqi\.codex\skills`
- `SKILL.md` 包含远端优先与 SSH 兜底关键指导语
- 自动化主脚本执行完成且返回 `PASS`
- 指定 Java 回归测试全部通过
- 若执行中发现问题，问题已修复，且本文档已记录问题、修复与复测结果

## 7. 风险与关注点

- Windows 的 `python` 命令可能命中 `WindowsApps` 别名，导致脚本执行不稳定
- 真实 Codex UI 内部是否“选择了技能”无法直接通过黑盒埋点证明，只能通过安装路径、文档内容、脚本链路和回归测试进行强间接验证
- SSH 兜底在当前测试环境中属于代码路径验证，不是外部真实目标机联机验证

## 8. 测试策略

### 8.1 分层策略

- 文档层：验证技能 frontmatter、关键指导语、README 命令
- 安装层：验证技能安装到 Codex 本机技能目录
- 脚本层：验证 PowerShell / Python 自动化入口
- 业务层：验证远端 relay、A2A 与部署兜底路径
- 回归层：整合所有关键测试项做最终复测
- 场景层：由非开发者子 agent 按自然语言问题触发 `<CLI>` 工作流，验证用户视角闭环

### 8.2 非开发者子 agent 验收

- 主线程只提供自然语言场景，不提供实现细节、代码位置或手工修复步骤。
- 子 agent 作为普通使用者，只通过 skill 文档和 `<CLI>` 路径操作。
- 子 agent 需要覆盖安装、本地控制、远端 relay、A2A、真实模型、Java 强约束 ReAct 和稳定性场景。
- 子 agent 每个场景都必须记录：用户问题、执行步骤、关键命令、实际结果、是否需要人工介入。
- 只要需要人工 SSH 登录远端或手工修改本机/远端文件才能继续，就判定该场景稳定性未通过。

### 8.3 执行顺序

1. 先编写测试计划与测试用例
2. 运行文档与脚本校验
3. 运行 Java 编译与回归测试
4. 启动非开发者子 agent 执行自然语言场景验收
5. 记录问题
6. 修复问题
7. 回写文档
8. 重新执行全量关键测试与子 agent 场景验收

## 9. 测试用例明细

| 用例ID | 用例名称 | 目标 | 执行方式 | 预期结果 |
|---|---|---|---|---|
| TC-01 | 技能文档格式校验 | 验证 `SKILL.md` frontmatter 合法 | `quick_validate.py` | 校验通过 |
| TC-02 | 技能安装路径校验 | 验证技能安装到 Codex 本机 `skills` 目录 | 执行安装脚本 | 目标目录存在且包含 `SKILL.md` |
| TC-03 | 安装内容一致性 | 验证安装后的 `SKILL.md` 与源码一致 | 脚本对比文件内容 | 完全一致 |
| TC-04 | 远端优先引导语校验 | 验证技能文档包含“远端优先”策略 | 搜索关键短语 | 命中预期关键字 |
| TC-05 | SSH 兜底引导语校验 | 验证技能文档包含“SSH 最后兜底”策略 | 搜索关键短语 | 命中预期关键字 |
| TC-06 | README 自动化命令校验 | 验证 README 提供的命令可执行 | 按 README 执行 | 命令成功 |
| TC-07 | PowerShell 自动化脚本 | 验证 `test_codex_skill_flow.ps1` 可直接执行 | PowerShell 调用 | 返回 `PASS` |
| TC-08 | Python 自动化脚本 | 验证 Python 主脚本执行稳定性 | `python` / 实际解释器调用 | 至少一个官方脚本稳定可用 |
| TC-09 | Java 编译校验 | 验证测试代码完整可编译 | `gradlew.bat testClasses` | 编译通过 |
| TC-10 | 远端 relay 消息协同 | 验证远端 A2A 消息协同路径 | `A2aAgentServiceMultiNodeLocalTest` | 测试通过 |
| TC-11 | 远端 relay 任务协同 | 验证远端 A2A 任务 create/get/cancel | `A2aTaskRelayServerMultiNodeLocalTest` | 测试通过 |
| TC-12 | 独立技能运行时集成 | 验证运行时 + relay + SQLite + 影子任务 | `AiAgentSkillRuntimeStandaloneIntegrationTest` | 测试通过 |
| TC-13 | 自复制优先/SSH 兜底 | 验证 `SELF_REPLICATE` 失败后切换 SSH | `AiRelayDeployRemoteFirstFallbackTest` | 测试通过 |
| TC-14 | 全链路回归 | 汇总关键测试做最终确认 | 自动化主脚本 | 返回 `PASS` |
| TC-15 | 非开发者场景验收 | 验证普通用户自然语言是否能触发正确 `<CLI>` 流程 | 子 agent 场景执行 | 输出分级验收报告 |
| TC-16 | 稳定性重复验收 | 验证关键场景多轮执行不需要手工修复 | 子 agent 重复执行 | 无人工介入且结果稳定 |

## 10. 预期执行命令

### 10.1 文档与安装

```powershell
powershell -ExecutionPolicy Bypass -File C:\D\workspace\WDSAVS-dev-skill\scripts\validate_codex_skill.ps1
powershell -ExecutionPolicy Bypass -File C:\D\workspace\WDSAVS-dev-skill\scripts\install_codex_skill.ps1 C:\D\workspace\WDSAVS-dev-skill\codex-skill\ccrelay --force
```

### 10.2 Java 回归

```powershell
C:\D\workspace\WDSAVS-dev-skill\gradlew.bat testClasses --console=plain
C:\D\workspace\WDSAVS-dev-skill\gradlew.bat test --tests "com.webank.wedatasphere.wdsavs.aiagent.service.A2aAgentServiceMultiNodeLocalTest" --tests "com.webank.wedatasphere.wdsavs.aiagent.service.A2aTaskRelayServerMultiNodeLocalTest" --tests "com.webank.wedatasphere.wdsavs.aiagent.service.AiRelayDeployRemoteFirstFallbackTest" --tests "com.webank.wedatasphere.wdsavs.aiagentskill.AiAgentSkillRuntimeStandaloneIntegrationTest" --console=plain
```

### 10.3 自动化总脚本

```powershell
powershell -ExecutionPolicy Bypass -File C:\D\workspace\WDSAVS-dev-skill\scripts\test_codex_skill_flow.ps1
```

## 11. 缺陷记录模板

| 缺陷ID | 发现时间 | 现象 | 影响范围 | 根因 | 修复措施 | 状态 |
|---|---|---|---|---|---|---|
| DEF-001 | 2026-07-28 | 裸 `python` 调用 `quick_validate.py` 与 `test_codex_skill_flow.py` 返回 `9009` | Windows 文档级直接 Python 入口 | 当前环境 `python` 命令不可稳定解析为可用解释器 | 新增 `validate_codex_skill.ps1`，并将 README 与测试计划中的官方执行入口统一为 PowerShell 包装脚本 | CLOSED |

## 12. 执行记录

### 12.1 首轮执行（发现问题）

| 用例ID | 执行时间 | 执行命令 | 结果 | 备注 |
|---|---|---|---|---|
| TC-01 | 2026-07-28 | `python ...quick_validate.py ...` | FAIL | 退出码 `9009` |
| TC-02 | 2026-07-28 | `install_codex_skill.ps1` | PASS | 技能成功安装 |
| TC-08 | 2026-07-28 | `python scripts/test_codex_skill_flow.py` | FAIL | 退出码 `9009` |
| TC-07 | 2026-07-28 | `test_codex_skill_flow.ps1` | PASS | 返回 `PASS` |
| TC-09 | 2026-07-28 | `gradlew.bat testClasses` | PASS | 编译通过 |
| TC-10/11/12/13 | 2026-07-28 | `gradlew.bat test --tests ...` | PASS | 关键回归通过 |

### 12.2 问题分析

- 问题类型：环境兼容性 / 文档入口不稳
- 问题表现：测试计划初版中的裸 `python` 命令在当前 Windows 环境下不可稳定执行
- 影响判断：不影响技能逻辑与 Java 回归，但影响“按文档直接执行”这一验收项

### 12.3 修复措施

- 新增 `C:\D\workspace\WDSAVS-dev-skill\scripts\validate_codex_skill.ps1`
- 保持 `install_codex_skill.ps1` 与 `test_codex_skill_flow.ps1` 作为 Windows 官方脚本
- 更新 `README.md` 与本文档，将校验/安装/总回归命令统一为 PowerShell 包装脚本

## 13. 复测记录

### 13.1 修复后复测用例

| 用例ID | 执行时间 | 执行命令 | 预期 |
|---|---|---|---|
| RTC-01 | 2026-07-28 | `validate_codex_skill.ps1` | 技能校验通过 |
| RTC-02 | 2026-07-28 | `install_codex_skill.ps1` | 技能安装通过 |
| RTC-03 | 2026-07-28 | `test_codex_skill_flow.ps1` | 返回 `PASS` |
| RTC-04 | 2026-07-28 | `gradlew.bat testClasses` | 编译通过 |
| RTC-05 | 2026-07-28 | `gradlew.bat test --tests ...` | 关键回归通过 |

### 13.2 复测结论

| 用例ID | 实际结果 | 证据 |
|---|---|---|
| RTC-01 | PASS | `validate_codex_skill.ps1` 输出 `Skill is valid!` |
| RTC-02 | PASS | 安装结果输出 `C:\Users\liuqi\.codex\skills\ccrelay` |
| RTC-03 | PASS | `test_codex_skill_flow.ps1` 返回 JSON 且 `status = PASS` |
| RTC-04 | PASS | `gradlew.bat testClasses` 输出 `BUILD SUCCESSFUL` |
| RTC-05 | PASS | `gradlew.bat test --tests ...` 输出 `BUILD SUCCESSFUL` |

### 13.3 最终结论

- 文档先行：已在本文件中定义完整测试计划、范围、入口/退出准则和细粒度用例。
- 测试发现问题：首轮执行发现 Windows 环境下裸 `python` 文档入口不稳定，返回 `9009`。
- 问题已修复：新增 `validate_codex_skill.ps1`，并统一 README 与测试计划中的官方执行入口为 PowerShell 包装脚本。
- 修复后复测通过：校验、安装、自动化总脚本、Java 编译、关键回归测试全部通过。
- 当前可接受结论：在当前本机环境下，技能安装、文档引导、远端优先语义、`SELF_REPLICATE` 优先与 SSH 兜底代码路径均已完成自动化验证。

## 14. 按设计文档补充的扩展测试矩阵

本节补齐 `docs/design/standalone-skill-runtime.md`、`codex-skill/ccrelay/SKILL.md` 与 `references/remote-first-runbook.md` 中尚未被显式拆分的设计点，目标是把 Agent 的控制面、授权面、任务面、部署面、恢复面、文档面全部纳入覆盖。

### 14.1 控制面与会话

| 用例ID | 用例名称 | 目标 | 预期结果 |
|---|---|---|---|
| TC-15 | 会话打开/关闭 | 验证 `<CLI> session open/close` 生命周期 | 会话可打开、可关闭、状态可追踪 |
| TC-16 | 多会话隔离 | 验证不同会话的影子任务不串线 | 同一节点多会话并行时互不污染 |
| TC-17 | 会话恢复 | 验证重启后本地 SQLite 能恢复会话上下文 | 重启后仍可查询历史会话/任务 |
| TC-18 | 关闭后不可继续写入 | 验证 close 后任务继续写入被阻断或标记异常 | 已关闭会话不应继续接收新任务 |

### 14.2 授权与安全

| 用例ID | 用例名称 | 目标 | 预期结果 |
|---|---|---|---|
| TC-19 | 默认 TTL 30 分钟 | 验证授权默认有效期 | 未显式配置时为 30 分钟 |
| TC-20 | TTL 可配置 | 验证授权有效期可覆盖默认值 | 配置后按新 TTL 生效 |
| TC-21 | 默认自动批准 | 验证策略命中时自动批准 | 默认无需人工介入 |
| TC-22 | 关闭自动批准 | 验证自动批准开关可禁用 | 关闭后进入人工/外部审批流 |
| TC-23 | 默认允许续期 | 验证授权支持续期 | 续期请求成功并刷新 expiresAt |
| TC-24 | 默认立即失效 | 验证 revoke 立即生效 | revoke 后旧 token 立即不可用 |
| TC-25 | HMAC 签名校验 | 验证授权 token 伪造/篡改会失败 | 签名不匹配时拒绝 |
| TC-26 | 过期校验 | 验证过期授权不可用 | 超时后请求被拒绝 |
| TC-27 | 节点白名单 | 验证默认通配符与可配置白名单 | 默认全放行，配置后按白名单收敛 |

### 14.3 节点注册与健康

| 用例ID | 用例名称 | 目标 | 预期结果 |
|---|---|---|---|
| TC-28 | nodeId 规则 | 验证 nodeId 采用机器名/host + 端口 | 注册后 nodeId 稳定且可持久化 |
| TC-29 | 注册后才可用 | 验证必须回注册中心后才算节点可用 | 仅启动不注册时不可授权直连 |
| TC-30 | 健康检查门禁 | 验证健康检查通过与注册成功同时满足才可用 | 两条件缺一不可 |
| TC-31 | 心跳默认间隔 | 验证默认 30 秒心跳 | 未配置时按 30 秒发送 |
| TC-32 | 心跳间隔可配置 | 验证心跳间隔可覆盖默认值 | 配置后按新间隔发送 |
| TC-33 | 失联阈值默认 3 次 | 验证离线判定规则 | 连续 3 次失联后标记异常 |
| TC-34 | 健康扫描状态 | 验证 `UP/DEGRADED/UNAVAILABLE` 分类 | 扫描结果与实际心跳一致 |

### 14.4 A2A 与任务面

| 用例ID | 用例名称 | 目标 | 预期结果 |
|---|---|---|---|
| TC-35 | A2A 消息发送 | 验证同步消息路径 | 远端 relay 可正常应答 |
| TC-36 | A2A 任务 create|get|cancel | 验证异步任务全生命周期 | create/get/cancel 全通过 |
| TC-37 | SSE 事件流 | 验证任务事件流可订阅 | 流式事件与状态查询一致 |
| TC-38 | 状态查询兜底 | 验证 SSE 不可用时可用状态查询兜底 | 状态可从查询命令恢复 |
| TC-39 | 引用传参 | 验证大文件只传引用不直接塞大体积内容 | 超阈值转引用传输 |
| TC-40 | 5MB 阈值 | 验证默认大文件阈值 | 默认 5MB，且可配置 |
| TC-41 | 任务取消 | 验证 cancel 能终止未完成任务 | 任务状态转为 cancelled/failed |
| TC-42 | 父子任务隔离 | 验证多协同 Agent 不串 taskId | 同会话内任务链路清晰 |

### 14.5 部署面与恢复面

| 用例ID | 用例名称 | 目标 | 预期结果 |
|---|---|---|---|
| TC-43 | 远端优先直连 | 验证已健康注册节点优先直连 | 不先走 SSH |
| TC-44 | SELF_REPLICATE 优先 | 验证节点不可用时先自复制 | 先尝试源端自复制 |
| TC-45 | 自复制失败后中心代部署 | 验证失败后进入中心 SSH 兜底 | 仅在自复制失败后兜底 |
| TC-46 | 远端回注册 | 验证目标节点启动后必须回注册中心 | 注册 + 健康检查都通过 |
| TC-47 | 默认远端目录 | 验证默认目录 `/home/<username>/ccrelay` | 省略目录时自动落到默认值 |
| TC-48 | 制品包启动无需系统 Java | 验证远端不依赖已安装 Java | 使用内置运行时直接启动 |
| TC-49 | PATH 受限环境 | 验证远端 PATH 缺失时仍可部署 | 不依赖 `mkdir/cat/java` 默认 PATH |
| TC-50 | 部署回滚 | 验证失败后可回滚清理 | 失败后残留状态可控 |

### 14.6 文档与安装链路

| 用例ID | 用例名称 | 目标 | 预期结果 |
|---|---|---|---|
| TC-51 | 技能安装一致性 | 验证源码与安装目录一致 | 安装后文档内容完全一致 |
| TC-52 | 文档引导词 | 验证 `SKILL.md` 明确远端优先语义 | 关键术语可命中 |
| TC-53 | 运行手册可执行性 | 验证 `remote-first-runbook.md` 能指导实际流程 | 步骤与实现一致 |
| TC-54 | 自动化脚本 | 验证 `test_codex_skill_flow.ps1` 一键执行 | 返回 `PASS` |

### 14.7 推荐补测顺序

1. 先补 TC-15 ～ TC-18，锁定会话隔离与恢复
2. 再补 TC-19 ～ TC-27，锁定授权、安全与 token 语义
3. 再补 TC-28 ～ TC-34，锁定注册、心跳、可用性判定
4. 再补 TC-35 ～ TC-42，锁定 A2A、任务、事件、取消
5. 最后补 TC-43 ～ TC-50，锁定真实部署、恢复与兜底
6. 最后用 TC-51 ～ TC-54 回读文档与自动化入口，确保“文档即流程”

## 15. 可执行测试用例拆解与实施优先级

本节把 TC-15 ～ TC-54 进一步拆为“测试类型、建议落点、自动化状态、实施优先级”，便于直接进入编码或联机执行。

### 15.1 用例落点映射

| 用例ID | 建议测试类型 | 建议落点 | 自动化状态 | 优先级 |
|---|---|---|---|---|
| TC-15 | 集成测试 | `AiAgentSkillRuntimeStandaloneIntegrationTest` | 待补 | P0 |
| TC-16 | 集成测试 | `AiAgentSkillRuntimeStandaloneIntegrationTest` | 待补 | P0 |
| TC-17 | 集成测试 | 新增 `AiAgentSkillRuntimePersistenceIntegrationTest` | 待补 | P1 |
| TC-18 | 集成测试 | `AiAgentSkillRuntimeStandaloneIntegrationTest` | 待补 | P1 |
| TC-19 | 单元测试 | 新增 `AiRelayGrantServiceImplTest` | 待补 | P0 |
| TC-20 | 单元测试 | 新增 `AiRelayGrantServiceImplTest` | 待补 | P1 |
| TC-21 | 单元测试 | 新增 `AiRelayGrantServiceImplTest` | 待补 | P0 |
| TC-22 | 单元测试 | 新增 `AiRelayGrantServiceImplTest` | 待补 | P1 |
| TC-23 | 单元+集成 | `AiRelayGrantServiceImplTest` + 运行时集成测试 | 待补 | P1 |
| TC-24 | 单元+集成 | `AiRelayGrantServiceImplTest` + 运行时集成测试 | 待补 | P0 |
| TC-25 | 单元测试 | `RelayGrantTokenServiceImplTest` | 部分已覆盖，需补全 | P0 |
| TC-26 | 单元测试 | 新增 `AiRelayGrantServiceImplTest` | 待补 | P0 |
| TC-27 | 单元+集成 | `AiRelayGrantServiceImplTest` + 运行时集成测试 | 待补 | P1 |
| TC-28 | 单元测试 | 新增 `AiRelayRegistryServiceImplTest` | 待补 | P0 |
| TC-29 | 集成测试 | `AiAgentSkillRuntimeStandaloneIntegrationTest` | 待补 | P0 |
| TC-30 | 集成测试 | `AiAgentSkillRuntimeStandaloneIntegrationTest` | 待补 | P0 |
| TC-31 | 单元测试 | 新增 `AiRelayHeartbeatServiceImplTest` | 待补 | P1 |
| TC-32 | 单元测试 | 新增 `AiRelayHeartbeatServiceImplTest` | 待补 | P1 |
| TC-33 | 单元测试 | 新增 `AiRelayHeartbeatServiceImplTest` | 待补 | P0 |
| TC-34 | 集成测试 | `AiAgentSkillRuntimeStandaloneIntegrationTest` | 待补 | P0 |
| TC-35 | 集成测试 | `A2aAgentServiceMultiNodeLocalTest` | 已覆盖主路径 | P0 |
| TC-36 | 集成测试 | `A2aTaskRelayServerMultiNodeLocalTest` | 已覆盖主路径 | P0 |
| TC-37 | 集成测试 | `A2aTaskRelayServerMultiNodeLocalTest` | 已覆盖主路径，需补断流分支 | P1 |
| TC-38 | 集成测试 | `A2aTaskRelayServerMultiNodeLocalTest` | 待补 | P1 |
| TC-39 | 集成测试 | `A2aAgentServiceMultiNodeLocalTest` / `A2aTaskRelayServerMultiNodeLocalTest` | 部分已覆盖 | P1 |
| TC-40 | 单元+集成 | `A2aPayloadPolicyServiceImplTest`（新） | 待补 | P1 |
| TC-41 | 集成测试 | `A2aTaskRelayServerMultiNodeLocalTest` | 已覆盖主路径 | P0 |
| TC-42 | 集成测试 | `AiAgentSkillRuntimeStandaloneIntegrationTest` | 待补 | P1 |
| TC-43 | 联机验收 | `2026-07-28_remote-ssh-external-acceptance-plan.md` | 已实测主路径 | P0 |
| TC-44 | 单元+联机 | `AiRelayDeployRemoteFirstFallbackTest` + 联机验收 | 顺序已覆盖，真实成功链待补 | P0 |
| TC-45 | 单元+联机 | `AiRelayDeployRemoteFirstFallbackTest` + 联机验收 | 已覆盖 | P0 |
| TC-46 | 联机验收 | `2026-07-28_remote-ssh-external-acceptance-plan.md` | 已实测 | P0 |
| TC-47 | 单元测试 | `RemoteSelfReplicateServiceDefaultRemoteDirectoryTest` | 已覆盖 | P0 |
| TC-48 | 联机验收 | `2026-07-28_remote-ssh-external-acceptance-plan.md` | 已实测 | P0 |
| TC-49 | 联机验收 + 单元 | `SshDeployExecutorImpl` 相关测试 + 联机记录 | 已实测，需补单测 | P0 |
| TC-50 | 单元+联机 | 新增 `AiRelayDeployRollbackTest` + 联机验收 | 待补 | P1 |
| TC-51 | 脚本测试 | `test_codex_skill_flow.py` | 已覆盖 | P0 |
| TC-52 | 脚本测试 | `test_codex_skill_flow.py` | 已覆盖 | P0 |
| TC-53 | 文档验收 | 手工+脚本辅助检查 | 待补 | P1 |
| TC-54 | 脚本测试 | `test_codex_skill_flow.ps1` | 已覆盖 | P0 |

### 15.2 直接可开工的测试类建议

| 新增测试类 | 主要覆盖 | 对应用例 |
|---|---|---|
| `AiRelayGrantServiceImplTest` | TTL、自动批准、续期、撤销、过期、白名单 | TC-19 ～ TC-27 |
| `AiRelayRegistryServiceImplTest` | nodeId 规则、注册门禁 | TC-28 ～ TC-30 |
| `AiRelayHeartbeatServiceImplTest` | 间隔、离线阈值、状态分类 | TC-31 ～ TC-34 |
| `AiAgentSkillRuntimePersistenceIntegrationTest` | SQLite 重启恢复、多会话隔离 | TC-16 ～ TC-18 |
| `A2aPayloadPolicyServiceImplTest` | 大文件阈值、引用替换、路径策略 | TC-39 ～ TC-40 |
| `AiRelayDeployRollbackTest` | 部署失败后的回滚与状态修复 | TC-50 |

### 15.3 仍需真实联机覆盖的关键链路

以下链路不能只靠本地 mock/集成测试证明，必须保留外部联机验收：

1. 远端节点 A 已注册且健康，中心请求后直接 `ALLOW` 并走远端 relay
2. 远端节点 A 作为源 relay，先执行 `SELF_REPLICATE` 去拉起远端节点 B
3. 远端节点 B 启动后主动回中心注册，并通过心跳
4. 中心在确认“注册成功 + 健康检查通过”后，重新发放授权
5. 获得授权后，真实走远端 A2A 消息 / 任务调用
6. 当 A 自复制失败时，才进入中心侧 SSH 兜底

### 15.4 当前覆盖结论

- **已覆盖较强**：A2A 消息、A2A 任务、授权 token 能力子集校验、`SELF_REPLICATE -> SSH 兜底` 顺序、默认远端目录、制品包联机部署、远端注册/心跳/授权主路径
- **已覆盖但还不够细**：SSE 断流兜底、引用策略、PATH 受限环境的回归单测
- **尚未完整自动化**：多会话恢复、授权 TTL/续期/撤销配置矩阵、节点健康状态边界、远端节点 A 先向中心申请授权后再扩散节点 B 的真实 E2E

### 15.5 下一批最值得先补的 8 个用例

建议优先实现以下 8 个，因为它们最接近设计文档的硬约束：

- TC-19 默认 TTL 30 分钟
- TC-24 revoke 立即失效
- TC-28 nodeId 规则
- TC-29 注册后才可用
- TC-30 健康检查 + 注册双门禁
- TC-33 失联阈值默认 3 次
- TC-38 SSE 失败时状态查询兜底
- TC-50 部署失败后的回滚

## 16. 非开发者子 agent 阶段验收记录

### 16.1 验收方式

- 验收角色：非开发者视角子 agent。
- 输入方式：只给自然语言场景和验收目标，不给实现细节或手工修复步骤。
- 验收原则：优先通过已发布 Skill 文档与 `<CLI>` 操作；不允许把 SSH 登录远端手工修复当成通过依据。

### 16.2 阶段结论

| 等级 | 当前结论 | 说明 | 人工介入 |
|---|---|---|---|
| `L0_SKILL_FORMAT` | 阶段通过 | 已有 `validate_codex_skill.ps1` 与 `test_codex_skill_flow.ps1` 通过记录 | 否 |
| `L1_LOCAL_CONTROL` | 阶段通过 | 已有 `test_ccrelay_cli.py` `12/12 OK`，覆盖本地 CLI 闭环 | 否 |
| `L2_REMOTE_RELAY` | 部分验证 | 有远端部署、注册、心跳相关记录，但本轮未做非开发者独立复验 | 可能 |
| `L3_A2A_MOCK` | 部分验证 | 有模拟链路基础，但未完成自然语言用户视角独立复验 | 否 |
| `L4_REMOTE_AI_WEAK_REACT` | 未完全验证 | 本轮未完成真实远端 AI 会话闭环复验 | 需要有效模型配置与远端可达环境 |
| `L5_JAVA_ENFORCED_REACT` | 未验证 | 本轮未现场验证步数、白名单、超时、注入、调参、停止和审计 | 否，但需完整用例执行 |

### 16.3 稳定性结论

- 当前只能认定本地 Skill 格式、安装流与 CLI 回归稳定。
- 远端连续多轮协作、远端故障恢复、真实 AI 多 Agent 会话稳定性尚不能判定通过。
- 若后续任一场景需要手工 SSH 登录远端或手工改本机/远端文件才能继续，应直接判为稳定性未通过。

## 17. 集群发现与子 Agent 互通验证

### 17.1 设计原则

- 子 Agent 不应假设自己知道全量集群节点列表；节点视图应来自 `CC center` 的注册/心跳聚合。
- 普通用户只通过自然语言场景触发验证，不直接接触中心内部表或实现细节。
- 子 Agent 之间可以协同，但所有互通都必须落到可审计的会话、授权、任务与观测流程。

### 17.2 测试用例

| 用例ID | 用例名称 | 目标 | 执行方式 | 预期结果 |
|---|---|---|---|---|
| TC-55 | 集群视图获取 | 验证子 Agent 通过中心/`relay scan` 获取可用节点视图 | 自然语言场景 + `<CLI> relay scan` / `<CLI> relay nodes` | 能看到已注册且健康节点，节点来源明确来自中心视图 |
| TC-56 | 无环境自扩散 | 验证目标节点缺少环境或包时先走 `SELF_REPLICATE` | 自然语言场景 + `<CLI> task create --task-type DEPLOY_RELAY` | 先尝试自复制，成功后完成注册和健康检查 |
| TC-57 | 子 Agent 单点互通 | 验证节点 A 可通过同一会话向节点 B 下发工作请求 | 自然语言场景 + `<CLI> session open` + `<CLI> access request` + `<CLI> a2a message-send` | A/B 之间可在同一会话下完成可审计通信 |
| TC-58 | 子 Agent 多节点协作 | 验证 `fanout` 可驱动多个远端 Agent 并行协作 | 自然语言场景 + `<CLI> agent fanout` | 每个节点返回独立 `taskId`，结果可按节点观测 |
| TC-59 | 互通稳定性 | 验证集群视图、互通与自扩散场景可重复通过 | 重复执行 TC-55 ～ TC-58 | 无需人工 SSH 修复，结果稳定 |

### 17.3 验收判定

- `TC-55` 通过，才说明子 Agent 的节点视图来自中心而不是本地猜测。
- `TC-56` 通过，才说明目标节点缺环境/缺包时的自扩散路径可用。
- `TC-57`、`TC-58` 通过，才说明远端子 Agent 之间可以在中心编排下互通。
- `TC-59` 通过，才说明这些场景具备稳定性，不是偶发成功。

### 17.4 子 agent 提示词模板

#### TC-55 集群视图获取

> 你现在是一个普通使用者。请帮我看看当前可用的远端节点有哪些，并说明这些节点信息是从哪里来的。请只使用技能文档里允许的命令，不要查看实现代码，也不要手工去机器上找答案。最后给我一个可用节点清单和你的判断依据。

#### TC-56 无环境自扩散

> 我有一个远端节点当前还没有完整运行环境或技能包。请按正常用户方式帮我把这个节点恢复到可用状态，优先尝试自复制或自动部署，只有前面的方式不行才考虑兜底方案。请告诉我每一步做了什么、是否成功、最终这个节点是不是已经注册并健康。

#### TC-57 子 Agent 单点互通

> 我想让两个远端节点在同一个会话里完成一次协作。请你先把会话、授权和目标节点处理好，然后让节点 A 向节点 B 发起一次标准工作请求，并把双方的结果都告诉我。请不要绕过技能命令直接登录机器操作。

#### TC-58 子 Agent 多节点协作

> 我有一批远端节点需要同时处理同一个问题。请你用多节点协作方式把任务分发出去，分别收集每个节点的结果，再综合告诉我最终结论。请注意不要同步等待单个慢节点拖住整体流程。

#### TC-59 互通稳定性

> 请把刚才的集群视图、自扩散、单点互通和多节点协作流程再重复跑几轮。每一轮都要告诉我有没有出现需要人工修复、手工 SSH、手工改文件才能继续的情况；如果有，就直接说明这个流程不稳定。

### 17.5 Windows/Linux SSH 预检矩阵

| 用例ID | 场景 | 预期处理 |
|---|---|---|
| TC-60 | Windows 本机缺少 OpenSSH Client | 预检直接失败，提示启用 `ssh.exe` / `scp.exe` |
| TC-61 | Linux 本机缺少 `ssh` / `scp` | 预检直接失败，提示安装 openssh-client |
| TC-62 | 通用 SSH 凭据可用但节点级凭据缺失 | 先用通用凭据执行部署，若个别节点失败则提示补单节点覆盖 |
| TC-63 | 通用 SSH 凭据对单节点认证失败 | 不继续假设成功，明确提示用户提供 `ip:port` 覆盖凭据 |
| TC-64 | 远端缺少 `mkdir` / `tar` / `chmod` | 优先尝试绝对路径与 bundle 内置工具；仍失败则提示远端基础工具缺失 |
| TC-65 | 远端权限不足或无 sudo | 明确提示需要更高权限账号，不把失败伪装成部署成功 |
| TC-66 | 端口关闭或被防火墙拦截 | 明确提示 SSH 不可达，要求先修网络 / 安全组 / 端口 |
| TC-67 | host key 变化 | 提示确认指纹变更，不自动覆盖 known_hosts |

## 18. 2026-08-05 真实场景继续执行记录

### 18.1 当前可用性

- 中心健康：`http://47.93.195.246:29292` 返回 `UP`
- 稳定可用节点：`111.229.32.85:18091`、`47.93.195.246:18093`
- 当前可用节点：`47.93.195.246:18092` 已在 `2026-08-05` 复测为 `AVAILABLE`
- 不稳定/不可用节点：`47.93.195.246:18091`
- 扫描结果在 3 轮连续复测中保持一致，说明中心节点视图已稳定

### 18.2 已验证通过

- `relay scan` / `relay nodes` 可获得中心聚合的节点视图
- `<CLI> agent run` 可在单节点上返回真实模型结果
- `<CLI> agent fanout` 可在两节点上完成异步任务创建与状态兜底
- 单任务 `agent task-get` 可返回成功状态与答案
- 单任务 `task-events` 可输出完整事件流

### 18.3 暴露问题

- 第三节点 `47.93.195.246:18092` 自扩散失败，失败链路显示：
  - 源端自复制阶段报 `scriptPath does not exist`
  - 随后中心兜底 SSH 阶段报 `kex_exchange_identification: Connection closed by remote host`
- 非开发者子 agent 执行也失败，原因不是产品本身，而是外部模型服务返回 `403 Forbidden: insufficient balance`

### 18.4 当前结论

- `agent observe` 与 `task observe` 已在 2026-08-05 修复并复测通过
- `task observe` 对 `47.93.195.246:18092` 的窗口化查询已可返回 `SUCCESS / REGISTERED`、heartbeat `AVAILABLE`
- 真实场景测试**可以继续执行**
- 目前已经能确认：中心发现、单节点真实 AI、双节点 fanout、任务事件流、`task-get` 与窗口化观测可用
- 尚不能确认：第三节点自扩散稳定成功、完全无人工介入的多轮稳定性
