# 2026-07-29 上线发布说明

## 范围

本次发布打包独立的 `ccrelay` 控制面与远端 relay 协作链路，使用本地 SQLite 持久化，并默认采用远端优先的 A2A 行为。

已验证范围包括：

- 本地 center 运行时：`<center-url>`
- relay 注册、心跳、授权、吊销、续期与校验
- `DEPLOY_RELAY` 异步任务生命周期
- `task observe` / `agent observe` 窗口化观测
- `config get|set|unset|list|reload|history` 在线配置
- 注册 + 心跳后自动关闭部署任务，不依赖手工部署上报
- 源端优先 `SELF_REPLICATE`，中心侧 `CENTER_DEPLOY` 作为兜底
- 从已有远端 relay 扩散第三节点
- 恢复后的 A2A 消息链路返回 `REMOTE_MOCK_RESPONSE`

## 当前已验证拓扑

- center：`127.0.0.1:19291`
- 主 relay A：`47.93.195.246:18091`
- 主 relay B：`111.229.32.85:18091`
- 第三节点：`47.93.195.246:18092`

截至 2026-07-29，三个 relay 均已验证为 `AVAILABLE`。

## 本次发布已修复风险

- 远端代理路径下心跳连接不稳定
- 部署仍处于 `WAITING_DEPLOY` 时授权续期失败
- 部署任务关闭依赖手工 `deploy/report`
- 受限 `PATH` 环境下源 relay 找不到 `ssh`
- 远端登录环境异常导致 `/bin/sh -lc` 副作用
- `47.93.195.246:18091` 包装脚本 身份配置错误
- 旧 relay 进程仍在服务但被误判为重启成功

## 运维基线

- 远端优先访问仍是默认路径
- 节点可用必须同时满足注册成功与心跳健康
- 部署兜底保持开启且已测试
- 大型远端制品包传输可用，但第三节点扩散优先使用 slim 制品包提升速度
- `47` 主 relay 运行时制品包与 in-place 部署制品包文件一致
- `111` 主 relay 制品包与 `slim-18092` 制品包已对齐到同一个 `app.jar`

## 发布结论

基于 2026-07-29 记录的真实验收证据，当前基线适合受控上线与运维交接。

## 运维必须注意

- 包装脚本 身份必须匹配实际目标节点 host 与 relay 端点
- relay 重启前必须确认旧进程真实退出，再重新绑定端口
- 确认 `UNAVAILABLE` 时应使用心跳扫描，不要只看节点详情
- 观测远端执行时优先用 `task observe` / `agent observe`，不要只等事件流结束
- 密钥配置只能走 `config secret get|set`
- 第三节点扩散且传输窗口有限时，优先使用 slim 制品包
