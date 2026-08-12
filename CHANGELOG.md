# 变更日志

本文档记录 CC Relay 各版本面向用户的主要新增特性和重要变更。

## [0.1.1] - 2026-08-12

### 新增

- 新增 Center 集中管理标准 Skill 的目录能力，支持通过 `ccrelay-cli skill install/list/remove` 安装、查询和失效 Skill。
- 新增 Relay 心跳触发的异步 Skill 同步，使用 SHA-256 校验制品一致性，并维护 `INSTALLING`、`INSTALLED`、`INVALID` 三种本地安装状态。
- 新增 Claude Skill 目录映射，使安装完成的 Skill 可被远端 Agent 发现和使用。
- 新增面向中小规模集群的批量节点处理和并发控制，部署目标集合可持久化并进行全量校验。
- 新增可配置 SSH 参数，兼容较旧的 OpenSSH 客户端以及用户自定义连接参数。
- 新增示例角色扮演 Skill `gugugaga-roleplay`。

### 变更

- Relay 部署默认采用覆盖部署，自动清理目标目录中的残余文件后重新安装。
- SSH 身份准备与验证覆盖完整目标节点集合，避免以单节点结果误判整个集群状态。
- 改进专用账户创建、权限检查、空闲端口探测和批量部署失败处理。
- 修复 Web UI 打开已关闭历史会话时错误同步任务并无法读取共享上下文的问题。
- Web UI 会话首次收到用户问题后，由协调 Agent 异步生成并持久化简洁标题；失败时保留节点标题兜底。
- 本地构建与 GitHub Actions 统一生成完整 Skill ZIP 和一次性 GitHub 下载引导 ZIP；引导包完成覆盖后不再参与运行或要求后续更新。
- GitHub 标签构建成功后显式将对应版本设为 Latest Release，供固定的 `releases/latest/download` 地址下载。
- 引导包下载增加有限重试，并在 Windows `curl` 失败后回退 PowerShell 下载器。
- 合并版本构建与 Release 发布流程，由单次手动 Actions 运行测试、打包、更新版本标签并发布资产。

## [0.1.0] - 2026-08-11

### 新增

- 发布 CC Relay 首个独立版本，提供 Center、Relay、SQLite 持久化和标准 Skill 运行时。
- 提供远端节点注册、心跳、授权、A2A 消息、异步任务和多 Agent 协作能力。
- 提供 Java 托管 ReAct Runner，支持命令执行、步数与超时控制、任务注入、调参、停止和审计。
- 提供 `ccrelay-cli` 会话、节点、任务、观测、配置、SSH 和部署命令。
- 提供自包含运行时、Windows/Linux 构建安装脚本以及 GitHub Actions ZIP 发布流程。
- 提供远端优先、自复制优先和 Center SSH 兜底的部署流程。

[0.1.1]: https://github.com/LastEmbryo2950618641/ccrelay/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/LastEmbryo2950618641/ccrelay/releases/tag/v0.1.0
