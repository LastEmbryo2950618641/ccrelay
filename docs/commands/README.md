# 命令文档

## 概览

本目录只面向 skill 目录内 `<CLI>` 使用者，Markdown 不暴露运行时内部协议细节。

所有健康检查、会话、relay 注册、授权、部署、异步任务和 A2A 协作都应通过 skill 目录内 CLI 脚本完成。内部请求细节仅保留在实现代码和自动化测试中，不作为用户操作入口。
当前命令面已包含 `task observe` / `agent observe` 窗口化观测，以及 `config get|set|unset|list|reload|history` 在线配置闭环。

## 文档列表

- `C:\D\workspace\WDSAVS-dev-skill\docs\commands\ccrelay-cli.md`：`ccrelay-cli` 中文命令手册，覆盖日常协同、部署恢复、远端诊断和闭环验收。
- `C:\D\workspace\WDSAVS-dev-skill\docs\commands\skill-runtime.md`：控制面能力说明，不列内部协议。
- `C:\D\workspace\WDSAVS-dev-skill\docs\commands\a2a.md`：A2A 能力说明，不列内部协议。
- `C:\D\workspace\WDSAVS-dev-skill\docs\commands\remote-first-end-to-end-examples.md`：CLI 端到端流程，不列内部协议。

## 阅读顺序

1. 先读 `C:\D\workspace\WDSAVS-dev-skill\docs\commands\ccrelay-cli.md`，按命令完成操作。
2. 如需理解控制面能力边界，再读 `C:\D\workspace\WDSAVS-dev-skill\docs\commands\skill-runtime.md`。
3. 如需理解 A2A 协作语义，再读 `C:\D\workspace\WDSAVS-dev-skill\docs\commands\a2a.md`。
4. 如需端到端验收顺序，再读 `C:\D\workspace\WDSAVS-dev-skill\docs\commands\remote-first-end-to-end-examples.md`。

## 范围说明

- 健康远端 relay 访问永远是第一路径。
- 目标 relay 不可用时，首选部署模式是 `SELF_REPLICATE`。
- 中心侧 SSH 部署只作为兜底。
- relay 只有在注册与心跳健康检查都成功后才可用。
- 远端 A2A 调用应携带同一会话身份，避免多 agent 会话串线。
