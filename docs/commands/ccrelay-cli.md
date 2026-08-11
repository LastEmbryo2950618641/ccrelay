# ccrelay-cli 命令说明

本文只说明 `ccrelay-cli` 这一命令封装的用法；实际操作统一通过 skill 目录内 `<CLI>` 完成，Markdown 只暴露命令，不暴露内部协议细节。

正常协同只使用中心命令组；显式 `remote` 命令仅用于诊断和恢复，不应作为正常协同首选路径。

## 启动方式

标准发布与验收必须使用 skill 目录内脚本，不依赖 `CODEX_HOME/bin` 或任何外部 shim。

已安装 skill 中，Windows 使用：

```powershell
$skill = "$HOME/.codex/skills/ccrelay"
powershell -ExecutionPolicy Bypass -File "$skill/scripts/ccrelay-cli.ps1" --center <center-url> health
```

已安装 skill 中，Linux/macOS 使用：

```bash
SKILL_DIR="${CODEX_HOME:-$HOME/.codex}/skills/ccrelay"
bash "$SKILL_DIR/scripts/ccrelay-cli.sh" --center <center-url> health
```

开发仓库中可以用同名 skill 内脚本：

```powershell
powershell -ExecutionPolicy Bypass -File codex-skill/ccrelay/scripts/ccrelay-cli.ps1 --center <center-url> health
```

源码开发时才可以直接用 Python：

```powershell
python codex-skill/ccrelay/scripts/ccrelay-cli.py --center <center-url> health
```

发布的 Skill 包内置 Python 3.13，正常使用不依赖宿主机的 Python 版本或 `PATH`。

下文示例中的 `<CLI>` 表示上述 skill 目录内脚本入口，例如：

```powershell
& "$skill/scripts/ccrelay-cli.ps1" --center <center-url> health
```

全局 `ccrelay-cli` 仅是用户显式安装后的可选快捷别名，不作为标准 Skill 依赖。

也可以通过环境变量设置中心地址：

```powershell
$env:CCRELAY_CENTER_URL = "<center-url>"
```

## 公共参数

- `--center`：显式中心运行时地址；未提供时依次读取 `CCRELAY_CENTER_URL`、Skill `.local` 已选中心，最后使用内置本地中心默认值。
- `--timeout`：请求超时时间，单位秒，默认 `60`。
- `--raw`：原样输出响应文本，不格式化 JSON。
- `--json`：直接传完整 JSON 请求体。
- `--json-file`：从文件读取完整 JSON 请求体。

## 命令清单

### 引导阶段

- `bootstrap status [--cluster-id <id>]`：读取三阶段里程碑；无记录返回 `UNINITIALIZED`。
- `bootstrap next --node <host:sshPort>...`：新任务的唯一引导入口；未初始化时返回 SSH 凭据交互，已部署时允许进入正常 Relay 协作。
- `bootstrap mark-deployed [--cluster-id <id>]`：仅允许从 `SSH_READY` 进入 `DEPLOYED`，重复执行幂等。
- `bootstrap reset [--cluster-id <id>]`：删除阶段记录并回到 `UNINITIALIZED`。

阶段 SQLite 仅保存 `cluster_id`、`stage`、`updated_at`，不保存凭据、节点详情、模型配置、错误或中间选择。

### 中心发现

- `center resolve`：识别当前使用的 CC 中心，并返回中心模式与健康状态。
- `center ensure`：确保本地非 AI CC center 已启动。
- `center status`：查看 Skill 管理的本地 center 进程状态。
- `center stop`：停止 Skill 管理的本地 center。
- `center logs [--source AUTO|ACTIVE|ATTEMPT]`：默认优先读取活动远端中心的 Center/Relay 进程、健康状态和日志摘要；指定 `ATTEMPT` 时读取最近一次未完成尝试保存的远端目录和端口。失败结果自身也必须包含 `diagnostic.logTail`，不要求用户手工 SSH 获取日志。
- `center cleanup-attempt`：先采集最近一次未完成尝试的日志，再只停止该尝试目录中的 Center/Relay PID 并清除尝试状态，不影响已经持久化的活动 Center。
- `center plan --node <host:sshPort>`：只读探测候选资源、目录权限和可用端口，生成可审计的自动中心选择计划。
- `center bootstrap --node <host:sshPort> [--bootstrap-timeout 900]`：执行或恢复最近一次匹配的计划，分发自包含运行时、启动远端中心、验证跨节点可达性、同步身份状态并持久化中心。默认完整执行预算为 900 秒；远端已经满足健康、注册和心跳门禁时直接认领，不重复部署。

首次使用仍从本地非 AI center 开始；若远端节点不能访问本地中心，默认由 `center plan|bootstrap` 从受管节点中自动选择资源更充足的远端中心并自动探测端口。只有用户在检视模式主动选择手动配置当前步骤时，才使用 `--selection MANUAL --manual-*` 字段。发行 CLI 不得包含固定公网测试地址。本地 center 首次启动时生成随机 HMAC secret，CLI 与 center 通过 `.local` 受保护配置共享；远端启动脚本将同一 secret 写入受保护文件和进程环境，Java 中环境变量优先于旧运行时配置。

HMAC secret 属于跨进程配置。`config secret set wdsavs.ai.relay.hmac-secret` 只保存新的受保护配置并返回 `restartRequired=true`；必须随后通过 Skill 的中心/Relay 重启或重新部署流程使所有节点使用同一新密钥。

```powershell
<CLI> center resolve
<CLI> center plan --node <node-a:sshPort> --node <node-b:sshPort>
<CLI> center bootstrap --node <node-a:sshPort> --node <node-b:sshPort>
```

### 健康检查

- `health`：检查中心运行时健康状态。

### 会话

- `session open`：打开会话。
- `session close <sessionId>`：关闭会话，并使该会话下仍可用授权立即失效。
- `session get <sessionId>`：查看协作模式、当前主 Agent、epoch 和参与节点。
- `session messages <sessionId>`：按 cursor 增量读取共享上下文，用于终端反馈群聊会话。

```powershell
<CLI> session open --source-node-id <sourceNodeId>
<CLI> session close <sessionId>
```

### Relay 节点

- `relay register`：注册 relay 节点。
- `relay heartbeat`：上报 relay 心跳。
- `relay scan`：扫描可用 relay 节点。
- `relay nodes`：列出 relay 节点。
- `relay node <nodeId>`：查看指定 relay 节点。

```powershell
<CLI> relay scan
<CLI> relay node <targetNodeId>
```

### 授权

- `access request`：申请 relay 访问授权。
- `access get <grantId>`：查看授权状态。
- `access validate`：校验授权，默认自动生成 HMAC 签名。
- `access renew`：续期授权。
- `access revoke`：吊销授权。

```powershell
<CLI> access request `
  --session-id <sessionId> `
  --source-node-id <sourceNodeId> `
  --target-node-id <targetNodeId> `
  --reason "remote collaboration"

<CLI> access validate `
  --grant-id <grantId> `
  --session-id <sessionId> `
  --source-node-id <sourceNodeId> `
  --target-node-id <targetNodeId> `
  --signed-token <signedToken> `
  --capabilities A2A_MESSAGE_SEND `
  --expires-at <expiresAtEpochMs>
```

### 部署

- `deploy report`：上报 relay 部署结果。

```powershell
<CLI> deploy report `
  --task-id <taskId> `
  --session-id <sessionId> `
  --target-node-id <targetNodeId> `
  --deploy-mode SELF_REPLICATE `
  --status SUCCEEDED `
  --health-passed true `
  --registered true
```

### 本地异步任务

- `task observe <taskId>`：查看任务窗口化观测；也可用 `--task-ids` 或 `--parent-task-id` 做批量观测。
- 顶层 `observe` 与 `task observe` 同义，适合先看窗口再决定是否深入。
- `task create`：创建本地异步任务。
- `task create` 必须提供 `--session-id`，或通过 `--json/--json-file` 提供 `sessionId`；CLI 会在发起 HTTP 请求前拒绝缺失会话的请求。
- `task get <taskId>`：查询本地异步任务。
- `task status <taskId>`：查询任务状态。
- `task cancel <taskId>`：取消任务。
- `task events <taskId>`：读取任务事件。
- `task stream <taskId>`：流式订阅任务事件。
- 部署任务进入 `WAITING_USER_INPUT/SSH_CREDENTIAL_REQUIRED` 后不会被普通任务超时覆盖；补齐 SSH 条件后使用 `deploy resume <taskId>` 恢复同一任务。

```powershell
<CLI> task create `
  --session-id <sessionId> `
  --request-id <requestId> `
  --task-type DEPLOY_RELAY `
  --source-node-id <sourceNodeId> `
  --target-node-id <targetNodeId> `
  --payload-json '{"deployMode":"SELF_REPLICATE","enableCenterFallback":true}' `
  --timeout-ms 600000
```

### A2A

- `a2a agent-card`：查看远端协作能力声明。
- `a2a message-send`：发送同步远端协作消息。
- `a2a task-create`：创建异步远端协作任务。
- `a2a task-get <taskId>`：查询异步远端任务。
- `a2a task-events <taskId>`：读取异步远端任务事件。
- `a2a task-cancel <taskId>`：取消异步远端任务。

```powershell
<CLI> a2a message-send `
  --session-id <sessionId> `
  --grant-id <grantId> `
  --signed-token <signedToken> `
  --source-node-id <sourceNodeId> `
  --target-node-id <targetNodeId> `
  --target-relay-endpoint <targetRelayEndpoint> `
  --center-grant-validate-endpoint <centerGrantValidateEndpoint> `
  --prompt "请检查远端工作目录并返回摘要"
```

如需真实模型配置，可传：

```powershell
<CLI> a2a message-send `
  --model-config-file .\codex-skill\ccrelay\assets\runtime-bundle\ccrelay\config\cc-model-config.yml `
  --prompt "ping"
```

`--model-config-file` 会读取 `model`、`provider`、`baseUrl/endpoint`、`remoteEndpoint` 与 `relayCommand`，并把模型服务地址转为请求体中的运行上下文；不会把 `apiKey` 放进 A2A 请求体。真实 `apiKey` 应留在随 relay 下发的 `cc-model-config.yml` 或远端运行环境中。

模型配置准备入口支持三档 API key 输入：

```powershell
scripts\prepare-cc-config.ps1 --input-options --test --write <skill>\.local\cc-model-config.yml --model <model> --base-url <url>
scripts\prepare-cc-config.ps1 --launch-secure-terminal --test --write <skill>\.local\cc-model-config.yml --model <model> --base-url <url>
scripts\prepare-cc-config.ps1 --prompt-api-key --test --write <skill>\.local\cc-model-config.yml --model <model> --base-url <url>
```

`--discover` 返回 `NEED_USER_INPUT/MODEL_CONFIG_SOURCE_REQUIRED` 和固定来源选择模板。调用方必须先让用户选择复用检测结果或填写新配置；只有选择复用后，才检查并询问检测结果中的缺失字段。

- `LAUNCH_SECURE_TERMINAL`：适用于高风险环境且有桌面 GUI；必须用户明确选择后，调用方才能执行 `--launch-secure-terminal`。
- `SECURE_TERMINAL_COMMAND`：适用于高风险环境且只有终端；必须用户明确选择后，用户在自己的终端执行返回的安全输入命令。
- `MANUAL_VISIBLE_INPUT`：适用于低风险环境或用户明确承担明文风险；必须用户明确选择后，调用方使用 `--api-key-env <envName>` 或临时 `--api-key-file` 传递，禁止把 key 放入命令行。
- 三个选项必须始终展示；`recommended`、`available` 和宿主 GUI 检测只用于说明适用性，不得由调用方代替用户选择。

如果已提供 `grantId` 与 `targetNodeId`，`ccrelay-cli a2a` 会自动补齐会话、签名 token、目标 relay 上下文和中心校验上下文；正常验收不需要手工填写内部上下文字段。

### Agent 编排

- `agent observe`：查看远端 Agent 的窗口化观测；支持单任务、批量任务和父任务聚合。
- `agent run`：在会话内向单个远端 Agent 下发工作请求；CLI 自动处理会话、授权、上下文和关闭。
- `agent ask`：`agent run` 的兼容别名；是否调用 AI 由远端 Agent 代码决定。
- `agent fanout`：在同一个会话内向多个远端 Agent 异步下发同一工作请求，默认返回各节点任务与即时状态兜底，不同步等待全部完成。
- `agent task-create`：创建远端异步 Agent 工作任务。
- `agent task-events <taskId>`：读取远端异步 Agent 任务事件。
- `agent inject <taskId>`：向运行中的远端 Agent 注入提示词，用于中断当前方向并追加引导。
- `agent adjust <taskId>`：调整运行中远端 Agent 的 ReAct 控制参数。
- `agent stop <taskId>`：停止远端 Agent 异步任务。

同一 `sessionId + targetNodeId` 的消息按 FIFO 排队执行；同会话不同节点、不同会话仍可并行。`agent run` 返回 `QUEUED` 时消息已持久化，调用方不要重复发送。讨论与会话追问模式会在排队消息完成后自动唤醒原发起节点，同步最新共享上下文并继续协作。

```powershell
<CLI> agent run `
  --target-node-id <targetNodeId> `
  --prompt "请检查该机器上与某个失败任务相关的日志并返回证据"

<CLI> agent fanout `
  --collaboration-mode DISCUSSION `
  --target-node-ids <nodeA,nodeB,nodeC> `
  --prompt "请围绕同一目标协作，发现分歧或证据不足时主动联系对应节点并形成共同结论"
```

`agent` 命令面向远端多 Agent 协作，不绑定 Hadoop、YARN 或任何单一业务域。当前使用该 Skill 的主 AI 负责把用户问题转换为工作请求，并综合远端 Agent 返回的证据。

远端 Agent 目标默认按 `ReAct` 模式执行，可用以下参数控制。当前远端异步任务已接入 Java 托管 Runner；结构化动作、步数、白名单、超时、注入、调参、停止和审计可按 `L5_JAVA_ENFORCED_REACT` 验收。自然语言请求未提供结构化动作时会走 `ASK_AI` 模型兜底，这类请求只能证明模型链路可用，不能证明命令白名单等动作级约束被触发。

- `--max-steps`：最大执行步数，默认 `12`。
- `--command-whitelist`：允许执行的远端命令集合，逗号分隔；为空时使用远端默认策略。
- `--step-timeout-ms`：单步超时，默认 `60000`。
- `--task-timeout-ms`：任务总超时，默认 `600000`。
- `--audit-level`：审计级别，默认 `FULL`。
- `--allow-ai`：远端需要时是否允许调用 AI，默认 `true`。

多节点 `fanout` 的默认语义是 `INDEPENDENT_FANOUT`。共同讨论必须显式使用 `--collaboration-mode DISCUSSION`；命令会先用全部首批参与节点初始化会话并由 CC center 随机选择主 Agent，再创建各节点异步任务。如果希望短暂等待初始结果，可显式设置 `--poll-seconds <seconds>`；正常长任务应结合 `session get`、`session messages`、`agent task-events`、`agent inject`、`agent adjust` 或 `agent stop` 后续控制。

### 在线配置

- `config get <key>`：读取配置。
- `config list`：列出配置，可按 `--prefix` 过滤。
- `config set <key>`：更新普通配置。
- `config unset <key>`：清除普通配置覆盖。
- `config reload`：触发配置重载。
- `config history <key>`：查看配置变更历史。
- `config secret get <key>`：读取密钥配置元数据。
- `config secret set <key>`：更新密钥配置，建议配合 `--value-file`。

普通 `config get` / `config list` 会返回脱敏后的视图；密钥类配置不会在 CLI 输出里明文展示。

### SSH 凭据与部署恢复

- `ssh config show`：查看脱敏后的通用与节点级 SSH 配置。
- `ssh config set-default`：配置唯一通用 SSH 凭据。
- `ssh config set-passwordless-default --username <user> --port <port> [--private-key-file <path>]`：保存已有免密通用账号；私钥路径为空时使用系统 SSH config、SSH Agent 或默认密钥，不要求或保存密码。
- `ssh config set-node --host <ip> --port <port>`：配置 `ip:port` 节点级覆盖。
- `ssh tools`：检查本机 `ssh`、`scp`、`ssh-keygen`。
- `ssh identity plan`：只读探测引导账号、远端系统和账号创建权限。
- `ssh identity select --allow-create <true|false>`：用户选择后立即保存本地账号模式、专用账号名和目录模板，不修改远端。
- `ssh identity apply --execution-mode <AUTO_EXECUTE_REMAINING|INSPECT_STEP_BY_STEP>`：在用户确认执行模式、资源探测和中心选择完成后，从本地配置读取账号模式并初始化身份。中心尚未启动时先保存在 Skill 本地，中心启动后由 `ssh identity verify` 同步到 SQLite。
- `ssh identity status`：查看本地和中心保存的账号策略与脱敏能力摘要。
- `ssh identity verify`：刷新中心到节点和节点间互信状态。
- `ssh identity rotate-key`：轮换专用模式的共享集群密钥。

身份写入命令支持 `--operator-id`，默认取当前系统用户；每次成功的状态快照会写入独立的 `wdsavs_ai_ssh_identity_audit_event` 表。审计只保存操作、账号模式、能力摘要、节点/互信边数量和公钥指纹，不保存密码、私钥或完整秘密参数。
- `ssh prepare-center`：获取当前中心公钥，用本地凭据写入目标节点，再由中心验证免密。
- `ssh preflight`：`prepare-center` 的部署门禁入口。
- `deploy resume <taskId>`：预检成功后恢复 `SSH_CREDENTIAL_REQUIRED` 状态的原部署任务。

密码默认使用安全交互输入。若当前进程没有 TTY，`ssh config set-default|set-node` 返回 `SSH_PASSWORD_INPUT_REQUIRED`，其中包含 `inputCapabilities` 和完整选项：安全终端弹窗（高风险 + 有桌面 GUI）、安全输入命令（高风险 + 无桌面 GUI）和明文输入（低风险 + 无桌面 GUI/用户承担风险），另有取消选项。调用方必须等待用户明确选择，不能自动使用安全终端或推荐项。自动化传入只使用临时 `--password-file` 或 `--password-env`，不要把密码直接放在命令行。密码只存于 skill 的 `.local/ssh-credentials.json`，不进入中心数据库和任务记录。

非 TTY 的标准返回形态：

```json
{
  "status": "NEED_USER_INPUT",
  "failureType": "SSH_PASSWORD_INPUT_REQUIRED",
  "reason": "NO_INTERACTIVE_TTY",
  "inputCapabilities": {
    "platform": "WINDOWS 或 LINUX",
    "currentProcessTty": false,
    "secureHiddenInput": false,
    "guiTerminalLauncherDetected": false,
    "manualVisibleFallbackAvailable": true
  },
  "options": [
    {
      "id": "LAUNCH_SECURE_TERMINAL",
      "recommended": false,
      "applicability": "高风险 + 有桌面 GUI",
      "command": "由 CLI 生成的 Skill 包装命令",
      "resume": "命令成功后回到当前会话重试原命令"
    },
    {
      "id": "SECURE_TERMINAL_COMMAND",
      "recommended": false,
      "applicability": "高风险 + 无桌面 GUI",
      "command": "由 CLI 生成的 Skill 包装命令"
    },
    {
      "id": "MANUAL_VISIBLE_INPUT",
      "recommended": false,
      "applicability": "低风险 + 无桌面 GUI",
      "securityWarning": "密码可能进入对话记录或模型上下文"
    },
    {"id": "CANCEL", "recommended": false}
  ]
}
```

GUI 只负责拉起终端，不负责接收或回显密码；Windows 使用 `ccrelay-cli.ps1`，Linux 使用 `ccrelay-cli.sh`。密码输入完成后，用户只需返回“已完成”，不应把密码复制回当前对话。

首次执行 `ssh identity plan|apply|verify|rotate-key` 时，CLI 严格返回以下交互阶段：

1. `SSH_CREDENTIALS_REQUIRED`：没有通用访问配置时，可选择填写通用账号密码、使用已有免密通用账号，或按 `ip:port` 填写节点覆盖。免密分支只填写用户名、端口和可选私钥路径，仍须对全部节点做只读验证。
2. `SSH_CREDENTIALS_INVALID`：通用或节点凭据未通过全部节点的只读 SSH 验证，必须根据 `validation.failedNodes` 补齐或更新凭据；此阶段不会进入账号模式，也不会创建任务或修改远端。
3. `SSH_ACCOUNT_MODE_REQUIRED`：全部节点验证通过后，选择 `ALLOW_DEDICATED_ACCOUNT` 或 `USE_EXISTING_ACCOUNT`，并立即通过 `ssh identity select` 保存选择。
4. `DEDICATED_ACCOUNT_DETAILS_CONFIRMATION`：专用模式下确认 `dedicatedUsername`、`remoteDirectoryTemplate` 和 `deploymentDirectories`；只有明确传入 `--confirm-details true` 后，`identity apply` 才能执行远端变更。
5. `MODEL_CONFIG_PREPARE`：专用账号详情确认后，先让用户选择模型配置来源和 API key 输入方式，完成模型测试与受保护写入。安全终端仅在用户明确选择后拉起；推荐项不等于自动授权。此阶段未通过时 `identity plan|apply` 返回 `taskCreated=false`，不得接受自动执行模式。
6. `BOOTSTRAP_EXECUTION_MODE_REQUIRED`：模型配置完成后、任何远端变更之前，选择“自动执行后续全部步骤”或“逐步检视后执行”。未传 `--execution-mode` 时，`identity plan|apply` 均停在此阶段且 `taskCreated=false`。
6. `RESOURCE_DISCOVERY` 与 `CENTER_SELECTION`：按用户选择的模式探测节点资源并确定中心；不得在此之前指定临时中心。
7. `SSH_IDENTITY_INITIALIZATION`：以已选中心执行 `identity apply`。若中心进程尚未启动，持久化状态为 `CENTER_IDENTITY_SYNC_DEFERRED`，待中心启动后通过 `identity verify` 完成同步。

每个阶段的 `interaction.options` 是可选动作，`interaction.fields` 是需要填写的字段清单。调用 Skill 的 AI 必须把结构化选项和字段展示给用户，不能用一句“请提供密码”代替，也不能代替用户确认。

```powershell
<CLI> ssh config set-default --username liuqi --port 22
<CLI> ssh config set-passwordless-default --username ops --port 22 --private-key-file ~/.ssh/id_ed25519
<CLI> ssh identity plan --center-node 47.93.195.246:22 --node 47.93.195.246:22 --node 111.229.32.85:22
<CLI> ssh identity select --allow-create false --node <node-a>:22 --node <node-b>:22
<CLI> ssh identity apply --execution-mode AUTO_EXECUTE_REMAINING --center-node <selected-center>:22 --node <node-a>:22 --node <node-b>:22
# 专用模式还必须在用户看到目录预览后确认：
<CLI> ssh identity select --allow-create true --confirm-details true --dedicated-username ccrelay --node <node-a>:22 --node <node-b>:22
<CLI> ssh identity apply --confirm-details true --execution-mode AUTO_EXECUTE_REMAINING --center-node <selected-center>:22 --node <node-a>:22 --node <node-b>:22
<CLI> ssh prepare-center --host 47.93.195.246 --port 22 --username liuqi
<CLI> deploy resume <taskId> --host 47.93.195.246 --port 22 --username liuqi
```

缺少凭据时命令返回 `NEED_USER_INPUT`、`interaction.options` 和 `interaction.fields`。调用 Skill 的 AI 必须把这些选项和字段展示给用户并等待选择，不能只输出错误，也不能手工 SSH 修复。

验收时必须标注等级：

- `L0_SKILL_FORMAT`：标准 Skill 格式与构建产物。
- `L1_LOCAL_CONTROL`：本地 runtime 与控制面基础命令。
- `L2_REMOTE_RELAY`：远端 relay 注册、心跳与发现。
- `L3_A2A_MOCK`：跨节点 A2A mock 闭环。
- `L4_REMOTE_AI_WEAK_REACT`：真实模型调用 + 弱约束 ReAct。
- `L5_JAVA_ENFORCED_REACT`：Java 托管 Runner 强制执行结构化 ReAct 动作、步数、白名单、超时、注入、调参、停止和审计。

```powershell
<CLI> agent inject <taskId> `
  --target-node-id <targetNodeId> `
  --prompt "停止当前搜索方向，优先检查最近 30 分钟错误日志"

<CLI> agent adjust <taskId> `
  --target-node-id <targetNodeId> `
  --max-steps 20 `
  --command-whitelist "ls,cat,grep,tail,find"

<CLI> agent stop <taskId> `
  --target-node-id <targetNodeId> `
  --reason "证据已足够，停止远端执行"
```

### 显式远端 relay

以下命令需要指定 `remote --relay <relay-base-url>`，仅用于诊断、恢复或手工验收；正常协同应优先走中心授权后的 `a2a` 命令。

- `remote health`：检查远端 relay 健康状态。
- `remote chat`：直接向远端 relay 发起诊断对话。
- `remote grant-validate`：诊断远端授权校验。
- `remote self-replicate`：诊断源端自复制能力。
- `remote task-create`：诊断远端异步任务创建。
- `remote task-get <taskId>`：诊断远端异步任务查询。
- `remote task-events <taskId>`：诊断远端任务事件读取。
- `remote task-cancel <taskId>`：诊断远端异步任务取消。

## 推荐闭环

1. `center resolve`
2. `health`
3. `relay scan` 或 `relay node <nodeId>`
4. 优先执行 `agent run`、`agent fanout` 或 `agent task-create`
5. 若目标不可用，执行 `task create --task-type DEPLOY_RELAY`
6. 用 `task stream` 或 `task status` 等待部署完成
7. 重新执行 `agent` 工作请求
8. 需要低层排查时再使用 `session`、`access`、`a2a`

`DEPLOY_RELAY` 已经属于现有协作会话时传入真实 `--session-id`；独立引导部署省略该参数，CLI 会先打开部署会话，再使用 Center 返回的 `sessionId` 创建任务。不得自行构造固定会话 ID。
