# A2A 能力说明

本文只说明 A2A 协作语义，不暴露内部协议细节。实际操作统一使用 skill 目录内 `<CLI> a2a`。

## 能力分组

- `a2a agent-card`：查看远端协作能力声明。
- `a2a message-send`：发起一次同步远端协作消息。
- `a2a task-create`：创建异步远端协作任务。
- `a2a task-get`：查询异步远端任务详情。
- `a2a task-observe`：读取异步远端任务窗口化观测。
- `a2a task-events`：读取异步远端任务事件。
- `a2a task-cancel`：取消异步远端任务。

## 必需上下文

- `sessionId`
- `sourceNodeId`
- `targetNodeId`
- `grantId`
- `signedToken`
- 授权返回的目标 relay 上下文
- 授权返回的中心校验上下文

## 闭环规则

- 先执行 `<CLI> session open`。
- 再执行 `<CLI> access request`。
- 只有授权决策为 `ALLOW`，才执行 `<CLI> a2a message-send` 或 `<CLI> a2a task-create`。
- 如果授权决策为 `ALLOW_WITH_DEPLOY`，先执行 `<CLI> task create` 触发 relay 部署恢复。
- 大文件只传引用，不直接塞进消息体；阈值默认 `5 MB`，可配置。
- 若要观察远端执行过程，优先用 `<CLI> agent observe` 或 `<CLI> task observe`，不要只盯着 `task-events` 或 SSE。
- 多会话并发时，所有 A2A 请求必须携带同一会话上下文，避免串线。
