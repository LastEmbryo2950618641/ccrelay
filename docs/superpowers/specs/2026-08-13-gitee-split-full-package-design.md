# Gitee 分卷完整包安装设计

## 背景

Gitee Release 单文件上限为 100 MB。完整包在 Gitee 上以 7-Zip 分卷压缩包发布，资产名为 `ccrelay-full.zip.001`、`.002` 等；先从 `.001` 恢复出外层 ZIP，再解压外层 ZIP，才得到需要校验和安装的内层 `ccrelay-full.zip`。GitHub Release 不受此流程影响，仍直接发布 `ccrelay-full.zip`。

当前引导 Skill 和 Windows/Linux 安装脚本将两个来源都视为单个 ZIP，导致 Gitee 优先下载无法使用并回退 GitHub。

## 目标

- 引导 Skill 明确说明 Gitee 使用 7-Zip 分卷，GitHub 使用完整 ZIP。
- 引导包内置固定版本的 7-Zip 独立命令行工具；Windows 和 Linux/macOS 安装脚本能够从 Gitee Release 自动发现、下载并恢复全部分卷。
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
6. 根据当前操作系统和 CPU 架构选择引导包内置的 7-Zip 命令，从 `.001` 恢复出外层 ZIP。内置平台不匹配时才尝试系统 `7z` 或 `7zz`。
7. 使用系统 ZIP 解压能力打开外层 ZIP，并要求其中存在唯一的 `ccrelay-full.zip`；该内层文件才是待安装归档。
8. 按 SHA-256 文件校验内层完整 ZIP。

### GitHub 和自定义来源

继续直接下载 `ccrelay-full.zip` 与 `ccrelay-full.zip.sha256`，不调用 7-Zip，不查找分卷。

## 内置 7-Zip 与失败处理

- `bootstrap-skill/ccrelay/assets/7zip/` 保存固定版本的 Windows x64、Linux x64、macOS x64/arm64 独立命令行程序及 SHA-256 清单。
- 构建和测试校验所有内置文件的 SHA-256，避免工具损坏或无意漂移。引导 ZIP 直接包含这些文件，安装时不再从第三方站点下载工具。
- 安装脚本优先使用匹配平台和架构的内置程序。没有匹配项时，Windows 依次查找系统 `7z.exe`、`7zz.exe`、`7z`、`7zz`；POSIX 依次查找 `7z`、`7zz`。
- 7-Zip 仅用于 Gitee 分卷恢复。没有匹配的内置程序且系统也未安装时，Gitee 来源失败并自动尝试 GitHub，不阻止 GitHub 单 ZIP 安装。
- 引导 Skill 随附 7-Zip 许可证与来源说明，明确使用 7-Zip、GNU LGPL/BSD/unRAR 许可构成，并链接官方源码页面。
- 分卷元数据异常、下载失败、7-Zip 返回非零、外层 ZIP 缺少内层完整包或 SHA-256 不匹配，均不得覆盖现有 Skill。
- 每次来源失败后删除归档、校验文件、分卷和恢复目录，防止残留数据污染回退流程。
- 最终所有来源均失败时，错误信息需指出 Gitee 分卷恢复或 GitHub 下载均未成功。

## 测试

在发布包测试中增加静态契约检查，覆盖：

- 引导 Skill 明确出现 Gitee 分卷、`.001`、7-Zip、恢复完整 ZIP 和 GitHub 直接 ZIP 的说明。
- Windows/Linux 脚本均识别 `ccrelay-full.zip.NNN`，检查连续编号，按平台选择内置 7-Zip 并保留系统命令回退。
- 引导包包含所有声明支持平台的工具、SHA-256 清单和许可证来源说明，且文件摘要与清单一致。
- Gitee 校验发生在分卷恢复和外层 ZIP 解压之后，并以得到的内层 `ccrelay-full.zip` 为对象。
- GitHub 和自定义来源仍请求 `ccrelay-full.zip`。
- Gitee 失败后仍保留 GitHub 回退路径。

通过内置 7-Zip 和临时测试资产执行至少一个真实分卷恢复测试，不依赖开发机预装 7-Zip。

## 非目标

- 不修改 GitHub Release 资产格式。
- 不改变 Gitee 分卷的生成或上传流程。
- 不把各分卷分别做 SHA-256 校验；发布的校验文件对应恢复后的完整 `ccrelay-full.zip`。
- 不改变完整 Skill 覆盖安装和 `.local` 数据保留逻辑。
- 不支持 Windows x86/arm64、Linux arm 或其他未列出的引导平台；这些平台只能使用系统 7-Zip 或回退 GitHub。
