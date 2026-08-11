# 2026-07-29 运维手册

## 1. 核心健康检查

`<CLI>` 表示已安装 skill 目录内脚本，Windows 为 `powershell -ExecutionPolicy Bypass -File $HOME/.codex/skills/ccrelay/scripts/ccrelay-cli.ps1`，Linux/macOS 为 `bash ${CODEX_HOME:-$HOME/.codex}/skills/ccrelay/scripts/ccrelay-cli.sh`。

### 中心节点

- 健康检查：`<CLI> health`
- 心跳扫描：`<CLI> relay scan`
- 节点列表：`<CLI> relay nodes`
- 任务观测：`<CLI> task observe <taskId>`
- 运行中控制：`<CLI> agent observe <taskId>`
- 在线配置：`<CLI> config get <key>`

### 预期健康状态

- 两节点基线：`availableCount >= 2`
- 当前已验证三节点基线：`availableCount = 3`
- 每个 relay 节点状态应为 `AVAILABLE`

## 2. 当前远端目录

### `47.93.195.246`

- 主 relay 运行目录：`/home/liuqi/ccrelay`
- 源端部署制品目录：`/home/liuqi/wdsavs-ai-agent-deploy-inplace/ccrelay`
- 第三节点运行根目录：`/home/liuqi/ccrelay-18092`

### `111.229.32.85`

- 主 relay 包装脚本 根目录：`/home/liuqi/ccrelay`
- 主 relay 制品包：`/home/liuqi/ccrelay/ccrelay`
- 第三节点精简制品：`/home/liuqi/ccrelay-slim-18092`

## 3. 当前包装脚本职责

### `47.93.195.246:18091`

- 包装脚本：`/home/liuqi/ccrelay/install-relay-with-env-bundle.sh`
- 身份必须保持：
  - `WDSAVS_AI_RELAY_NODE_HOST=47.93.195.246`
  - `WDSAVS_AI_RELAY_ENDPOINT=<47 主 relay 端点>`

### `111.229.32.85:18091`

- 包装脚本：`/home/liuqi/ccrelay/install-relay-with-env-bundle.sh`
- 身份必须保持：
  - `WDSAVS_AI_RELAY_NODE_HOST=111.229.32.85`
  - `WDSAVS_AI_RELAY_ENDPOINT=<111 主 relay 端点>`

### `47.93.195.246:18092`

- 扩散 包装脚本 存放在 `111`：`/home/liuqi/ccrelay/install-relay-47-18092.sh`
- 身份必须保持：
  - `WDSAVS_AI_RELAY_NODE_HOST=47.93.195.246`
  - `WDSAVS_AI_RELAY_ENDPOINT=<47 第三节点 relay 端点>`

## 4. 安全重启流程

### 主 relay 重启

1. 确认 包装脚本 应代表哪个节点身份
2. 停止真实运行中的 relay 进程，而不是只删过期 pid 文件
3. 删除过期 `relay.pid`
4. 通过 包装脚本 脚本重新启动
5. 从中心心跳扫描确认 `AVAILABLE`
6. 如果是版本变更，执行一次真实授权 + A2A 检查

### 为什么重要

旧进程可能仍占用端口，导致新进程因 `Address already in use` 启动失败，而运维人员误以为重启已经成功。

## 5. 恢复决策顺序

1. 优先使用健康远端 relay 访问
2. 如果目标 relay 不可用，申请授权并进入部署流程
3. 首先使用 `SELF_REPLICATE`
4. 如果源端自复制失败，允许中心兜底
5. 只有注册 + 心跳成功后才认为节点可用
6. 部署任务应自动闭环为 `SUCCESS / REGISTERED`

## 6. 常见故障模式

### 现象：`Cannot run program "ssh"`

可能原因：

- 源 relay 运行环境无法解析 `ssh`

当前已包含的缓解措施：

- Unix 环境下部署 executor 优先使用绝对路径 `/usr/bin/ssh`

### 现象：远端登录 profile 导致 shell 报错

可能原因：

- 异常远端 profile 脚本破坏 `/bin/sh -lc`

当前已包含的缓解措施：

- 部署 executor 现在使用 `/bin/sh -c`

### 现象：进程被杀后节点仍看起来是 `AVAILABLE`

可能原因：

- 只检查了节点详情

运维动作：

- 执行 `<CLI> relay scan`，强制刷新当前可用性评估

### 现象：大制品部署兜底超时

可能原因：

- 远端目录拷贝体积超过当前传输窗口

运维动作：

- 第三节点扩散优先使用 slim 制品包

## 7. 变更后最小验证

任何类生产变更后，至少验证：

- 中心健康
- 节点扫描健康
- 一次授权请求
- 一次真实 A2A 消息
- 一次窗口化任务观测
- 一次在线配置读取或更新
- 如果变更涉及部署代码或 包装脚本，验证一次部署与恢复路径
