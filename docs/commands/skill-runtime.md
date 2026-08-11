# 控制面能力说明

本文只说明独立技能运行时的控制面能力，不暴露内部协议细节。实际操作统一使用 skill 目录内 `<CLI>`，不把 `ccrelay-cli` 当成标准调用口径。

## 能力分组

- `health`：检查中心运行时是否可访问。
- `session`：打开和关闭一次远端协同会话。
- `relay`：注册、心跳、扫描和查询 relay 节点。
- `access`：申请、查看、校验、续期和吊销授权。
- `deploy`：上报 relay 部署结果。
- `task`：创建、查询、取消和订阅本地异步任务。
- `observe`：窗口化读取任务当前状态、事件窗口、控制态与心跳摘要。
- `config`：读取、更新、重载和审计在线配置。

## 关键规则

- `session open` 是授权与 A2A 调用前置步骤。
- `access request` 返回 `ALLOW` 后，才可进入远端协同。
- `ALLOW_WITH_DEPLOY` 表示需要先部署或恢复目标 relay。
- `DEPLOY_RELAY` 默认优先 `SELF_REPLICATE`，中心 SSH 只作为兜底。
- 只有目标 relay 注册成功且健康检查通过，才算节点可用。
- 观测请求应优先走窗口化 `observe`，不要只依赖 `events` 或 `events/stream`。
- 密钥类配置必须走 `config secret get|set`，普通 `config get|set|unset` 不得明文处理密钥。
- `session close` 会关闭会话，并使该会话下仍可用授权立即失效。

## 推荐入口

`<CLI>` 表示已安装 skill 目录内脚本，Windows 为 `powershell -ExecutionPolicy Bypass -File $HOME/.codex/skills/ccrelay/scripts/ccrelay-cli.ps1`，Linux/macOS 为 `bash ${CODEX_HOME:-$HOME/.codex}/skills/ccrelay/scripts/ccrelay-cli.sh`。

```powershell
<CLI> health
<CLI> relay scan
<CLI> session open --source-node-id <sourceNodeId>
<CLI> access request --session-id <sessionId> --source-node-id <sourceNodeId> --target-node-id <targetNodeId>
<CLI> task create --task-type DEPLOY_RELAY --payload-json '{"deployMode":"SELF_REPLICATE","enableCenterFallback":true}'
<CLI> session close <sessionId>
```

如需完整命令参数，阅读 `C:\D\workspace\WDSAVS-dev-skill\docs\commands\ccrelay-cli.md`。
