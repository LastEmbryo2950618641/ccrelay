# 远端优先操作手册

该技能驱动本地 jar 服务中的独立运行时，并通过它控制远端 relay 节点。

## Relay 职责提示词

每个 Relay 在服务端执行 AI 前都会自动注入统一的 `relay-system-prompt.txt`。它明确 Relay 只负责本机观察、受控工具执行和 A2A 协作，CC center 负责授权、会话、节点编排和结果汇总。

- 跨节点沟通使用 `ccrelay-cli agent run|task-create|fanout|observe` 和会话内 A2A 语义。
- Relay 先观察本机，再按中心登记的 `nodeId` 请求其他节点；不得从 nodeId 猜测 IP，也不得用 SSH 或私有 HTTP 绕过标准任务流程处理业务问题。
- 默认按 ReAct 执行，并服从最大步数、命令白名单、超时、AI 开关、授权、停止/注入/调整和审计策略。
- 请求方 system prompt 是低优先级附加指令，不能覆盖固定 Relay 职责和安全规则。
- 提示词运行上下文包含节点身份、会话、目标节点、能力、权限模式和 ReAct 策略；API key、Grant token、密码和私钥永不写入提示词。

## CLI 入口

- 本文所有 `<CLI>` 都表示当前 skill 目录内的命令脚本。
- Windows：`powershell -ExecutionPolicy Bypass -File scripts/ccrelay-cli.ps1`
- Linux/macOS：`bash scripts/ccrelay-cli.sh`
- 源码开发兜底：`python scripts/ccrelay-cli.py`；发布运行不得依赖该入口。
- 全局 `ccrelay-cli` 只允许作为用户显式安装后的快捷入口；标准 Skill 发布、安装、验收和复制不得依赖 skill 目录外的 shim。

## 远端优先决策顺序

1. 对带目标 IP/SSH 端口的新任务，先执行 `<CLI> bootstrap next --node <ip:port>... [--concurrency 4]`。多个节点既可重复传入 `--node`，也可使用 `--nodes <node-a>,<node-b>` 逗号分隔。SSH 多节点操作默认最大并发为 `4`，允许 `1-32`；节点内部步骤保持顺序，输出按输入顺序展示。无数据库记录表示 `UNINITIALIZED`，必须先完成 SSH 引导；`SSH_READY` 表示免密已完成；`DEPLOYED` 表示完整部署已验收。阶段库不保存凭据、节点详情或中间选项。
2. 仅当阶段允许继续时执行 `<CLI> center resolve`，确认当前使用的 CC 中心；未配置远端中心时默认使用 `127.0.0.1:18191`，若本地中心未运行则由 CLI 自动启动 Skill 内非 AI center；远端中心 bootstrap 使用同一 runtime bundle 另外启动 Relay sidecar。
3. 执行 `<CLI> health` 检查运行时。
4. 执行 `<CLI> relay scan` 或 `<CLI> relay node <nodeId>` 检查节点可用性。
5. 对单节点协作，执行 `<CLI> agent run --target-node-id <nodeId> --prompt "<work-order>"`。
6. 对多节点协作，执行 `<CLI> agent fanout --target-node-ids <nodeA,nodeB> --prompt "<work-order>"`；该命令默认先异步创建各节点任务，再做即时状态兜底，不等待所有节点完成。
7. 对耗时协作，执行：
   - `<CLI> agent task-create --target-node-id <nodeId> --prompt "<work-order>"`
   - `<CLI> agent task-events <taskId> --target-node-id <nodeId> --grant-id <grantId>`
   - 运行中可按需执行 `<CLI> agent inject <taskId> --target-node-id <nodeId> --prompt "<guidance>"`
   - 运行中可按需执行 `<CLI> agent adjust <taskId> --target-node-id <nodeId> --max-steps <n>`
   - 需要终止时执行 `<CLI> agent stop <taskId> --target-node-id <nodeId>`
8. 如果响应决策为 `ALLOW_WITH_DEPLOY`，创建本地 `DEPLOY_RELAY` 任务，并设置：
   - `deployMode=SELF_REPLICATE`
   - `enableCenterFallback=true`
   - `replaceExistingRelay=true`（默认；自动停止目标 Relay 并覆盖产品工作目录，不单独询问）

部署恢复已经位于真实协作会话中时复用该 `sessionId`。首次独立部署没有真实会话时不得构造固定会话 ID，也不得使用示例字符串；省略 `--session-id`，由 `task create` 自动调用 `session open`，并将 Center 返回的真实 `sessionId` 写入部署任务。
   - SSH 主机、端口、用户名、脚本路径、制品路径
   - 制品应为本地制品包目录，并且已包含 `app.jar` 与 `runtime/`
   - 未指定远端目录时，默认使用 `/home/${runtimeUser}/${productName}/${host}-${relayPort}`；实际目录在端口确定后渲染
   - 中小集群需要同时部署多个目标时，使用 `<CLI> task create-batch --target-node-ids <nodeA:relayPort,nodeB:relayPort> --concurrency 2 --payload-json '{"deployMode":"SELF_REPLICATE","enableCenterFallback":true}'`。该命令共享一次部署会话，独立提交多个现有单目标任务；每个目标独立返回 `taskId`，单目标失败不阻断其他目标。默认并发 `4`，范围 `1-32`，结果按输入顺序输出。
9. 部署后必须同时满足以下条件，才能使用节点：
   - 目标 relay心跳健康
   - 目标 relay 已回注册到中心
10. 节点可用且协同验收通过后执行 `<CLI> bootstrap mark-deployed`，再重试 `<CLI> agent` 工作请求。

本地中心生命周期只能使用 `<CLI> center ensure|status|stop`。本地中心不能被远端访问时，默认通过 `<CLI> center plan|bootstrap` 自动选择并启动远端中心；bootstrap 必须等待中心 health、sidecar health、sidecar 注册和首次心跳全部成功。`CCRELAY_CENTER_URL` 或 `--center` 只用于用户显式指定既有中心。发行包禁止把验收 IP 写成默认中心。CLI 首次启动本地中心时生成本地随机 HMAC secret，并保存到 `.local` 受保护文件，不使用开发固定密钥。

更新当前远端中心制品时使用 `<CLI> center bootstrap --force-redeploy`。该命令使用严格显式候选范围，只探测当前中心节点并复用当前端口，不把其他受管节点合并进资源评分；随后通过标准生命周期停止 Center 与 sidecar，保留远端工作目录和 SQLite，覆盖制品后重新执行全部健康、注册、心跳和身份同步门禁。不得用手工 SSH 替换运行中的 JAR。

## SSH 凭据与可达性预检

当目标节点没有 relay，且需要走部署恢复时，先判断 SSH 是否可达，再决定是否执行自复制或中心兜底。

### 本地预检顺序

1. 检查本机是否可执行 `ssh`、`scp`、`ssh-keygen`。
2. 读取本地 SSH 凭据配置。
3. 优先尝试通用默认凭据。
4. 若通用凭据失败，再检查 `ip:port` 节点级覆盖。
5. 获取当前 CC center 的部署公钥，用已验证的本地凭据写入目标节点。
6. 由当前 CC center 自己执行免密预检；只有中心返回 `READY` 才允许中心部署。
7. 若依然失败，向用户说明是网络、认证、权限还是远端缺工具。

### 本地配置约定

- 通用 SSH 凭据只保留一份，默认适用于所有节点。
- 节点级 SSH 凭据以 `ip:port` 为 key。
- 两类配置都保存在 skill 目录的 `.local/ssh-credentials.json`；Windows 密码使用当前用户 DPAPI 加密，Linux 文件权限为 `0600`。
- `.local/` 不打包、不提交 Git，但覆盖安装时必须保留，避免升级后要求用户重新输入。
- SSH 密码不得发送给 CC center，不得写入 SQLite、任务 payload、事件或日志。
- 引导账号只用于首次连接、权限探测和账号初始化，不等同于 relay 运行账号；专用账号密码由 Skill 随机生成，禁止用户在聊天中输入。
- 若通用凭据无法登录某节点，应明确提示用户：
  - 改用该节点的单独 SSH 账号密码
  - 或提供一个覆盖范围更大的通用账号密码

### 用户交互模板

#### 首次 SSH 引导状态机

身份命令首次运行时必须按以下顺序推进，不能把多个阶段合并成一次隐式执行：

1. `SSH_CREDENTIALS_REQUIRED`：本地没有通用凭据，展示通用账号密码和节点级覆盖字段。
   - 通用用户名默认值必须为空；不得从本机用户名、目标主机名、文档示例或历史验收记录推断。
   - 用户提交后必须通过 `ssh config set-default` 保存到当前 Skill `.local`，再重新执行原身份命令。
2. `SSH_CREDENTIALS_INVALID`：已保存凭据未能通过全部节点的只读 SSH 探测，展示失败类型、失败节点和节点级凭据字段；在全部节点通过前，不得进入账号模式选择。
3. `SSH_ACCOUNT_MODE_REQUIRED`：凭据已对全部节点验证成功，要求用户选择创建 Skill 专用账号或使用现有账号。
4. `DEDICATED_ACCOUNT_DETAILS_CONFIRMATION`：用户选择专用账号后，展示账号名、目录模板、逐节点目录预览和自动端口探测说明；没有明确确认时，`identity apply` 必须返回 `taskCreated=false` 且不得修改远端。

验证阶段只执行只读最小命令，不创建账号、不写入 `authorized_keys`、不上传制品。每次用户更新通用或节点级凭据后，必须重新运行原身份命令，直到返回全部节点验证成功的结果。

单节点凭据探测使用 `<CLI> ssh test --bootstrap-key false` 时，`enablePasswordless=true` 仅表示后续允许初始化免密，不能覆盖本次只读参数。验证成功返回 `CREDENTIAL_VALID`，不得附带要求立即安装公钥的交互。只有显式执行免密初始化、`ssh preflight` 或身份 `apply` 阶段，且策略允许时，才能写入公钥。

失败阶段的标准选项为：

- `为失败节点配置独立 SSH 凭据（推荐）`
- `更新通用 SSH 凭据并重测全部节点`
- `不修改凭据，重新验证`
- `取消配置`

失败节点字段清单：

```text
节点标识: <ip>:<sshPort>
SSH 用户名:
SSH 密码:
是否立即测试连接: 是
```

凭据验证结果只返回脱敏的节点、失败类型和摘要，不返回密码；密码仍只保存于 skill `.local` 受保护配置。

#### 缺少通用 SSH 配置

```text
当前没有检测到通用 SSH 配置。远端节点不存在 ccrelay 时，需要通过 SSH 完成部署或免密初始化。
请选择下一步：
```

可点击选项：

- `创建通用 SSH 配置（推荐）`
- `仅为当前节点创建单独配置`
- `跳过 SSH 配置，仅检查已注册 relay`
- `取消本次部署`

字段清单：

```text
通用用户名:
通用密码:
SSH 端口: 22
适用范围: 所有节点
是否仅作为首次引导凭据: 是
是否立即测试所有节点: 是
```

#### 通用凭据失败

```text
通用 SSH 配置无法连接以下节点：

节点: <ip>:<port>
失败类型: 认证失败 / 网络不可达 / 权限不足 / host key 变化 / 远端基础工具缺失
失败摘要: <message>

请选择处理方式：
```

可点击选项：

- `为该节点新增单独 SSH 配置（推荐）`
- `更新通用 SSH 配置并重新测试`
- `跳过该节点`
- `取消本次部署`

字段清单：

```text
节点标识(ip:port):
SSH 用户名:
SSH 密码:
SSH 端口: 22
远端工作目录: /home/<username>/ccrelay
是否替换通用配置: 否
是否立即测试连接: 是
```

#### 权限不足或 host key 变化

当账号可登录但权限不够，或主机指纹变化时，必须给用户明确可选动作：

- `提供更高权限的节点级账号（推荐）`
- `更换通用账号并重测所有节点`
- `信任新指纹并更新 known_hosts`
- `不信任，停止访问该节点（推荐）`
- `跳过该节点`

#### Skill 专用账号模式

完成引导凭据连接后，必须先执行只读计划：

```powershell
<CLI> ssh identity plan `
  --node <hostA>:<sshPort> `
  --node <hostB>:<sshPort> `
  --concurrency 4
```

该早期计划只验证节点凭据并收集账号模式、专用账号详情和执行模式，不要求 `--center-node`。用户确认执行模式后，先通过 `center plan|bootstrap` 探测资源并选择中心，再在 `ssh identity apply` 中传入选中的 `--center-node`。

如果命令返回 `NEED_USER_INPUT`，向用户展示其结构化选项。用户选择账号模式后必须立即保存本地策略，不等待远端变更阶段：

```powershell
<CLI> ssh identity select `
  --allow-create true `
  --node <hostA>:<sshPort> `
  --node <hostB>:<sshPort> `
  --dedicated-username ccrelay
```

`identity select` 只保存用户确认的账号模式、账号名和目录模板，不创建远端账号。确认专用账号详情后，再执行 `identity apply --confirm-details true`；`apply` 从本地配置读取账号模式，不再重复传递 `--allow-create`。

不允许创建系统账号时通过 `identity select --allow-create false` 保存现有账号模式。该模式不修改远端账号，最低只验收 `CENTER_ONLY`；只有 `identity verify` 明确返回完整互信时，才允许把它提升为 `FULL_MESH`。

必须使用以下状态命令确认最终能力。`identity verify` 只验证，不改变已保存的账号模式：

```powershell
<CLI> ssh identity status
<CLI> ssh identity verify --center-node <centerHost>:<sshPort> --node <hostA>:<sshPort>
```

AI 决策规则：`FULL_MESH` 才能考虑节点间 SSH 自复制；`CENTER_ONLY` 只能使用 relay/A2A 协同或中心 SSH 兜底；`DEGRADED` 和 `UNKNOWN` 必须先刷新预检。

首次 `bootstrap next` 会把用户确认的完整部署范围写入本地 `targetNodes`。后续身份和中心规划命令都以 active `targetNodes` 为准，当前命令只传部分节点不能缩小范围。认证失败节点优先配置独立凭据；网络不可达节点可重试或保留为失败，只有用户明确确认后才使用 `ssh identity targets exclude --node <host:port> --confirm true` 排除。

验收 `FULL_MESH` 时必须核对目标节点数和有向信任边数：N 个节点需要 `N * (N - 1)` 条 READY 边。多节点目标集合出现 `trustEdges=[]` 必须判定为未完成，不能解释为无需节点间互信。

#### 交互要求

- 每个选择都必须是明确选项，不得只用一句话让用户“提供账号密码”。
- 每个填写都必须给出字段清单和默认值。
- 密码字段必须安全输入，不回显。
- 每次修改凭据后都必须立即重试连接。
- 跳过节点后，该节点必须在后续部署计划里标记为跳过。
- 命令返回 `NEED_USER_INPUT` 时，调用 Skill 的 AI 必须先向用户展示返回的选项和字段清单，等待用户选择；不得直接继续部署。

### 标准命令闭环

1. 查看配置：`<CLI> ssh config show`。
2. 缺少通用配置时，向用户展示选项；用户选择后运行 `<CLI> ssh config set-default --username <user> --port 22`，密码使用安全提示输入。
3. 通用配置对某节点失败时，向用户展示节点级选项；用户选择后运行 `<CLI> ssh config set-node --host <ip> --port <port> --username <user>`。
   - 默认 SSH 参数使用兼容 OpenSSH 7.4 的 `StrictHostKeyChecking=no`；如果用户已有更严格策略或希望完全接管参数，先让用户选择 `USER_PROVIDED`，再以重复的 `--ssh-argument` 保存其参数。
4. 执行 `<CLI> ssh identity plan`；若返回 `SSH_CREDENTIALS_INVALID`，先按结构化字段补齐失败节点凭据并重试，直到全部节点通过。
5. 凭据全部通过后，让用户选择是否创建 Skill 专用账号；若选择专用模式，再展示账号和目录预览。
6. 用户选择后先执行 `<CLI> ssh identity select --allow-create <true|false>` 保存本地策略；详情确认和中心选择完成后执行 `<CLI> ssh identity apply [--confirm-details true]`，完成账号/密钥配置和 SQLite 状态上报；需要审计归属时附加 `--operator-id <operator>`。
7. 显式中心部署只有 `readyForCenterDeploy=true` 才能创建任务，否则返回 `taskCreated=false`。
8. `SELF_REPLICATE` 任务不因缺少 SSH 凭据而提前阻断；自复制失败后进入 `WAITING_USER_INPUT/SSH_CREDENTIAL_REQUIRED`。标准任务只需要目标节点、源节点和部署策略；运行时从注册中心源节点的 `workspaceRoot` 自动推导 `artifactPath` 与 `scriptPath`，不要要求用户填写或在提示词中暴露这些内部路径。
   - CLI 在任务提交前根据当前中心自动补齐 Relay 注册、心跳和授权校验参数；用户和主 AI 不填写内部 API 路径。
9. 用户完成配置与中心预检后执行 `<CLI> deploy resume <taskId> --host <ip> --port <port> --username <user>`，恢复原任务。身份状态写入独立审计表，审计内容不含凭据或私钥。
10. 恢复后仍需等待 relay 注册和健康检查同时通过。
    - Relay 对外地址使用目标可达主机和实际 Relay 端口，不能注册 `0.0.0.0`；该地址只写入 `relay-endpoint`，不得用 SSH/IP 主机覆盖 Relay 的机器名身份。
    - `nodeId` 是逻辑身份，不是 SSH 地址。部署前必须通过 `<CLI> relay node <nodeId>` 从 CC center 读取 `host`、SSH 端口、Relay 端口、`relayEndpoint` 和 `workspaceRoot`；禁止把 `nodeId` 中的机器名直接当作 SSH 主机，也禁止自行猜测 IP。
    - Relay `nodeId` 固定来源于目标机器名和 Relay 端口；中心通过实际端口和规范化工作目录把部署前 IP 标识收口到最终 `nodeId`。
    - 部署前目标标识与中心下发的最终 `nodeId` 不同时，中心按实际 Relay 端口和规范化工作目录关联同一部署并保存最终 `nodeId`，不得永久停在 `WAIT_REGISTER`。
    - 目录制品内容必须直接安装到渲染后的目标工作目录，不能形成“目标目录/源节点目录名”的额外嵌套。
11. 如果当前中心仅监听本机地址而目标 relay 在远端，先执行中心自动规划：从本次节点清单或已保存集群成员中探测资源，选择资源更充足且具备部署权限的节点，并自动探测可用中心端口。
12. 自动中心完成启动、健康检查、跨节点可达验证和状态同步后，CLI 自动生成 relay 注册端点并继续部署；普通流程不得要求用户填写 `centerRegisterEndpoint`。
13. 只有用户主动选择“手动指定中心”时，才收集协议、主机、端口和基础路径。自动规划失败时提供“重试自动选择、排除节点、手动指定、取消”四类操作。
14. SSH 身份初始化完成后，先返回 `BOOTSTRAP_EXECUTION_MODE_REQUIRED`：用户选择“自动执行”时连续执行剩余引导；选择“检视”时逐步展示探测、中心、部署和验证结果。
15. 检视模式的当前步骤按语义提供“自动执行本步”“手动配置本步”“后续全部自动执行”；手动字段按需生成，不能用一个固定大表单要求用户理解全部内部参数。

### 免密引导

1. 当通用凭据可登录但尚未免密时，先用该账号在远端完成授权配置。
2. 中心部署必须使用当前 CC center 自己的公钥；调用端本地密钥只用于调用端测试，不能替代远端中心密钥。
3. 将中心公钥写入目标节点 `authorized_keys`；多节点互通场景再按授权策略同步集群公钥。
4. 若账号没有写权限或没有 sudo，直接提示需要更高权限账号，不要伪装成功；但如果专用账号已经创建且引导账号仅无法读取其目录，必须标记为 `INACCESSIBLE`，优先用集群私钥直接登录专用账号验收，不得判为创建失败。

### 平台与失败边界

- 本机仅支持 Windows 与 Linux。
- Windows 侧若缺少 OpenSSH Client，应先提示启用该功能。
- Linux 侧若缺少 `ssh` / `scp` / `ssh-keygen`，应提示安装 openssh-client。
- 远端若缺少 `bash`、`sh`、`mkdir`、`tar`、`chmod`，应优先使用绝对路径探测；仍失败则返回缺包错误。
- 远端若 `PATH` 受限，只要基础命令能通过绝对路径调用，仍可继续部署。

## 协作语义

- 同一会话向同一目标节点发送的消息由 CC center 按 FIFO 持久排队；同一会话的不同目标节点以及不同会话可以并行。命令返回 `QUEUED` 表示消息已可靠入队，不应重复下发；讨论和会话追问场景会在目标完成后自动唤醒原发起节点继续处理。

- `ccrelay-cli` 是会话内控制与任务编排工具，不是业务域专用诊断工具。
- 当前调用该 Skill 的 AI 负责理解用户意图、选择协作模式、创建会话和向终端反馈；`DISCUSSION` 会话由 CC center 从首批健康参与节点中随机选择远端主 Agent负责拆分、推进和收敛。
- 远端子 Agent 负责在目标机器上读取日志、查询状态、执行允许的诊断动作，并返回证据。
- 远端子 Agent 是否调用 AI，由远端 Agent 代码根据 `ccrelay-cli` 工作请求自行判断。
- 远端子 Agent 目标默认采用 `ReAct` 执行：按“观察、思考、行动、审计”的循环推进，直到完成、失败、超时或达到最大步数。
- 当前远端异步任务已接入 Java 托管 Runner；结构化动作、步数、白名单、超时、注入、调参、停止和审计可标记为 `L5_JAVA_ENFORCED_REACT`。
- 自然语言请求未提供结构化动作时会走 `ASK_AI` 模型兜底；这类请求只能说明模型链路可用，不能证明命令白名单等动作级约束被触发。
- `ReAct` 的最大步数、命令白名单、单步超时、任务总超时、审计级别与是否允许 AI 都必须由控制参数覆盖，不能写死在业务场景里；强约束执行必须由 Java 控制点完成。
- 长任务必须支持运行中控制：提示词注入用于中断并引导当前推理，停止用于取消任务，调参用于改变剩余步数或执行约束。
- 多节点 `fanout` 默认不是同步等待所有节点返回，而是先初始化全部参与节点和主 Agent，再异步创建任务并返回逐节点状态。调用 Skill 的 AI 后续通过 `session get`、`session messages` 和任务事件反馈会话进度。
- 所有 Relay 使用同一份固定职责提示词，并根据中心下发的 `coordinatorNodeId`、`coordinatorEpoch`、`participantNodeIds` 和 `agentRole` 判断本轮职责；主 Agent 是临时协调角色，不具备额外事实权威。
- 主 Agent 不可用时只接受 CC center 的 `COORDINATOR_CHANGED` 会话控制事件，不允许节点自行选举。新主 Agent 先同步共享上下文增量，再继续原会话。
- 发现远端 Agent 不可用时，不要手工 SSH 修好后算成功；必须通过 skill 内 `<CLI>` 恢复、部署或失败报告路径闭环。
- 发现本地 Skill runtime 缺少 `app.jar`、JRE、内置 Python 或部署脚本时，立即报告 `SKILL_RUNTIME_INCOMPLETE` 并停止。禁止搜索历史会话、历史命令、SSH 配置或私钥来猜测凭据，也禁止绕过 Skill 直接 SSH；先安装完整发布包，再从第一步重新引导。
- 未注册目标仅提供 IP/SSH 端口时，首次访问统一执行 `<CLI> ssh identity plan --node <ip:port>...`，不要逐节点调用 `ssh preflight` 代替集群引导。任何命令返回 `NEED_USER_INPUT` 后立即结束当前轮次，逐项原样展示 `prompt/options/applicability/securityWarning/fields`；不得改写成“把用户名密码发给我”。只有用户明确选择明文输入后，才展示和接收秘密字段。

## 真实 AI 配置与验收

mock agent 只能验证 relay、授权和 A2A 通道，不代表真实 AI 链路已经走通。若用户要求完整真实链路，必须先准备 CC 配置。

首次引导必须把模型配置准备安排在“自动执行/逐步检视”选择之前，更不能拖到远端 `center bootstrap`、Relay 部署或部署恢复阶段。账号模式和专用账号详情确认后，先完成模型来源选择、API key 输入方式选择、连通测试和受保护写入，再展示执行模式。模型配置缺失时，即使用户此前表达过自动执行意图，也不得自动拉起安全终端或代替用户选择输入方式。该约束属于引导顺序，不要求后续每个命令重复测试模型 API；已有配置后的运行时错误直接返回用户，由用户决定重试或调整。

### 配置来源判断

1. Windows 运行 `scripts/prepare-cc-config.ps1 --discover`，Linux 运行 `scripts/prepare-cc-config.sh --discover`。
2. `--discover` 返回 `MODEL_CONFIG_SOURCE_REQUIRED` 时，必须先原样展示“使用检测到的本机模型配置 / 填写新的模型配置 / 取消”选项并停止；扫描到候选配置不等于用户已经选择复用，禁止直接询问候选配置缺少的字段。
3. 发现顺序覆盖当前进程的 `OPENAI_*`、`ANTHROPIC_*`，Claude Code 的 `CLAUDE_CONFIG_DIR/settings.json`、`~/.claude/settings.json`，以及本地模型配置文件。Claude Code 配置中的 `ANTHROPIC_AUTH_TOKEN` 按 API 凭据读取，从而兼容 `cc switch` 等配置工具写入的当前生效配置；不得依赖第三方工具私有数据库，也不得使用默认模型或默认 Base URL 填充缺失字段并声称为检测结果。发现后询问用户：“检测到本机 AI 配置，使用本机配置还是用户输入？”
4. 用户选择“使用本机配置”时，自动填充：
   - `model`
   - `baseUrl`
   - `apiKey`
5. 用户选择“用户输入”时，请用户输入：
   - `model`
   - `baseUrl`
   - `apiKey`

如果用户选择本机配置但发现结果不完整，只收集缺失字段。例如只缺模型时仅展示“模型:”输入项；缺 API key 时进入安全输入方式选择。已检测到的字段不得重复询问。
6. 收集 `model` 和 `baseUrl` 后，先调用 `--input-options`，无论宿主是否有桌面 GUI，都完整展示三种 API key 输入方式及适用场景：弹窗（高风险 + 有桌面 GUI）、安全命令（高风险 + 无桌面 GUI）和明文（低风险 + 无桌面 GUI/用户承担风险）。只有用户明确选择 `LAUNCH_SECURE_TERMINAL` 后才能调用 `--launch-secure-terminal`；推荐项、可用性检测、自动执行模式都不构成用户授权。

### 配置生成位置

模板文件：

- `assets/config-templates/cc-model-config.template.yml`

真实运行配置文件：

- Skill 本地受保护配置区中的 `.local/cc-model-config.yml`

该文件不进入公共 runtime bundle。配置发现只在调用 Skill 的本机执行，一份本机配置作为整个集群的统一模型配置；部署时由中心生成目标级受保护配置包，与 runtime artifact 分开传输，并在远端 `install-relay.sh` 启动前安装。远端不得扫描或复用远端账号自己的环境变量、Claude Code 配置或 `cc switch` 等工具数据；启动脚本先清除远端已有模型变量，再从本机下发配置导出 `ANTHROPIC_MODEL`、`ANTHROPIC_BASE_URL`、`ANTHROPIC_API_KEY`、`CLAUDE_CODE_API_KEY` 以及兼容的 `OPENAI_*` 环境变量。配置缺失或不完整时直接失败。

### 测试要求

1. 生成 `cc-model-config.yml` 后，用对应平台的 `prepare-cc-config` 包装脚本执行 `--test`，对 `baseUrl + apiPath` 发起一次最小 Anthropic 兼容请求。
2. 如果测试不可用，返回 `MODEL_CONFIG_TEST_UNAVAILABLE`，向用户说明具体原因和分类，但不预判一定是配置错误；提供“API 已恢复后重试当前配置”“更换模型配置”“取消配置”三个选项。测试未通过不得写入配置。原因包括：
   - `baseUrl` 为空或不可达
   - `model` 为空或模型不存在
   - `apiKey` 为空、过期或无权限
   - 模型服务消息通道不兼容
   - 网络超时或 TLS/代理错误
3. 如果成功，才能生成目标级配置包、继续部署并执行真实远端 A2A；配置缺失或下发失败时只能报告 `RELAY_READY_AI_UNAVAILABLE`。
4. 返回 `REMOTE_MOCK_RESPONSE` 只能标记为 mock 验收；必须得到模型真实文本响应，才算完整真实 AI 链路通过。

## 验收等级

- `L0_SKILL_FORMAT`：标准 Skill 目录、frontmatter、脚本与构建产物通过。
- `L1_LOCAL_CONTROL`：本地 runtime、SQLite、会话、授权、relay、task 基础命令通过。
- `L2_REMOTE_RELAY`：远端 relay 部署、启动、注册、心跳和发现通过。
- `L3_A2A_MOCK`：A2A 跨节点消息与异步任务闭环，但返回 mock 或固定响应。
- `L4_REMOTE_AI_WEAK_REACT`：远端真实模型可调用，ReAct 参数可下发，但执行约束仍是弱约束。
- `L5_JAVA_ENFORCED_REACT`：Java 托管 Runner 强制执行步数、白名单、超时、注入、调参、停止和审计。

## 部署说明

- `SELF_REPLICATE` 表示源 relay 自行尝试分发并启动目标 relay。
- 如果自复制失败且 `enableCenterFallback=true`，运行时自动切换到中心侧 SSH 部署。
- SSH 兜底是最后手段；即便 SSH 部署成功，也必须等注册与健康检查通过后才视为节点可用。
- 部署耗时时使用 `<CLI> task observe <taskId>` 或父任务批量观测查看每个目标节点的阶段、百分比、已传输字节、滚动速度、预计剩余时间、耗时和最近更新时间；监控只读，不通过监控视图修改执行参数。
- `COPYING_ARTIFACT` 等长阶段必须持续产生 `DEPLOY_NODE_PROGRESS` 快照。总大小未知时仍展示传输字节和速度；超过停滞阈值无更新时明确显示 `STALLED`，不得把无进展任务持续显示为普通运行中。

## 必需上下文字段

- `sessionId`
- `sourceNodeId`
- `targetNodeId`
- `grantId`
- `signedToken`
- 授权返回或 `ccrelay-cli` 自动补齐的目标 relay 上下文
- `ccrelay-cli` 自动维护的中心校验上下文

## 安全规则

- 远端 A2A 请求不得绕过授权校验。
- 已部署但未注册的节点不得视为可用。
- 当健康远端 relay 已可访问时，不要从 SSH 开始。

## 运行时环境语义

- “不依赖外部环境”只适用于 relay 安装与自举启动。
- 这并不表示远端命令执行要忽略目标机器环境。
- relay 启动后，应基于已探测能力，显式且安全地使用远端环境。

### 实用规则

1. 制品包已包含运行时时，relay 自举不应要求系统预装 Java。
2. 远端执行仍可使用目标机器已有的 `python`、`node`、`git`、`docker`、`rg` 等工具。
3. 将 `PATH` 视为运行时能力输入，而不是永远可靠的常量。
4. 如果远端 shell 的 `PATH` 受限，部署仍应尽量保证 relay 能启动，但命令规划应依赖已探测的命令可用性。
5. relay 应向控制面暴露环境摘要，至少覆盖 `PATH`、shell、OS、架构和常用命令可用性。

## 环境摘要上报

relay 启动后，应向中心上报一份小型运行时环境摘要。

### 推荐上报时机

- 在 `ccrelay-cli relay register` 对应流程中发送完整快照。
- 在 `ccrelay-cli relay heartbeat` 对应流程中发送部分更新。

### 推荐字段

- `shell`
- `path`
- `cwd`
- `os`
- `osVersion`
- `arch`
- `hostname`
- `javaBundled`
- `javaExternalAvailable`
- `availableCommands`
- `probedAt`

### 控制面使用方式

- 优先基于 `availableCommands` 选择命令策略，而不是只看 `PATH` 文本。
- 缺少环境摘要时，将其视为“能力未知”，不要视为注册失败。
- 能力探测不完整时，采用保守执行策略。
