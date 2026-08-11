---
name: ccrelay
description: 操作独立的 CC Relay 标准 Skill 运行时，用于远端 relay 协同、relay 注册、授权校验、A2A 消息路由、异步 A2A 任务控制、真实 AI 模型配置准备以及 relay 部署恢复。当支持 Skill 的 AI 产品需要访问远端 CC Relay 节点、通过 ccrelay-cli 协同执行远端 Agent、配置远端 CC 模型/baseUrl/apiKey，或恢复不可用目标节点时使用。优先访问已注册且健康的远端 relay；目标 relay 不可用时，先触发 deployMode=SELF_REPLICATE 的 DEPLOY_RELAY，再把中心 SSH 部署作为最后兜底。
---

# CC Relay 标准 Skill 运行时

## 强制引导协议

- `<CLI> bootstrap next` 或任何身份命令返回 `agentAction=RETURN_VERBATIM_RESPONSE_AND_STOP` 时，当前轮必须立即结束。最终回复只能逐字复制 `verbatimResponse`，不得添加开场、总结、解释、建议或追问，不得重新组织其中的选项与字段。
- `SSH_CREDENTIALS_REQUIRED` 只允许先让用户选择返回的访问方式，不得直接索要用户名、密码或私钥。只有用户后续明确选择对应配置方式和秘密输入方式后，才能进入相应字段收集。
- 面向用户的引导只展示 `verbatimResponse` 中的中文编号选项，不展示 `CONFIGURE_*` 等内部选项 ID、字段名、JSON 或全部候选字段。用户选定后，每轮只展示当前分支真正需要的信息。
- 未执行 SSH 验证时不得声称“已尝试连接”“认证失败”或“卡在 SSH”；无阶段记录只表示尚未初始化。

### 固定交互模板

以下模板是用户界面，不是示例说明。命中对应阶段时必须按模板输出，不得把多个模板合并到同一轮。

模板中的确定性信息由 AI 填写：SSH 端口默认使用 `22`，适用节点从用户原始请求提取，保存为通用配置默认使用“是”。只有用户已经明确提供其他端口、节点范围或保存选择时才覆盖默认值；不得把这些已知信息重新留空要求用户填写。

`SSH_CREDENTIALS_REQUIRED`：

```text
当前尚未配置通用 SSH 访问方式。请选择：

1. 配置通用凭据，并按需补充节点独立凭据（推荐）
2. 使用已有的免密通用账号
3. 仅配置一套通用凭据并测试全部节点
4. 取消

请回复选项序号。选定后再填写对应信息。
```

用户选择“配置通用凭据”后，先收集非敏感信息：

```text
请填写：

SSH 用户名:
SSH 端口: 22
适用节点:
- <从用户原始请求提取的节点>
保存为通用配置: 是
```

随后单独选择密码输入方式：

```text
请选择 SSH 密码输入方式：

1. 安全终端弹窗（高风险环境 + 有桌面 GUI）
2. 安全输入命令（高风险环境 + 仅终端）
3. 明文输入（低风险环境 + 接受密码进入对话记录）
4. 取消

请回复选项序号。
```

只有用户选择明文输入后，才展示密码字段；用户名、端口、节点和保存选项沿用上一轮已经确认的值：

```text
请填写：

通用 SSH 密码:
```

用户选择“已有免密通用账号”后使用：

```text
请填写：

免密 SSH 用户名:
SSH 端口: 22
私钥路径（可选，留空则使用 SSH config、Agent 或默认密钥）:
适用节点:
- <从用户原始请求提取的节点>
保存为通用配置: 是
```

通用账号验证失败时：

```text
以下节点无法使用当前通用账号连接：

- <节点 1>
- <节点 2>

请选择：

1. 为失败节点配置独立账号
2. 更换通用账号
3. 使用当前配置重新验证
4. 取消

请回复选项序号。
```

模型 API key 使用相同的两步秘密输入模板：先填写模型和 Base URL，再让用户单独选择安全终端弹窗、安全输入命令、明文输入或取消。禁止在用户选择输入方式前展示 API key 字段。

模型 API 测试失败时：

```text
模型配置暂时不可用。

原因: <余额、鉴权、网络或服务响应原因>

1. API 已恢复，重试当前配置（推荐）
2. 更换模型、Base URL 或 API key
3. 取消配置

请回复选项序号。
```

账号模式选择：

```text
SSH 访问验证已通过。请选择 Relay 的运行账号：

1. 创建 Skill 专用账号（推荐）
2. 使用当前 SSH 账号
3. 取消

请回复选项序号。
```

专用账号确认：

```text
即将创建以下专用账号：

账号: ccrelay
部署目录规则: /home/ccrelay/ccrelay/<节点>-<Relay端口>
节点间免密: 开启
端口: 部署时自动选择

1. 确认
2. 修改账号或目录
3. 取消

请回复选项序号。
```

账号模式一旦选定，后续步骤不得再提供返回上一账号模式的结构化选项。用户主动用自然语言要求改变账号模式时，才重新进入账号模式选择。

用户选择“修改账号或目录”后才展示：

```text
请填写需要修改的内容，留空表示保持当前值：

专用账号: ccrelay
部署目录: 按节点和 Relay 端口自动生成
```

模型配置来源：

```text
请选择模型配置来源：

1. 使用检测到的本机模型配置
2. 填写新的模型配置
3. 取消

请回复选项序号。
```

用户选择“使用检测到的本机模型配置”后，只能展示实际读取到的字段，不得用产品默认模型或默认 Base URL 补齐并声称为检测结果。若本机配置只包含 API key 和 Base URL、没有模型，单独使用：

```text
检测到本机模型服务配置，但没有检测到模型名称。

请填写：

模型:
```

执行 `prepare-cc-config --discover` 只代表完成扫描，不代表用户已经选择使用扫描结果。该命令返回 `MODEL_CONFIG_SOURCE_REQUIRED` 时必须原样展示 `verbatimResponse` 并结束当前轮；即使检测结果只缺一个字段，也不得跳过来源选择直接询问该字段。只有用户明确选择“使用检测到的本机模型配置”后，才能按上面的缺失字段模板继续。

其他非敏感字段缺失时同样只询问缺失字段；API key 缺失时进入三档安全输入方式选择，不得直接展示 API key 字段。

新的模型配置：

```text
请填写：

模型:
Base URL:
```

模型配置完成后选择执行方式：

```text
环境准备信息已经确认。请选择后续执行方式：

1. 自动完成剩余部署（推荐）
2. 逐步检视
3. 取消

请回复选项序号。
```

检视模式的每个步骤都使用同一模板，只替换当前步骤名称和说明：

```text
当前步骤: <步骤名称>
说明: <本步骤将完成的事情>

1. 自动执行本步
2. 手动配置本步
3. 后续全部自动执行
4. 取消

请回复选项序号。
```

自动执行或单步执行期间使用只读进度模板：

```text
集群部署进度                         总进度 <百分比>  <运行状态>

节点                   状态      当前阶段          进度
<节点>                 <状态>    <阶段>            <百分比>

当前操作: <当前操作>
已传输: <已传输> / <总量>
已耗时: <耗时>
```

发生错误时只展示当前步骤和可操作选项：

```text
部署未能继续。

失败步骤: <步骤>
失败节点: <节点>
原因: <用户可理解的原因>

1. 重试当前步骤
2. 修改当前步骤配置
3. 查看错误详情
4. 取消

请回复选项序号。
```

免密阶段完成但部署尚未完成时：

```text
集群 SSH 免密已经配置完成，部署尚未完成。

1. 自动继续部署（推荐）
2. 逐步检视后续部署
3. 取消

请回复选项序号。
```

完整部署验收通过时：

```text
集群部署完成。

CC center: <中心节点>
Relay 节点: <可用数>/<总数> 可用
SSH 免密: 已验证
模型连接: 已验证
节点协作: 已验证

正在继续执行你最初提出的任务。
```

- 操作 relay 节点前，先阅读 `references/remote-first-runbook.md`。
- 运行时能力必须使用本 skill 目录内脚本：Windows 使用 `scripts/ccrelay-cli.ps1`，Linux 使用 `scripts/ccrelay-cli.sh`；脚本优先使用包内 Python 3.13，不要求宿主机预装 Python。`python scripts/ccrelay-cli.py` 仅供源码开发调试，不是发布运行方式。
- 收到包含目标 IP/SSH 端口的自然语言任务时，第一条命令必须是 `<CLI> bootstrap next --node <ip:port>...`。本地阶段库只记录三种里程碑：无记录为 `UNINITIALIZED`、免密验证完成为 `SSH_READY`、完整部署验收完成为 `DEPLOYED`；不得用文件探测、历史会话或 Center 状态推断并跳过阶段。
- `UNINITIALIZED` 必须进入 SSH 引导，不能先执行 `center resolve`、`ssh preflight`、Relay 探测或直接 SSH；`DEPLOYED` 才进入正常 Relay 协作。完整部署、注册、心跳和协同验证全部通过后执行 `<CLI> bootstrap mark-deployed`。
- 如果 Skill 内 `app.jar`、目标平台 JRE、内置 Python 或部署脚本缺失，必须立即返回 `SKILL_RUNTIME_INCOMPLETE` 并停止；不得搜索历史会话、历史命令、SSH 配置或本机私钥来恢复旧凭据，不得绕过 Skill 直接 SSH，也不得把历史测试中心或节点状态当作当前配置。只有安装完整 Skill 包后才能重新开始标准引导。
- 全局 `ccrelay-cli` 命令只是用户显式安装后的可选快捷别名，不作为标准 Skill 依赖；发布、验收和跨机器复制都不得依赖 skill 目录外的 shim。
- 需要可部署运行时包时，使用 `assets/runtime-bundle/ccrelay` 中的内置 relay 运行时。
- 远端 CC center 使用同一 runtime bundle 启动两个独立进程：CC center 控制面和 Relay sidecar；sidecar 只有在本机 health、中心注册和首次心跳都成功后才算可用。
- 该技能定位是远端多 Agent 协作控制面，不是某个业务域的专用诊断工具。
- 当前调用该 Skill 的 AI 产品是会话发起者和终端反馈者；CC center 从话题首批健康参与节点中随机选择一个远端 Relay 作为当前主 Agent，并把同一份职责提示词和权威会话元数据同步给所有参与节点。
- 每个 Relay 都在服务端统一注入同一份 `relay-system-prompt.txt` 职责提示词；该提示词说明本机职责、中心协作、A2A/ccrelay-cli 使用方式、ReAct 约束、审计和秘密保护。请求方的临时 system prompt 只能作为低优先级附加指令，不能覆盖 Relay 固定职责。
- Relay 提示词会填充 `nodeId`、`nodeHost`、`relayEndpoint`、`centerUrl`、`sessionId`、目标节点、能力列表、权限模式和本轮 ReAct 策略；缺失字段显示为 `unknown`，不得根据 nodeId 猜测网络地址。
- 发起 `ccrelay-cli` 不等于“直接让子 Agent 走 AI 对话”；它是在会话内向远端 Agent 下发标准工作请求，远端 Agent 代码解析请求后，只有确实需要 AI 时才调用远端 AI。
- 远端 Agent 目标默认按 `ReAct` 模式执行；当前远端异步任务已接入 Java 托管 Runner，结构化动作、步数、白名单、超时、注入、调参、停止和审计可按 `L5_JAVA_ENFORCED_REACT` 验收。
- 自然语言请求未提供结构化动作时会走 `ASK_AI` 模型兜底；这类请求只能说明模型链路可用，不能证明命令白名单等动作级约束被触发。
- 步数、命令白名单、单步超时、任务超时、是否允许 AI、审计级别都必须通过 `<CLI> agent` 参数显式可控。
- 远端任务运行中允许通过 `<CLI> agent inject` 注入提示词进行中断/引导，通过 `<CLI> agent adjust` 调整 ReAct 控制参数，通过 `<CLI> agent stop` 停止任务。
- 同一会话发往同一 Relay 的消息由 Center 持久化并按 FIFO 执行；收到 `QUEUED` 后不得重复下发，讨论与会话追问会在目标完成后自动恢复原发起 Agent。
- 多节点 fanout 默认使用“异步任务 + 逐节点状态兜底”，先一次性初始化参与节点和随机主 Agent，再向所有目标节点创建任务；不因分发循环而把讨论误判为严格串行。
- 首次引导在调用 `center bootstrap`、Relay 部署或部署恢复前先完成 CC 配置准备，避免部署到一半才发现缺少配置；已经完成配置后不在后续命令中重复增加模型 API 可用性门禁，运行时错误直接返回用户处理。
- 需要真实远端 AI 调用时，先完成 CC 配置准备；mock 响应只能证明 relay/A2A 链路，不能证明完整 AI 链路。
- 先执行 `<CLI> health`，确认本地技能运行时可访问；`<CLI>` 表示本 skill 目录内的 `scripts/ccrelay-cli.ps1` 或 `scripts/ccrelay-cli.sh`。
- 在申请授权或发送 A2A 流量前，通过 `<CLI> session open` 打开会话。
- 通过 `<CLI> access request` 申请 relay 访问授权，并在后续远端调用中使用返回的 `grantId`、`signedToken` 与 relay 上下文。
- 目标节点已经注册且心跳健康时，优先直连远端 relay。
- 常规远端协作优先使用 `<CLI> agent run`、`<CLI> agent fanout`、`<CLI> agent task-create` 与 `<CLI> agent task-events`。
- 低层 A2A 调试才使用 `<CLI> a2a message-send` 与 `<CLI> a2a task-create|get|events|cancel`。
- 目标 relay 不可用时，通过 `<CLI> task create` 创建 `DEPLOY_RELAY` 任务，并设置 `deployMode=SELF_REPLICATE` 与 `enableCenterFallback=true`，让运行时先尝试源端自复制，最后才走中心 SSH 兜底。部署属于已有用户会话时传入该真实 `sessionId`；独立引导部署没有会话时省略 `--session-id`，由 CLI 自动执行 `session open` 并使用 Center 返回值。禁止构造 `bootstrap-default` 或其他未由 Center 返回的会话 ID。
- `task create` 必须依据当前已解析中心自动向 `SELF_REPLICATE` payload 注入注册、心跳和授权校验上下文；主 AI 不手工拼写或向用户展示内部中心端点。
- 部署 CLI 必须使用目标可达主机生成 Relay 对外 endpoint，但不得把 SSH/IP 主机自动写入 `relay.host`；Relay 的 `nodeId` 保持“目标机器名 + Relay 端口”，部署任务通过目标端口与规范化工作目录关联真实注册节点并自动收口。
- `nodeId` 是 Relay 的逻辑身份，不是 SSH 主机名；CC center 注册表中的 `nodeId -> host + port + relayEndpoint + workspaceRoot` 映射是唯一可信来源。执行部署或 SSH 兜底前，必须通过 `<CLI> relay node <nodeId>` 或任务内部的中心查询读取真实 `host`；禁止从 `nodeId` 的机器名猜测 IP、DNS 或 SSH 地址，也不得把历史测试地址当作默认目标。
- 目录制品部署到已渲染的远端目录时，制品内容直接落在该目录，不得额外嵌套源目录名。
- 需要 SSH 兜底时，先做本机与远端可达性预检；通用 SSH 凭据默认只保留一份，节点级凭据按 `ip:port` 覆盖，缺少凭据时要主动询问用户，不要默认假设所有节点都能直接免密登录。
- 首次没有已保存的通用 SSH 凭据时，必须让用户输入用户名和密码并执行 `ssh config set-default` 保存；禁止把本机登录用户名、节点主机名、文档示例或历史验收账号当作默认 SSH 用户。只有 Skill `.local` 中已经保存且验证有效的凭据才允许自动复用。
- 首次凭据选项还必须提供“使用已有的免密通用账号”：用户填写用户名、SSH 端口和可选私钥路径后，执行 `<CLI> ssh config set-passwordless-default`；私钥为空时使用系统 SSH config、Agent 或默认密钥。该分支不要求密码，但仍必须逐节点只读验证，失败节点回到独立凭据补充流程。
- SSH 密码只能保存到本 skill 的 `.local/ssh-credentials.json`，Windows 使用当前用户 DPAPI 加密，Linux 使用当前用户 `0600` 权限；不得写入任务 payload、中心数据库、日志或最终回复。覆盖安装必须保留 `.local/`。
- 凭据输入必须按宿主能力选择：当前有 TTY 时使用 `getpass` 隐藏输入；宿主 GUI 能拉起终端时，向用户提供 Skill 内包装命令，让用户在新终端隐藏输入后回到当前会话继续；没有 GUI/TTY 时仍优先提供该终端命令。只有用户明确选择风险后，才允许使用“直接填写账号密码”的手工兜底，且密码只能通过进程级环境变量或临时文件传给 CLI，绝不能拼进命令行、日志或回复。
- SSH 引导账号与 relay 长期运行账号分离。首次配置严格按“凭据填写 → 全部节点只读验证 → 账号模式选择并立即执行 `ssh identity select` 保存 → 专用账号和目录确认 → apply”执行；凭据未验证完成前不得展示或接受账号模式配置。
- 凭据验证必须使用只读探测；`ssh test --bootstrap-key false` 即使配置允许后续免密，也不得写入 `authorized_keys`，成功时返回 `CREDENTIAL_VALID` 且不提示提前初始化免密。只有显式进入免密初始化或部署预检时才能安装公钥。
- 允许创建时，专用账号默认名为 `ccrelay`，密码由 Skill 随机生成并只保存受保护引用；共享集群密钥用于节点间免密，只有完整互信验证通过才允许直接自复制。
- 不允许创建时，不修改远端系统账号；至少验证 CC center 到全部节点免密，AI 不得假设节点间 SSH 互通。
- 身份策略和验证能力通过 `<CLI> ssh identity status|verify` 查询并落入中心 SQLite；主 AI 只依据 `FULL_MESH`、`CENTER_ONLY`、`DEGRADED`、`UNKNOWN` 做部署决策。
- 当 `<CLI> ssh identity|preflight` 或部署命令返回 `NEED_USER_INPUT` 时，立即暂停部署，原样展示返回的 `interaction.options` 与 `interaction.fields` 给用户选择和填写；不得跳过询问、不得手工 SSH 修复。`SSH_CREDENTIALS_INVALID` 必须先修复失败节点并重新验证，不能直接进入账号模式。
- `NEED_USER_INPUT` 是当前 Agent 轮次的强制终止状态：最终回复只能复制 CLI 返回的 `verbatimResponse`。内部选项 ID、字段名、JSON 和命令参数只供 AI 编排使用，不得展示给用户。涉及密码/API key 时，只有用户已明确选择明文方式后，才能展示秘密字段并接收明文；否则不得要求用户把秘密发到对话中。
- 用户只提供目标 IP/SSH 端口且中心没有注册节点时，首次 SSH 引导必须一次性执行 `<CLI> ssh identity plan --node <ip:port>...`，由该命令返回集群级凭据、节点覆盖和账号模式交互；不得先逐节点调用 `ssh preflight` 来替代首次引导，也不得先尝试直接按 IP 调用 Relay。
- 当 `<CLI> ssh config set-default|set-node` 返回 `SSH_PASSWORD_INPUT_REQUIRED` 时，必须原样展示 `interaction.options`、`securityWarning` 和 `interaction.fields`，让用户在以下三类方式中明确选择：安全终端弹窗（高风险 + 有桌面 GUI）、安全输入命令（高风险 + 无桌面 GUI）或明文输入（低风险 + 无桌面 GUI）。不得因为某项标记为推荐、宿主有无 GUI、用户选择了自动执行或 AI 判断而代替用户选择；用户未明确选择前不得拉起终端、读取密码或写入凭据。用户选择安全命令后，指导其在 Windows PowerShell 或 Linux shell 中隐藏输入密码，执行成功后回到当前会话重试原命令；不要要求用户把密码回传到对话。若用户选择 `MANUAL_VISIBLE_INPUT`，必须先展示风险警告，再一次性提供用户名、密码、端口清单，并使用 `--password-env` 或 `--password-file` 的进程级安全传递方式。
- 用户配置凭据后，执行 `<CLI> ssh prepare-center --host <ip> --port <port> --username <user>`：获取当前 CC center 公钥，用本地凭据写入目标节点，再由实际 CC center 验证免密连接。中心在远端时也必须使用远端中心自己的公钥。
- 自复制失败且任务进入 `WAITING_USER_INPUT/SSH_CREDENTIAL_REQUIRED` 后，完成 `ssh prepare-center`，再执行 `<CLI> deploy resume <taskId> --host <ip> --port <port> --username <user>` 恢复原任务；不要重新创建任务冒充恢复成功。
- 本地 CC 对话默认视为关闭；真实交互执行交给远端 relay。
- 健康远端中心需要滚动更新当前 Skill 制品时，只能使用 `<CLI> center bootstrap --force-redeploy` 原位更新并保留 SQLite；该模式严格限定当前中心节点，不参与其他受管节点的资源重选，不得手工 SSH 替换 JAR。
- 部署进入长耗时阶段后，不得只等待终态；使用 `<CLI> task observe <taskId>` 或父任务批量观测读取各目标节点的阶段、百分比、已传输/总字节、滚动速度、预计剩余时间、耗时和最近更新时间，并向用户呈现紧凑的只读监控视图。
- 监控视图只回答是否推进和速度是否正常，不展示暂停、取消或参数调整控件。超过返回的停滞阈值无进度更新时明确显示 `STALLED`，不能继续描述为普通运行中。
- 远端 relay 需要重新校验授权 token 时，使用授权响应中的中心校验上下文，不在 Markdown 中拼写内部入口。
- 部署后重新执行 `<CLI> relay scan`，只有心跳健康且节点已注册后才继续。
- 本地中心无法被目标节点访问时，优先使用 `ccrelay-cli center plan|bootstrap --node ...` 从受管远端节点中自动选择资源更充足的节点并自动探测中心端口；中心 bootstrap 会在同一目录启动独立 Relay sidecar、自动探测第二个端口并等待注册；不得先要求用户填写 `centerRegisterEndpoint`。
- 只有用户主动选择“手动指定中心”时，才询问中心协议、主机、端口和基础路径。relay 的注册、心跳和授权校验端点由 CLI 根据已选中心自动生成，不向普通用户暴露内部 API 路径。
- 完成 SSH 凭据、节点级覆盖、账号模式和专用账号详情确认后，必须先让用户完成模型配置选择与连通性测试，再让用户选择“自动执行”或“检视”。即使用户此前已选择自动执行，模型配置未完成时也只能暂停，不能自动拉起 API key 弹窗或代替用户选择输入方式。模型配置成功后由用户重新确认执行模式；自动执行时由 CLI 依次完成资源探测、中心选择、端口探测、运行时分发、中心启动、状态同步、relay 部署和验收；确定性代码无法判断且不触及安全边界时，才由当前 AI 根据结构化上下文决策。
- 用户选择账号模式后必须先调用 `ssh identity select` 保存到当前 Skill 配置；后续 `plan|apply|verify|deploy` 只读取该配置，不得再次推断或覆盖账号模式。执行模式是远端变更的强制门禁，但模型配置选择必须排在执行模式之前：用户确认专用账号详情后，先执行 `prepare-cc-config --discover`，让用户选择复用本机配置或填写新配置，再让用户选择 API key 的安全终端/安全命令/明文兜底方式，完成测试并写入配置；配置未完成时不得展示或接受 `AUTO_EXECUTE_REMAINING`。模型配置就绪后重新执行 `ssh identity plan|apply`，此时才返回 `BOOTSTRAP_EXECUTION_MODE_REQUIRED`；未明确传入 `--execution-mode AUTO_EXECUTE_REMAINING|INSPECT_STEP_BY_STEP` 时，不得创建账号、安装密钥或修改远端。选择执行模式后必须先做资源探测和中心选择，再把选中的中心传给 `ssh identity apply`；禁止在资源探测前临时指定第一台节点为中心。
- `ssh identity plan` 的凭据验证、账号模式选择和专用账号详情预览阶段只需要 `--node` 清单，不得要求或推断 `--center-node`；`apply/verify/rotate-key` 才强制使用资源探测后选出的中心节点。
- 身份初始化早于中心进程启动，因此本地身份状态允许暂存为 `CENTER_IDENTITY_SYNC_DEFERRED`；这不是失败。中心启动后必须执行 `ssh identity verify` 写入中心 SQLite，只有同步和验证成功后才允许部署 Relay。
- 检视模式每次只展示当前步骤，并按步骤动态提供“自动执行本步”“手动配置本步”“后续全部自动执行”以及适用的重试、跳过、取消选项。只有选择手动配置本步时，才展示该步骤字段。

## 最小流程

1. 执行 `<CLI> center resolve`；默认本地中心不可用时由 CLI 自动启动 Skill 内非 AI center，再检查运行时健康状态。
2. 首次使用时执行 CC 配置准备流程，再进入远端 `center bootstrap` 或 Relay 部署；已有配置时直接沿用。
3. 对单节点工作请求，优先执行 `<CLI> agent run --target-node-id <nodeId> --prompt "<work-order>"`。
4. 对独立多节点工作，执行 `<CLI> agent fanout --collaboration-mode INDEPENDENT_FANOUT --target-node-ids <nodeA,nodeB> --prompt "<work-order>"`；对共同讨论，执行 `<CLI> agent fanout --collaboration-mode DISCUSSION --target-node-ids <nodeA,nodeB> --prompt "<shared-goal>"`。CC center 在首批参与节点中随机选择远端主 Agent。
5. 对耗时任务，使用 `<CLI> agent task-create` 创建远端任务，再用 `<CLI> agent task-events <taskId>` 收集事件；运行中可用 `agent inject`、`agent adjust`、`agent stop` 控制。
6. 如果目标 relay 不可达，创建 `DEPLOY_RELAY`，优先 `SELF_REPLICATE`，并设置 `enableCenterFallback=true`。
7. 若任务进入 `SSH_CREDENTIAL_REQUIRED`，按结构化选项询问用户，保存凭据并重新执行只读验证；全部节点通过后再执行 `ssh prepare-center` 与 `deploy resume`。
8. 等待目标节点完成注册并通过心跳检查。
9. 节点恢复后重新执行 `<CLI> agent` 工作请求。

## 终端协作会话

- 用户明确要求“分别检查、分别回答、独立取证”时选择 `INDEPENDENT_FANOUT`；明确要求“共同讨论、协同解决、互相审阅、形成共同结论”时选择 `DISCUSSION`；复用既有 `sessionId` 追加问题时选择 `SESSION_FOLLOW_UP`；单节点请求使用 `DIRECT`。用户表意不清且不同模式会改变结果时，先用终端文本选项询问，不由 Relay 自行决定。
- 首次话题必须把全部首批参与节点一次性传给 `agent fanout`。主 Agent 由 CC center 随机选择并持久化；调用 Skill 的 AI、终端用户和 Relay 都不得在提示词中自行指定或改写主 Agent。
- 使用 `<CLI> session get <sessionId>` 获取协作模式、主 Agent、epoch 和参与节点；使用 `<CLI> session messages <sessionId> --after-cursor <cursor>` 增量读取共享上下文。终端反馈至少显示会话、主 Agent、参与节点、节点间 `sender -> @target` 路由和任务状态。
- 用户继续对话或 `@` 新节点时，复用原 `sessionId` 执行 `<CLI> agent run --collaboration-mode SESSION_FOLLOW_UP --session-id <sessionId> --target-node-id <nodeId> --prompt "<message>"`。新节点由中心加入参与列表并先同步共享上下文增量。
- `DISCUSSION` 的完成标准不是“所有节点各自回复一次”，而是共享上下文中存在真实跨节点消息、被询问节点作出针对性回应、主 Agent 吸收关键分歧并形成共同结论。没有这些证据时必须反馈“当前只是独立回复，尚未形成讨论”。
- 终端展示以共享上下文为会话正文；任务事件仅作为对应消息下的执行轨迹和状态，不重复输出同一 Agent 的最终回复。不得根据模型自述推断 CLI 已执行，必须以真实任务和上下文事件为准。

## CC 配置准备流程

1. 先运行模型配置包装脚本的 `--discover`：Windows 使用 `scripts/prepare-cc-config.ps1`，Linux 使用 `scripts/prepare-cc-config.sh`，检查当前进程的 `ANTHROPIC_*`/`OPENAI_*`、`CLAUDE_CONFIG_DIR/settings.json`、`~/.claude/settings.json` 和本机模型配置文件。Claude Code 生效配置中的 `ANTHROPIC_AUTH_TOKEN` 必须作为 API 凭据识别，因此兼容由 `cc switch` 等工具写入的当前配置，但不得读取或依赖这些工具的私有数据库；缺失字段必须保持缺失，禁止用 `DEFAULT_MODEL`、默认 Base URL 或供应商示例值伪装成检测结果。扫描完成后必须先处理脚本返回的 `MODEL_CONFIG_SOURCE_REQUIRED` 来源选择，不得把发现候选配置视为用户已经选择复用。
2. 如果发现本机配置，必须询问用户：“检测到本机 AI 配置，使用本机配置还是用户输入？”
3. 如果用户选择“使用本机配置”，用发现到的 `model`、`baseUrl`、`apiKey` 自动生成 Skill 本地受保护配置区 `.local/cc-model-config.yml`；不能写入公共制品目录。
4. 如果用户选择“用户输入”，先收集非敏感的 `model` 和 `baseUrl`，再运行包装脚本的 `--input-options`，必须把三档 API key 输入方式全部展示给用户：`LAUNCH_SECURE_TERMINAL`（高风险 + 有桌面 GUI）、`SECURE_TERMINAL_COMMAND`（高风险 + 无桌面 GUI/仅终端）和 `MANUAL_VISIBLE_INPUT`（低风险 + 无桌面 GUI或用户明确接受风险）。选项后的场景说明不构成默认选择；只有用户明确选择某个选项后，才能执行对应动作。
5. 安全终端使用 `--prompt-api-key` 隐藏输入并在同一进程内完成测试与写入；明文兜底必须在用户明确接受风险后使用 `--api-key-env` 或临时 `--api-key-file` 传递，不得把 key 拼入命令行。用户已经明文提供时不得拒绝继续，但必须提示其会进入对话记录并建议后续轮换。
6. 生成配置前，使用同一模型配置包装脚本的 `--test` 对用户提供或本机发现的 Anthropic 兼容服务做一次简单连通测试。
7. 如果测试不可用，返回 `NEED_USER_INPUT/MODEL_CONFIG_TEST_UNAVAILABLE` 并明确失败类型，例如余额不足、`baseUrl` 为空、`model` 无效、鉴权失败、网络不可达或服务返回非 2xx；这不预判一定是配置错误，同时提供“API 已恢复后重试当前配置”“更换模型配置”“取消配置”三个选项。测试未通过不得写入配置。
8. 只有测试通过后，部署流程才生成目标级受保护配置包，并在 Relay 启动前通过受保护通道单独下发；真实配置不进入公共 runtime artifact。模型配置发现只允许在调用本 Skill 的本机执行，这一份本机配置作为集群统一配置复用到全部远端；远端 Center、Relay 和安装脚本不得扫描远端环境变量、Claude Code 配置或第三方配置工具数据，缺少本机下发配置时必须失败。
9. 不要在最终回复里明文展示完整 `apiKey`；不要把真实 `cc-model-config.yml` 提交到 Git。

## 必须遵守的策略

- 已注册且健康的远端 relay 永远优先。
- 节点缺失或不可用时，首选部署模式是 `SELF_REPLICATE`。
- 中心 SSH 部署只能作为最后兜底，不是默认第一跳。
- relay 只有在注册成功且健康检查通过后才可用。
- 真实 AI 验收必须返回模型真实响应；返回 `REMOTE_MOCK_RESPONSE` 只能算 mock 验收。
- 验收结论必须标注等级：`L0_SKILL_FORMAT`、`L1_LOCAL_CONTROL`、`L2_REMOTE_RELAY`、`L3_A2A_MOCK`、`L4_REMOTE_AI_WEAK_REACT` 或 `L5_JAVA_ENFORCED_REACT`。

## 内置运行时

- 使用 `assets/runtime-bundle/ccrelay/app.jar` 作为 relay 应用 jar。
- 使用 `assets/runtime-bundle/ccrelay/runtime.tar.gz` 作为内置 Linux JRE；本机首次启动时解压到 Skill `.local/runtime/jre-linux-21`。
- 使用 `assets/runtime-bundle/ccrelay/runtime-windows.zip` 作为内置 Windows JRE 21；本机首次启动时解压到 Skill `.local/runtime/jre-windows-21`。
- 发布包不重复携带已解压 JRE；远端 Center/Relay 仍按目标系统传输并解压对应归档。
- 使用 `assets/runtime-bundle/ccrelay/python-windows/` 或 `python-linux/` 作为本机 CLI 的内置 Python 3.13；该运行时仅包含标准库，不安装 pip 或第三方包。
- 在 Linux 目标节点上用 `assets/runtime-bundle/ccrelay/bin/start.sh` 启动 relay。
- 向远端节点分发 relay 制品包时，Linux 使用 `install-relay.sh`，Windows 使用 `install-relay.ps1`；中心 sidecar 使用独立 PID、日志和节点 ID 文件。
- CC 配置模板位于 `assets/config-templates/cc-model-config.template.yml`；真实配置由部署流程作为目标级受保护配置包单独下发。
- `health=UP` 只代表 Relay 进程健康；只有模型配置就绪并通过 AI readiness canary，才能继续真实 AI 对话。

## ccrelay-cli

- 始终从本 skill 目录调用 CLI：Windows 运行 `powershell -ExecutionPolicy Bypass -File scripts/ccrelay-cli.ps1 --help`，Linux/macOS 运行 `bash scripts/ccrelay-cli.sh --help`。
- 如果当前 shell 不方便运行包装脚本，应先使用对应平台的 Skill 包装脚本；不要要求用户安装或切换外部 Python。
- 不要求 `PATH` 包含任何外部目录，也不要求 `CODEX_HOME/bin` 存在。
- 默认中心地址读取 `CCRELAY_CENTER_URL`，未设置时使用 `http://127.0.0.1:18191`；发行代码不得携带固定公网测试中心。
- `center resolve` 在本地默认模式下自动确保非 AI center 已启动；显式生命周期管理使用 `center ensure|status|stop`，不得绕过 CLI 直接运行 JAR。
- 需要清理远端运行资源并保留账号、SSH 凭据和集群互信时，只能使用 `center remote-cleanup`；不得直接登录节点删除。
- 正常协同优先使用 `center`、`health`、`relay`、`agent` 命令组；`agent` 会自动编排会话、授权与上下文。
- `session`、`access`、`task`、`a2a` 是低层控制命令，只有排查或高级编排时再直接使用。
- `ssh config show|set-default|set-passwordless-default|set-node|remove-default|remove-node` 管理 skill 本地访问配置；已有免密账号使用 `set-passwordless-default --username <user> --port <port> [--private-key-file <path>]`，不保存密码。密码凭据默认在交互终端安全输入。非 TTY 时 CLI 返回结构化 `SSH_PASSWORD_INPUT_REQUIRED`，应优先执行 `interaction.options[].command` 指向的 Skill 包装命令；仅在用户明确接受风险后，才通过临时 `--password-file` 或 `--password-env` 输入，禁止把密码直接写进命令行。
- `ssh tools|test|center-key|prepare-center|preflight` 完成本机工具检查、凭据测试、中心公钥初始化和中心侧免密验证。
- `ssh identity plan|apply|status|verify|rotate-key` 管理 Skill 专用账号、中心管理密钥、集群互信和脱敏能力摘要。
- `remote` 命令组只能在明确诊断或恢复远端 relay 时使用，不作为首选协同路径。
- 关闭会话使用 `session close <sessionId>`；关闭后该会话下仍可用的授权会失效。
