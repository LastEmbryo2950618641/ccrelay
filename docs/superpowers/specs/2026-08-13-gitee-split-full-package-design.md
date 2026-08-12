# Gitee 分卷完整包安装设计

## 背景

Gitee Release 单文件上限为 100 MB。`ccrelay-full.zip` 在 Gitee 上以 7-Zip 分卷压缩包发布，资产名为 `ccrelay-full.zip.001`、`.002` 等；从 `.001` 解压后才得到需要校验和安装的完整 `ccrelay-full.zip`。GitHub Release 不受此流程影响，仍直接发布 `ccrelay-full.zip`。

当前引导 Skill 和 Windows/Linux 安装脚本将两个来源都视为单个 ZIP，导致 Gitee 优先下载无法使用并回退 GitHub。

## 目标

- 引导 Skill 明确说明 Gitee 使用 7-Zip 分卷，GitHub 使用完整 ZIP。
- Windows 和 Linux/macOS 安装脚本能够从 Gitee Release 自动发现、下载并恢复全部分卷。
- 恢复出的 `ccrelay-full.zip` 必须通过现有 `ccrelay-full.zip.sha256` 校验后才能安装。
- Gitee 任一步骤失败时清理该来源的临时产物，并整体回退 GitHub 单 ZIP 下载。
- 保留自定义 `ReleaseBaseUrl` 的现有单 ZIP 行为。

## 下载流程

### Gitee

1. 读取 Gitee latest Release 和附件列表。
2. 下载 `ccrelay-full.zip.sha256`。
3. 筛选名称匹配 `ccrelay-full.zip.NNN` 的附件，按三位数字后缀升序排列。
4. 要求第一卷为 `.001`，且所有编号连续；缺卷、重号或没有分卷均视为 Gitee 来源失败。
5. 将全部分卷下载到同一临时目录，并保留原始文件名。
6. 使用可用的 `7z` 或 `7zz` 命令从 `.001` 解压，在独立恢复目录中得到 `ccrelay-full.zip`。
7. 仅接受恢复目录根部唯一的 `ccrelay-full.zip`，将其作为待安装归档。
8. 按 SHA-256 文件校验恢复出的完整 ZIP。

### GitHub 和自定义来源

继续直接下载 `ccrelay-full.zip` 与 `ccrelay-full.zip.sha256`，不调用 7-Zip，不查找分卷。

## 依赖与失败处理

- Gitee 分卷路径需要 7-Zip CLI。Windows 依次查找 `7z.exe`、`7zz.exe`、`7z`、`7zz`；POSIX 依次查找 `7z`、`7zz`。
- 仅在实际尝试 Gitee 分卷恢复时要求 7-Zip。机器未安装 7-Zip 时，Gitee 来源失败并自动尝试 GitHub，不阻止 GitHub 单 ZIP 安装。
- 分卷元数据异常、下载失败、7-Zip 返回非零、未生成目标 ZIP或 SHA-256 不匹配，均不得覆盖现有 Skill。
- 每次来源失败后删除归档、校验文件、分卷和恢复目录，防止残留数据污染回退流程。
- 最终所有来源均失败时，错误信息需指出 Gitee 分卷恢复或 GitHub 下载均未成功。

## 测试

在发布包测试中增加静态契约检查，覆盖：

- 引导 Skill 明确出现 Gitee 分卷、`.001`、7-Zip、恢复完整 ZIP 和 GitHub 直接 ZIP 的说明。
- Windows/Linux 脚本均识别 `ccrelay-full.zip.NNN`，检查连续编号并调用 7-Zip。
- Gitee 校验发生在分卷恢复之后。
- GitHub 和自定义来源仍请求 `ccrelay-full.zip`。
- Gitee 失败后仍保留 GitHub 回退路径。

若环境具备 7-Zip，再通过临时测试资产生成分卷并执行恢复测试；没有 7-Zip 的开发环境不应因此使基础测试不可运行。

## 非目标

- 不修改 GitHub Release 资产格式。
- 不改变 Gitee 分卷的生成或上传流程。
- 不把各分卷分别做 SHA-256 校验；发布的校验文件对应恢复后的完整 `ccrelay-full.zip`。
- 不改变完整 Skill 覆盖安装和 `.local` 数据保留逻辑。
