# 远端优先端到端示例

本文只给出 skill 目录内 `<CLI>` 操作链路，不暴露内部协议细节。

`<CLI>` 表示已安装 skill 目录内脚本，Windows 为 `powershell -ExecutionPolicy Bypass -File $HOME/.codex/skills/ccrelay/scripts/ccrelay-cli.ps1`，Linux/macOS 为 `bash ${CODEX_HOME:-$HOME/.codex}/skills/ccrelay/scripts/ccrelay-cli.sh`。

## 健康远端 relay 已存在

```powershell
<CLI> health
<CLI> relay scan
<CLI> agent run --target-node-id <targetNodeId> --prompt "请返回远端摘要"
```

## 目标 relay 不可用

```powershell
<CLI> relay node <targetNodeId>
<CLI> task create --session-id <sessionId> --task-type DEPLOY_RELAY --source-node-id <sourceNodeId> --target-node-id <targetNodeId> --payload-json '{"deployMode":"SELF_REPLICATE","enableCenterFallback":true}'
<CLI> task stream <taskId>
<CLI> relay scan
<CLI> agent run --target-node-id <targetNodeId> --prompt "继续远端协同"
```

## 异步远端任务

```powershell
<CLI> agent task-create --target-node-id <targetNodeId> --prompt "执行远端检查"
<CLI> agent observe <taskId> --target-node-id <targetNodeId> --grant-id <grantId>
<CLI> task observe <taskId> --target-node-id <targetNodeId>
<CLI> agent task-get <taskId> --target-node-id <targetNodeId> --grant-id <grantId>
<CLI> agent inject <taskId> --target-node-id <targetNodeId> --grant-id <grantId> --prompt "调整方向，优先检查最新错误"
<CLI> agent adjust <taskId> --target-node-id <targetNodeId> --grant-id <grantId> --max-steps 20
<CLI> agent stop <taskId> --target-node-id <targetNodeId> --grant-id <grantId>
```

如需在线调节默认策略，可结合：

```powershell
<CLI> config get wdsavs.ai.observation.default-limit
<CLI> config set wdsavs.ai.agent.default-max-steps --value 20
```

## 授权续期与吊销

```powershell
<CLI> access renew --grant-id <grantId> --session-id <sessionId>
<CLI> access revoke --grant-id <grantId> --session-id <sessionId>
```

## 验收口径

- `health` 成功只表示中心运行时可访问。
- `relay scan` 中目标节点健康且已注册，才表示目标 relay 可用。
- `a2a message-send` 返回模型真实文本，才表示真实 AI 链路通过。
- 返回 `REMOTE_MOCK_RESPONSE` 只能算 mock 链路通过。
- 任何需要 SSH 的动作只能通过部署任务作为兜底触发，不作为正常协同入口。
