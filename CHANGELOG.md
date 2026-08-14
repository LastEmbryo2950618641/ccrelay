# 变更日志

本文档记录 CC Relay 各版本面向用户的主要新增特性和重要变更。

## [0.1.2] - 2026-08-13

### 新增

- 新增 SSH 身份密钥算法 `AUTO`、`ED25519`、`RSA` 配置；自动模式根据全部目标节点有效算法选择兼容密钥，旧环境自动使用 RSA 3072。
- 新增长任务后台执行与定时进度反馈规范，覆盖引导下载、SSH 身份准备、Center bootstrap、Relay 批量部署、制品分发和远端 Agent 任务。
- 新增部署架构契约与脚本兼容故障的自然语言运维兜底，引导 AI 按部署目标使用标准系统工具完成失败步骤，并回到控制面状态和健康验收闭环。

### 变更

- 本机新建免密密钥默认使用 RSA 3072，已有用户密钥保持复用。
- 集群专用密钥、中心节点密钥和 Java Center 管理密钥统一使用身份计划选出的算法；AUTO 检测结果变化时生成新算法密钥并在验证成功后切换，旧密钥文件保留用于失败回退。
- 显式选择不兼容算法时在身份计划阶段返回不兼容节点，避免账号创建完成后才发现认证失败。
- 解耦专用账号模式与 SSH 信任拓扑；超过可配置阈值的集群由 AUTO 动态采用 CENTER_ONLY，只执行 Center 到节点验证，避免平方级 FULL_MESH 探测。
- CENTER_ONLY 仅在 Center 专用账号安装集群私钥，普通节点只接收公钥并清理 Skill 受管的历史私钥；Center 到节点验证复用一次外层 Center SSH 并在 Center 内按并发上限执行。
- 批量 Relay 部署的 `--concurrency` 同时约束服务端真实部署执行数；等待许可的节点进入 `WAITING_BATCH_SLOT` 并持续可观测，不再只限制 CLI 请求提交速度。
- 复用严格匹配的脱敏身份验证快照，避免同一 Center 启动后重复执行 apply 已完成的全量 SSH 验证；Center、节点集合、账号模式或拓扑变化时仍强制重新验证。

## [0.1.1] - 2026-08-13

### 新增

- 新增 Center 集中管理标准 Skill 的目录能力，支持通过 `ccrelay-cli skill install/list/remove` 安装、查询和失效 Skill。
- 新增 Relay 心跳触发的异步 Skill 同步，使用 SHA-256 校验制品一致性，并维护 `INSTALLING`、`INSTALLED`、`INVALID` 三种本地安装状态。
- 新增 Claude Skill 目录映射，使安装完成的 Skill 可被远端 Agent 发现和使用。
- 新增面向中小规模集群的批量节点处理和并发控制，部署目标集合可持久化并进行全量校验。
- 新增可配置 SSH 参数，兼容较旧的 OpenSSH 客户端以及用户自定义连接参数。
- 新增部分节点失败治理：账号创建、密钥安装、互信验证或 Relay 部署部分失败时，统一汇总失败原因，并由用户选择治理失败节点或排除后继续。
- 新增 Gitee 分卷完整包下载能力，引导包内置经过校验的 7-Zip 工具，并在 Gitee 不可用时回退 GitHub。
- 新增示例角色扮演 Skill `gugugaga-roleplay`。

### 变更

- Relay 部署默认采用覆盖部署，自动清理目标目录中的残余文件后重新安装。
- SSH 身份准备与验证覆盖完整目标节点集合，避免以单节点结果误判整个集群状态。
- 改进专用账户创建、权限检查、空闲端口探测和批量部署失败处理。
- 改进 SSH 密码安全输入：支持当前终端隐藏输入和独立安全终端，不再因缺少显式输入方式而静默等待。
- 专用账号密码改为强复杂度随机密码，并在使用旧弱密码时自动更新受保护记录。
- 修复 Git Bash/MSYS 环境下模型 API 路径被错误转换为 Windows 文件路径的问题。
- Windows 引导下载仅使用经过验证的 Windows PowerShell 5.1 或 PowerShell 7 运行时。
- 修复 Web UI 打开已关闭历史会话时错误同步任务并无法读取共享上下文的问题。
- Web UI 会话首次收到用户问题后，由协调 Agent 异步生成并持久化简洁标题；失败时保留节点标题兜底。
- 本地构建与 GitHub Actions 统一生成完整 Skill ZIP 和一次性 GitHub 下载引导 ZIP；引导包完成覆盖后不再参与运行或要求后续更新。
- GitHub 标签构建成功后显式将对应版本设为 Latest Release，供固定的 `releases/latest/download` 地址下载。
- 引导包下载增加有限重试，并在 Windows `curl` 失败后回退 PowerShell 下载器。
- 引导下载在使用 `curl` 时显示实时进度，并显示分卷序号和下载完成后的文件大小，避免长时间下载被误判为卡住。
- 合并版本构建与 Release 发布流程，由单次手动 Actions 运行测试、打包、更新版本标签并发布资产。
- 新增 Gitee Go 手动构建发布方案，由 Gitee 侧本地构建完整包，避免 GitHub Runner 跨网直传大文件到 Gitee。
- 修复 Windows 上通过 SCP 上传运行时制品时反斜杠路径被远端误作完整文件名的问题。
- 修复 Center 启动失败后 PID 文件未记录的同目录孤儿进程持续占用端口、导致后续覆盖部署失败的问题。

## [0.1.0] - 2026-08-11

### 新增

- 发布 CC Relay 首个独立版本，提供 Center、Relay、SQLite 持久化和标准 Skill 运行时。
- 提供远端节点注册、心跳、授权、A2A 消息、异步任务和多 Agent 协作能力。
- 提供 Java 托管 ReAct Runner，支持命令执行、步数与超时控制、任务注入、调参、停止和审计。
- 提供 `ccrelay-cli` 会话、节点、任务、观测、配置、SSH 和部署命令。
- 提供自包含运行时、Windows/Linux 构建安装脚本以及 GitHub Actions ZIP 发布流程。
- 提供远端优先、自复制优先和 Center SSH 兜底的部署流程。

[0.1.1]: https://github.com/LastEmbryo2950618641/ccrelay/compare/v0.1.0...v0.1.1
[0.1.2]: https://github.com/LastEmbryo2950618641/ccrelay/compare/v0.1.1...v0.1.2
[0.1.0]: https://github.com/LastEmbryo2950618641/ccrelay/releases/tag/v0.1.0
