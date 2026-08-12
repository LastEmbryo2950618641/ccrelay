# Gitee Split Full Package Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让一次性引导 Skill 使用内置 7-Zip 自动恢复 Gitee 分卷完整包，同时保持 GitHub 直接 ZIP 下载和失败回退。

**Architecture:** 固定版本的多平台 7-Zip CLI 与许可证作为引导 Skill 资产随包发布。PowerShell 和 POSIX 安装器分别解析 Gitee 附件列表、验证分卷编号连续、选择匹配平台的内置工具并恢复完整 ZIP，再复用现有 SHA-256 和覆盖安装流程；GitHub 与自定义来源继续走单 ZIP 路径。

**Tech Stack:** Python `unittest`、PowerShell、POSIX shell、7-Zip CLI、GitHub/Gitee Release API

---

### Task 1: 固定内置 7-Zip 资产契约

**Files:**
- Modify: `scripts/test_release_packages.py`
- Create: `bootstrap-skill/ccrelay/assets/7zip/NOTICE.txt`
- Create: `bootstrap-skill/ccrelay/assets/7zip/SHA256SUMS`
- Create: `bootstrap-skill/ccrelay/assets/7zip/windows-x64/7zr.exe`
- Create: `bootstrap-skill/ccrelay/assets/7zip/linux-x64/7zz`
- Create: `bootstrap-skill/ccrelay/assets/7zip/macos-x64/7zz`
- Create: `bootstrap-skill/ccrelay/assets/7zip/macos-arm64/7zz`

- [ ] **Step 1: 写入失败测试**

在 `ReleasePackageTest` 中增加 `test_bootstrap_bundles_verified_7zip_tools`：读取 `SHA256SUMS`，断言四个平台文件均存在且 `hashlib.sha256` 与清单一致；断言 `NOTICE.txt` 包含 `7-Zip`、`GNU LGPL` 和 `https://www.7-zip.org/`。

- [ ] **Step 2: 运行测试并确认因资产缺失而失败**

Run: `python -m unittest scripts.test_release_packages.ReleasePackageTest.test_bootstrap_bundles_verified_7zip_tools -v`

Expected: FAIL，指出 `bootstrap-skill/ccrelay/assets/7zip/SHA256SUMS` 不存在。

- [ ] **Step 3: 下载并固定官方工具**

从 7-Zip 官方发布下载对应平台控制台包，提取独立 CLI 到上述路径；写入精确 SHA-256 清单。`NOTICE.txt` 说明项目随附 7-Zip 部分、许可证构成以及官方源码链接。不得提交安装器或无关文件。

- [ ] **Step 4: 运行资产测试**

Run: `python -m unittest scripts.test_release_packages.ReleasePackageTest.test_bootstrap_bundles_verified_7zip_tools -v`

Expected: PASS。

- [ ] **Step 5: 提交**

```powershell
git add scripts/test_release_packages.py bootstrap-skill/ccrelay/assets/7zip
git commit -m "build: bundle verified 7zip bootstrap tools"
```

### Task 2: 锁定引导 Skill 和安装器分卷契约

**Files:**
- Modify: `scripts/test_release_packages.py`
- Modify: `bootstrap-skill/ccrelay/SKILL.md`

- [ ] **Step 1: 写入失败测试**

增加 `test_bootstrap_documents_and_detects_gitee_split_archives`，断言 Skill 同时包含 `ccrelay-full.zip.001`、`7-Zip`、Gitee 分卷恢复和 GitHub 直接 `ccrelay-full.zip`；断言两个安装器包含分卷匹配表达式、连续编号检查、内置资产路径和系统命令回退标识。

- [ ] **Step 2: 运行并确认现有 Skill/脚本不满足契约**

Run: `python -m unittest scripts.test_release_packages.ReleasePackageTest.test_bootstrap_documents_and_detects_gitee_split_archives -v`

Expected: FAIL，缺少 `.001` 或内置工具路径。

- [ ] **Step 3: 更新引导 Skill 文案**

将第 3 步改为：Gitee 下载全部连续的 `ccrelay-full.zip.NNN`，使用内置 7-Zip 从 `.001` 恢复并校验；任一步失败整体回退 GitHub，而 GitHub 直接下载完整 ZIP。明确不要求用户预装 7-Zip。

- [ ] **Step 4: 只运行文案断言并确认脚本断言仍失败**

Run: `python -m unittest scripts.test_release_packages.ReleasePackageTest.test_bootstrap_documents_and_detects_gitee_split_archives -v`

Expected: FAIL 位置推进到安装脚本契约。

### Task 3: 实现 PowerShell 分卷恢复

**Files:**
- Modify: `bootstrap-skill/ccrelay/scripts/install-latest.ps1`
- Test: `scripts/test_release_packages.py`

- [ ] **Step 1: 在测试中补齐 PowerShell 顺序断言**

断言 PowerShell 脚本先完成附件筛选和连续性验证，再调用 `Expand-GiteeSplitArchive`，最后调用统一 SHA-256 校验；GitHub `Download-Package` 仍直接请求 `ccrelay-full.zip`。

- [ ] **Step 2: 运行测试并确认失败**

Run: `python -m unittest scripts.test_release_packages.ReleasePackageTest.test_bootstrap_documents_and_detects_gitee_split_archives -v`

Expected: FAIL，缺少 `Expand-GiteeSplitArchive`。

- [ ] **Step 3: 实现最小 PowerShell 支持**

增加以下职责明确的函数：

- `Clear-DownloadedPackage`：清理完整 ZIP、校验文件、分卷目录和恢复目录。
- `Get-SevenZipCommand`：按 Windows 架构选择 `assets/7zip/windows-x64/7zr.exe`，否则查找系统命令。
- `Assert-ContinuousVolumes`：对附件名称的三位编号排序，要求从 1 开始且逐一连续。
- `Expand-GiteeSplitArchive`：调用 `7z x -y -o<dir> <path.001>` 并要求根部生成一个 `ccrelay-full.zip`。
- `Test-PackageChecksum`：统一校验完整 ZIP。

`Download-GiteePackage` 下载校验文件和每一卷，恢复后校验；catch 中调用统一清理并返回 `$false`。

- [ ] **Step 4: 运行契约测试**

Run: `python -m unittest scripts.test_release_packages.ReleasePackageTest.test_bootstrap_documents_and_detects_gitee_split_archives -v`

Expected: PowerShell 断言通过，POSIX 断言仍失败。

### Task 4: 实现 POSIX 分卷恢复

**Files:**
- Modify: `bootstrap-skill/ccrelay/scripts/install-latest.sh`
- Test: `scripts/test_release_packages.py`

- [ ] **Step 1: 在测试中补齐 POSIX 顺序断言**

断言 shell 脚本先验证卷号，再调用 `expand_gitee_split_archive`，最后调用 `verify_package_checksum`；`download_package` 保持单 ZIP 行为。

- [ ] **Step 2: 运行测试并确认失败**

Run: `python -m unittest scripts.test_release_packages.ReleasePackageTest.test_bootstrap_documents_and_detects_gitee_split_archives -v`

Expected: FAIL，缺少 `expand_gitee_split_archive`。

- [ ] **Step 3: 实现最小 POSIX 支持**

增加 `clear_downloaded_package`、`seven_zip_command`、`validate_gitee_volumes`、`expand_gitee_split_archive` 和 `verify_package_checksum`。通过 `uname -s`/`uname -m` 选择 `linux-x64`、`macos-x64` 或 `macos-arm64` 内置工具并确保可执行；不匹配时查找系统 `7z`/`7zz`。下载时以附件元数据中的 ID 和名称逐卷保存。

- [ ] **Step 4: 运行契约测试**

Run: `python -m unittest scripts.test_release_packages.ReleasePackageTest.test_bootstrap_documents_and_detects_gitee_split_archives -v`

Expected: PASS。

- [ ] **Step 5: 提交 Skill 和安装器改动**

```powershell
git add bootstrap-skill/ccrelay/SKILL.md bootstrap-skill/ccrelay/scripts scripts/test_release_packages.py
git commit -m "feat: install Gitee split full package"
```

### Task 5: 验证真实恢复和发布包内容

**Files:**
- Modify: `scripts/test_release_packages.py`

- [ ] **Step 1: 增加真实内置工具冒烟测试**

测试在当前平台有匹配内置工具时，创建包含 `ccrelay-full.zip` 的临时 7z 分卷并从 `.001` 恢复，断言字节一致；在无匹配平台时显式 skip。

- [ ] **Step 2: 运行测试并确认测试先因缺少测试辅助逻辑失败**

Run: `python -m unittest scripts.test_release_packages.ReleasePackageTest.test_bundled_7zip_restores_split_archive -v`

Expected: FAIL，直到测试所需的工具选择和命令参数完成。

- [ ] **Step 3: 完成测试辅助逻辑并运行发布测试**

Run: `python -m unittest scripts.test_release_packages -v`

Expected: 全部 PASS，无 ERROR。

- [ ] **Step 4: 构建最小示例并检查引导 ZIP**

运行现有 `test_creates_complete_and_one_time_bootstrap_packages`，并断言 ZIP 中包含四个平台工具、`NOTICE.txt`、`SHA256SUMS`、Skill 和两个安装脚本。

- [ ] **Step 5: 全量回归与静态检查**

```powershell
python -m unittest discover -s scripts -p 'test_*.py' -v
git diff --check
git status --short
```

Expected: 测试全部通过，`git diff --check` 无输出，仅包含计划内改动。

- [ ] **Step 6: 提交最终测试完善**

```powershell
git add scripts/test_release_packages.py
git commit -m "test: verify bundled split package recovery"
```
