# 2026-07-29 部署指南

## 1. 部署角色

### 本地中心节点

- 运行时类型：独立 Spring Boot 技能运行时
- 访问地址：`<center-url>`
- 持久化：本地 SQLite

### 远端主 relay

- `47.93.195.246:18091`
- `111.229.32.85:18091`

### 可选第三节点

- `47.93.195.246:18092`
- 用于验证从已有 relay 进行多跳扩散

## 2. 制品结构

### 完整制品包

用于主 relay 运行目录和通用部署目录：

- `app.jar`
- `runtime.tar.gz`
- `install-relay.sh`
- `mock_agent.py`
- `bundle-manifest.json`
- 已安装时可包含解压后的 `runtime/` 目录

### 精简制品包

第三节点扩散时，如果传输大小敏感，使用该制品：

- `app.jar`
- `runtime.tar.gz`
- `install-relay.sh`
- `mock_agent.py`
- `bundle-manifest.json`

## 3. 当前已验证路径

### `47.93.195.246`

- 主运行目录：`/home/liuqi/ccrelay`
- in-place 部署源目录：`/home/liuqi/wdsavs-ai-agent-deploy-inplace/ccrelay`

### `111.229.32.85`

- 主 包装脚本 根目录：`/home/liuqi/ccrelay`
- 主制品包根目录：`/home/liuqi/ccrelay/ccrelay`
- 第三节点精简制品：`/home/liuqi/ccrelay-slim-18092`

## 4. 部署模式

### `SELF_REPLICATE`

当已有远端 relay 作为源节点时使用。

已验证示例：

- 源节点：`111.229.32.85:18091`
- 目标节点：`47.93.195.246:18092`
- 最终成功任务：`1158523c-f7fa-4b7b-b96e-0370b7475cd8`

### `CENTER_DEPLOY`

当源端自复制无法完成时作为兜底使用。

已验证示例：

- 兜底成功任务：`3f0ba8c7-bd47-45a5-9613-8e8eb123bd2e`

## 5. 包装脚本规则

每个 包装脚本 都必须设置其启动节点的身份。

上线前必须核对以下字段：

- `WDSAVS_AI_RELAY_NODE_HOST`
- `WDSAVS_AI_RELAY_ENDPOINT`
- `WDSAVS_AI_RELAY_NODE_ID_FILE`
- `WDSAVS_AI_RELAY_REGISTER_ENDPOINT`
- `WDSAVS_AI_RELAY_HEARTBEAT_ENDPOINT`
- `WDSAVS_AI_RELAY_GRANT_VALIDATE_ENDPOINT`

不要在未修改这些值的情况下，把一个 包装脚本 复用于另一个节点。

## 6. 建议发布顺序

1. 构建最新 `app.jar`
2. 更新本地 center jar 并确认健康
3. 更新 `47` 主 relay 运行时与 in-place 部署源目录
4. 更新 `111` 主 relay 制品包
5. 如果使用第三节点扩散，更新 `111` 上的 slim 第三节点制品
6. 逐个重启 relay
7. 确认心跳扫描返回所有预期节点均为 `AVAILABLE`
8. 执行一次真实授权 + A2A 验证
9. 如果部署代码有变更，执行一次真实恢复测试

## 7. 上线验收门禁

只有满足以下全部条件，才认为发布通过：

- 中心健康检查为 `UP`
- relay scan 显示预期节点均为 `AVAILABLE`
- 部署任务无需手工 `deploy/report` 即可自动闭环
- 当前版本至少一次源端优先自复制成功
- 当前版本至少一次中心兜底成功
- 恢复后的 A2A 返回 `REMOTE_MOCK_RESPONSE`
