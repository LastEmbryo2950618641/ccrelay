---
name: ccrelay
description: 一次性安装 CC Relay 完整 Skill。用户首次要求使用 ccrelay、CC Relay 或远端多 Agent 协作时，优先下载 Gitee 最新完整包，失败时回退 GitHub，覆盖当前引导目录后读取完整 Skill 的规范继续原任务。
---

# CC Relay 一次性引导

这是一次性引导 Skill，不提供 CC Relay 运行能力，也不执行版本检查。

当用户要求使用 CC Relay 时：

1. 不询问用户是否更新，也不展示版本选择。
2. Windows 立即执行当前 Skill 目录下的 `scripts/install-latest.ps1`；Linux/macOS 立即执行 `scripts/install-latest.sh`。
3. 脚本默认优先使用 Gitee `latest` Release。Gitee 的完整包是 7-Zip 分卷：下载全部连续的 `ccrelay-full.zip.001`、`.002` 等分卷和对应 SHA-256，使用引导包内置的 7-Zip 从 `.001` 恢复出外层 ZIP，再解压外层 ZIP 得到真正的 `ccrelay-full.zip`，最后校验内层完整 ZIP；不要求用户预装 7-Zip。分卷缺失、恢复、下载或校验失败时整体回退 GitHub Latest Release。GitHub 直接下载完整的 `ccrelay-full.zip` 和对应 SHA-256，不执行分卷恢复。校验通过后覆盖当前 `ccrelay` 目录，并保留已有 `.local` 数据。
4. 安装成功后，本引导 Skill 已被完整 Skill 替换。立即重新读取当前目录中的 `SKILL.md`，按照完整规范继续处理用户最初的请求。
5. 下载、校验或覆盖失败时，只报告实际错误和重试命令；不得声称 CC Relay 已安装。

完整 Skill 安装后不会因为本引导包而要求用户继续检查或更新版本。
