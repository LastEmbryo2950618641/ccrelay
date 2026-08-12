# Gitee Go 构建说明

## 目标

使用 Gitee Go 在国内侧直接构建 `ccrelay` 发布包，避免通过 GitHub Runner 跨网直传大文件到 Gitee Release。

构建产物与 GitHub Actions 保持相同文件集合：

- `ccrelay-full.zip`
- `ccrelay-full.zip.sha256`
- `ccrelay-bootstrap.zip`
- `ccrelay-bootstrap.zip.sha256`
- `release-manifest.json`

流水线文件：`.workflow/MasterPipeline.yml`

## 执行入口

流水线实际执行脚本：`scripts/gitee_go_build.sh`

脚本会按以下顺序执行：

1. 检查并准备 `pwsh`、`python3`、`gradle`、`java`
2. 运行 `gradle test`
3. 运行 `python scripts/test_ccrelay_cli.py`
4. 运行 `python scripts/test_release_packages.py`
5. 运行 `gradle bootJar`
6. 调用 `scripts/build_runtime_bundle.ps1`
7. 调用 `build.sh`

## SHA 一致性说明

### 可以保证的部分

以下条件满足时，Gitee Go 与 GitHub Actions 产物可以做到相同 SHA-256：

1. 使用相同提交内容
2. 使用相同依赖版本
3. 使用相同运行时源文件（JRE、Python、Claude Code 归档）
4. 使用相同的 Gradle / Java 主版本
5. 使用相同的打包规则

当前仓库已固定以下可重现条件：

- `build.gradle` 对所有归档任务启用固定文件顺序和去时间戳
- `scripts/package_skill_release.py` 对 ZIP 条目使用固定时间戳
- `scripts/package_skill_release.py` 对 ZIP 条目使用固定遍历顺序
- `.sha256` 文件由产物实际字节计算得到

### 不能无条件保证的部分

若 Gitee Go 侧与 GitHub Actions 侧存在以下差异，则 SHA 可能不同：

- `gradle` 或 `java` 次版本差异导致 `bootJar` 字节不同
- 运行时缓存源文件版本不同
- `pwsh` / 系统工具链行为差异导致 runtime bundle 内容变化

因此，更准确的表述是：

> 在相同输入与工具链条件下，可以做到与 GitHub Actions 产物同 SHA；
> 若工具链或运行时来源不同，则保证文件集合与功能一致，并通过各自 `.sha256` 校验。

## 建议配置

- Gitee Go 制品库：`ccrelay-release`
- 流水线不配置 `push` / `PR` / `tag` 自动触发，统一通过 Gitee Go 页面手动选择分支后执行版本构建
- 构建完成后直接从 Gitee 制品库或后续发布流程分发完整包

