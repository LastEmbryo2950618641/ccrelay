# CC Relay 一次性引导包

`ccrelay-bootstrap.zip` 是 CC Relay 的轻量安装入口，供用户交给 Codex、Claude Code
或其他支持标准 Skill 的 AI 使用。它的作用是帮助 AI 自动取得并安装最新的完整
CC Relay Skill，而不是只向用户展示一条下载命令。

## CC Relay 是什么

CC Relay 是一个面向远端多 Agent 协作的控制面。完整 Skill 安装后，AI 可以在用户
授权和引导下完成远端节点准备、Relay 部署、节点状态检查、多 Agent 讨论与协作排障。

引导包本身不包含完整运行时，因此不能直接部署 Relay、管理集群或发起多 Agent
协作。它只负责完成首次安装，随后会被完整 Skill 覆盖。

## 引导包会做什么

当用户要求安装或使用 CC Relay 时，引导包会让 AI：

1. 优先从 Gitee Latest Release 下载完整 Skill，失败时回退 GitHub Latest Release。
2. 下载所需文件，并在 Gitee 使用分卷包时自动完成分卷恢复。
3. 使用发布方提供的 SHA-256 文件校验完整包。
4. 将完整 Skill 解压并覆盖当前 `ccrelay` 引导目录，同时保留已有 `.local` 数据。
5. 重新读取完整 Skill 的 `SKILL.md`，继续处理用户最初提出的 CC Relay 请求。

下载、校验、解压或覆盖失败时，引导包应向用户报告实际错误，不得声称已经安装
成功，也不会在安装完成后持续要求用户检查或更新版本。

## 如何使用

先将 `ccrelay-bootstrap.zip` 安装为 Skill：

```text
帮我安装 Skill ccrelay-bootstrap.zip
```

安装引导包后，可以直接提出原本要完成的任务，例如：

```text
/ccrelay 帮我安装完整 CC Relay
```

也可以直接提出具体目标：

```text
/ccrelay 帮我部署到节点 IP1、IP2 和 IP3
```

AI 会先通过本引导包安装完整 Skill，再按照完整 CC Relay 的规范继续该任务。

## 使用说明

- 安装过程需要访问 Gitee 或 GitHub，并会显示可获得的下载与分卷进度。
- Windows 支持 Windows PowerShell 5.1 或 PowerShell 7 及以上版本。
- Linux 和 macOS 使用 Bash 安装脚本。
- Gitee 分卷恢复所需的 7-Zip 已包含在引导包内，无需用户单独安装。
- 引导包不会自行部署远端服务，也不会绕过后续流程中的用户确认和凭据授权。
