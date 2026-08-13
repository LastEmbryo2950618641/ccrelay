from __future__ import annotations

import hashlib
import json
import os
import platform
import shutil
import struct
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

import package_skill_release


class ReleasePackageTest(unittest.TestCase):

    def test_bootstrap_bundles_verified_7zip_tools(self):
        repository = Path(__file__).resolve().parents[1]
        seven_zip_root = repository / "bootstrap-skill/ccrelay/assets/7zip"
        checksum_path = seven_zip_root / "SHA256SUMS"
        notice_path = seven_zip_root / "NOTICE.txt"
        expected_tools = {
            "windows-x64/7za.exe",
            "linux-x64/7zz",
            "macos-x64/7zz",
            "macos-arm64/7zz",
        }

        checksums = {}
        for line in checksum_path.read_text(encoding="ascii").splitlines():
            digest, relative = line.split(maxsplit=1)
            checksums[relative.lstrip("* ")] = digest.lower()

        self.assertEqual(expected_tools, set(checksums))
        for relative, expected_digest in checksums.items():
            tool = seven_zip_root / relative
            self.assertTrue(tool.is_file(), f"Bundled 7-Zip tool is missing: {relative}")
            self.assertEqual(expected_digest, hashlib.sha256(tool.read_bytes()).hexdigest())
        windows_tool = seven_zip_root / "windows-x64/7za.exe"
        windows_bytes = windows_tool.read_bytes()
        pe_offset = struct.unpack_from("<I", windows_bytes, 0x3C)[0]
        machine = struct.unpack_from("<H", windows_bytes, pe_offset + 4)[0]
        self.assertEqual(0x8664, machine)

        notice = notice_path.read_text(encoding="utf-8")
        self.assertIn("7-Zip", notice)
        self.assertIn("GNU LGPL", notice)
        self.assertIn("https://www.7-zip.org/", notice)

    def test_bootstrap_documents_and_detects_gitee_split_archives(self):
        repository = Path(__file__).resolve().parents[1]
        skill = (repository / "bootstrap-skill/ccrelay/SKILL.md").read_text(encoding="utf-8")
        windows = (repository / "bootstrap-skill/ccrelay/scripts/install-latest.ps1").read_text(encoding="utf-8")
        shell = (repository / "bootstrap-skill/ccrelay/scripts/install-latest.sh").read_text(encoding="utf-8")

        for phrase in ("ccrelay-full.zip.001", "7-Zip", "Gitee", "GitHub", "完整"):
            self.assertIn(phrase, skill)
        self.assertIn("GitHub", skill[skill.index("ccrelay-full.zip.001"):])

        for marker in (
            "Assert-ContinuousVolumes",
            "Expand-GiteeSplitArchive",
            "Expand-GiteeOuterArchive",
            "Get-GiteeOuterArchiveEntry",
            "Test-PackageChecksum",
            "assets\\7zip\\windows-x64\\7za.exe",
            "ccrelay-full.zip.001",
            "-tSplit",
            "7z.exe",
            "7zz.exe",
            "--progress-bar",
            "Downloading $DisplayName",
            "Restoring the complete package from Gitee split volumes",
            "Verifying CC Relay package SHA-256",
        ):
            self.assertIn(marker, windows)
        self.assertLess(windows.index("Assert-ContinuousVolumes"), windows.index("Expand-GiteeSplitArchive"))
        self.assertLess(windows.index("Expand-GiteeSplitArchive"), windows.index("Expand-GiteeOuterArchive"))
        self.assertLess(windows.index("Expand-GiteeOuterArchive"), windows.rindex("Test-PackageChecksum"))
        self.assertIn('"$downloadBase/ccrelay-full.zip"', windows)

        for marker in (
            "validate_gitee_volumes",
            "expand_gitee_split_archive",
            "expand_gitee_outer_archive",
            "validate_gitee_outer_archive",
            "verify_package_checksum",
            "assets/7zip/linux-x64/7zz",
            "assets/7zip/macos-x64/7zz",
            "assets/7zip/macos-arm64/7zz",
            "ccrelay-full.zip.001",
            "-tSplit",
            "command -v 7z",
            "command -v 7zz",
            "--progress-bar",
            "Downloading %s (attempt %d/3)",
            "Restoring the complete package from Gitee split volumes",
            "Verifying CC Relay package SHA-256",
        ):
            self.assertIn(marker, shell)
        self.assertLess(shell.index("validate_gitee_volumes"), shell.index("expand_gitee_split_archive"))
        self.assertLess(shell.index("expand_gitee_split_archive"), shell.index("expand_gitee_outer_archive"))
        self.assertLess(shell.index("expand_gitee_outer_archive"), shell.rindex("verify_package_checksum"))
        self.assertIn('"$base_url/ccrelay-full.zip"', shell)

    @unittest.skipUnless(os.name == "nt", "Windows PowerShell compatibility test")
    def test_windows_powershell_expands_gitee_attachment_array(self):
        repository = Path(__file__).resolve().parents[1]
        script = (repository / "bootstrap-skill/ccrelay/scripts/install-latest.ps1").read_text(encoding="utf-8")
        expected_expression = (
            "$attachments = @(Get-Content -Raw $attachmentMetadata | "
            "ConvertFrom-Json | ForEach-Object { $_ })"
        )
        self.assertIn(expected_expression, script)

        fixture = json.dumps([
            {"id": 1, "name": "ccrelay-full.zip.sha256"},
            {"id": 2, "name": "ccrelay-full.zip.001"},
            {"id": 3, "name": "ccrelay-full.zip.002"},
            {"id": 4, "name": "ccrelay-full.zip.003"},
            {"id": 5, "name": "ccrelay-full.zip.004"},
        ])
        with tempfile.TemporaryDirectory() as temporary:
            metadata = Path(temporary) / "attachments.json"
            metadata.write_text(fixture, encoding="utf-8")
            command = (
                f"$attachments = @(Get-Content -Raw '{metadata}' | ConvertFrom-Json | ForEach-Object {{ $_ }}); "
                "$volumes = @($attachments | Where-Object { $_.name -match "
                "'^ccrelay-full\\.zip\\.(\\d{3})$' }); "
                "Write-Output ($attachments.Count.ToString() + ',' + $volumes.Count.ToString())"
            )
            result = subprocess.run(
                ["powershell.exe", "-NoProfile", "-Command", command],
                check=True,
                capture_output=True,
                text=True,
            )
        self.assertEqual("5,4", result.stdout.strip())

    def test_bootstrap_declares_supported_powershell_versions(self):
        repository = Path(__file__).resolve().parents[1]
        script = (repository / "bootstrap-skill/ccrelay/scripts/install-latest.ps1").read_text(encoding="utf-8")
        skill = (repository / "bootstrap-skill/ccrelay/SKILL.md").read_text(encoding="utf-8")
        readme = (repository / "README.md").read_text(encoding="utf-8")

        self.assertIn("$isWindowsPowerShell51", script)
        self.assertIn("$isPowerShell7OrLater", script)
        self.assertIn("$powerShellVersion.Major -ge 7", script)
        self.assertIn("Use Windows PowerShell 5.1 or PowerShell 7 or later", script)
        for document in (skill, readme):
            self.assertIn("Windows PowerShell 5.1", document)
            self.assertIn("PowerShell 7", document)

    def test_gitee_build_makes_gradle_wrapper_executable_before_detecting_it(self):
        repository = Path(__file__).resolve().parents[1]
        build_script = (repository / "scripts/gitee_go_build.sh").read_text(encoding="utf-8")

        self.assertLess(
            build_script.index('chmod +x "$REPO_ROOT/gradlew"'),
            build_script.index("ensure_gradle_command\n"),
        )

    def test_gradle_wrapper_uses_china_accessible_distribution_mirror(self):
        repository = Path(__file__).resolve().parents[1]
        wrapper_properties = (repository / "gradle/wrapper/gradle-wrapper.properties").read_text(encoding="utf-8")

        self.assertIn(
            "distributionUrl=https\\://mirrors.cloud.tencent.com/gradle/gradle-8.5-bin.zip",
            wrapper_properties,
        )

    def test_gitee_build_retries_gradle_after_transient_download_failure(self):
        repository = Path(__file__).resolve().parents[1]
        build_script = (repository / "scripts/gitee_go_build.sh").read_text(encoding="utf-8")

        self.assertIn("run_gradle()", build_script)
        self.assertIn('run_gradle test', build_script)
        self.assertIn('run_gradle bootJar', build_script)

    def test_local_and_github_builds_share_the_same_packager(self):
        repository = Path(__file__).resolve().parents[1]
        powershell_build = (repository / "build.ps1").read_text(encoding="utf-8")
        shell_build = (repository / "build.sh").read_text(encoding="utf-8")
        workflow = (repository / ".github/workflows/build-skill-zip.yml").read_text(encoding="utf-8")

        self.assertIn("scripts/package_skill_release.py", powershell_build.replace("\\", "/"))
        self.assertIn("scripts/package_skill_release.py", shell_build)
        self.assertIn("run: bash build.sh", workflow)
        self.assertNotIn('tags:\n      - "v*"', workflow)
        self.assertNotIn('      - "v*"', workflow)
        self.assertIn("if: github.event_name == 'workflow_dispatch'", workflow)
        self.assertIn('release_tag="v$version"', workflow)
        self.assertIn('gh release edit "$release_tag" --latest', workflow)
        windows_bootstrap = (repository / "bootstrap-skill/ccrelay/scripts/install-latest.ps1").read_text(encoding="utf-8")
        shell_bootstrap = (repository / "bootstrap-skill/ccrelay/scripts/install-latest.sh").read_text(encoding="utf-8")
        self.assertLess(windows_bootstrap.index("gitee.com"), windows_bootstrap.index("github.com"))
        self.assertLess(shell_bootstrap.index("gitee.com"), shell_bootstrap.index("github.com"))
        self.assertIn("ConvertFrom-Json", windows_bootstrap)
        self.assertIn("download_gitee_package", shell_bootstrap)
        self.assertIn("attach_files/$volume_id/download", shell_bootstrap)
        self.assertIn("Invoke-WebRequest", windows_bootstrap)
        self.assertIn("attempt = 1", windows_bootstrap)
        self.assertIn('attempt=1', shell_bootstrap)
        for archive in ("ccrelay-full.zip", "ccrelay-bootstrap.zip"):
            self.assertIn(archive, powershell_build)
            self.assertIn(archive, shell_build)
            self.assertIn(archive, workflow)

    def test_creates_complete_and_one_time_bootstrap_packages(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            full = root / "full"
            bootstrap = root / "bootstrap"
            release = root / "release"
            self.write(full / "SKILL.md", "---\nname: ccrelay\n---\nfull\n")
            self.write(full / "assets/runtime-bundle/ccrelay/app.jar", "jar")
            self.write(full / "scripts/ccrelay-cli.py", "print('ok')\n")
            self.write(full / ".local/secret.txt", "secret")
            self.write(bootstrap / "SKILL.md", "---\nname: ccrelay\n---\nbootstrap\n")
            self.write(bootstrap / "scripts/install-latest.ps1", "Write-Output ok\n")
            self.write(bootstrap / "scripts/install-latest.sh", "#!/usr/bin/env sh\n")
            bundled_assets = Path(__file__).resolve().parents[1] / "bootstrap-skill/ccrelay/assets"
            shutil.copytree(bundled_assets, bootstrap / "assets")

            manifest = package_skill_release.package_release(full, bootstrap, release, "0.1.1")

            self.assertEqual("ccrelay-full.zip", manifest["fullArchive"])
            self.assertEqual("ccrelay-bootstrap.zip", manifest["bootstrapArchive"])
            with zipfile.ZipFile(release / "ccrelay-full.zip") as package:
                names = set(package.namelist())
                self.assertIn("ccrelay/SKILL.md", names)
                self.assertIn("ccrelay/assets/runtime-bundle/ccrelay/app.jar", names)
                self.assertNotIn("ccrelay/.local/secret.txt", names)
            with zipfile.ZipFile(release / "ccrelay-bootstrap.zip") as package:
                names = set(package.namelist())
                self.assertTrue({
                    "ccrelay/SKILL.md",
                    "ccrelay/scripts/install-latest.ps1",
                    "ccrelay/scripts/install-latest.sh",
                    "ccrelay/assets/7zip/NOTICE.txt",
                    "ccrelay/assets/7zip/SHA256SUMS",
                    "ccrelay/assets/7zip/windows-x64/7za.exe",
                    "ccrelay/assets/7zip/linux-x64/7zz",
                    "ccrelay/assets/7zip/macos-x64/7zz",
                    "ccrelay/assets/7zip/macos-arm64/7zz",
                }.issubset(names))
                self.assertNotIn("ccrelay/assets/runtime-bundle/ccrelay/app.jar", names)
            self.assert_checksum(release / "ccrelay-full.zip")
            self.assert_checksum(release / "ccrelay-bootstrap.zip")
            stored_manifest = json.loads((release / "release-manifest.json").read_text(encoding="utf-8"))
            self.assertEqual("0.1.1", stored_manifest["version"])

    def test_bundled_7zip_restores_split_archive(self):
        repository = Path(__file__).resolve().parents[1]
        machine = platform.machine().lower()
        if os.name == "nt" and machine in {"amd64", "x86_64"}:
            seven_zip = repository / "bootstrap-skill/ccrelay/assets/7zip/windows-x64/7za.exe"
        elif platform.system() == "Linux" and machine in {"amd64", "x86_64"}:
            seven_zip = repository / "bootstrap-skill/ccrelay/assets/7zip/linux-x64/7zz"
            seven_zip.chmod(seven_zip.stat().st_mode | 0o111)
        elif platform.system() == "Darwin" and machine in {"amd64", "x86_64", "arm64", "aarch64"}:
            directory = "macos-arm64" if machine in {"arm64", "aarch64"} else "macos-x64"
            seven_zip = repository / f"bootstrap-skill/ccrelay/assets/7zip/{directory}/7zz"
            seven_zip.chmod(seven_zip.stat().st_mode | 0o111)
        else:
            self.skipTest("No bundled 7-Zip tool for this platform")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source"
            volumes = root / "volumes"
            restored = root / "restored"
            source.mkdir()
            volumes.mkdir()
            restored.mkdir()
            inner_archive = source / "ccrelay-full.zip"
            with zipfile.ZipFile(inner_archive, "w") as package:
                package.writestr("ccrelay/SKILL.md", "skill")
            inner_digest = hashlib.sha256(inner_archive.read_bytes()).hexdigest()
            outer_archive = root / "outer.zip"
            with zipfile.ZipFile(outer_archive, "w", zipfile.ZIP_DEFLATED) as package:
                package.write(inner_archive, "ccrelay-full.zip")
            outer_bytes = outer_archive.read_bytes()
            for index, offset in enumerate(range(0, len(outer_bytes), 1024), start=1):
                (volumes / f"ccrelay-full.zip.{index:03d}").write_bytes(outer_bytes[offset:offset + 1024])
            first_volume = volumes / "ccrelay-full.zip.001"
            self.assertTrue(first_volume.is_file())
            subprocess.run(
                [str(seven_zip), "x", "-tSplit", "-y", f"-o{restored}", str(first_volume)],
                check=True,
                capture_output=True,
            )

            restored_outer = restored / "ccrelay-full.zip"
            extracted = root / "extracted"
            extracted.mkdir()
            with zipfile.ZipFile(restored_outer) as package:
                self.assertEqual(["ccrelay-full.zip"], package.namelist())
                package.extractall(extracted)
            restored_inner = extracted / "ccrelay-full.zip"
            self.assertEqual(inner_digest, hashlib.sha256(restored_inner.read_bytes()).hexdigest())

    def assert_checksum(self, archive: Path) -> None:
        expected = (archive.with_name(archive.name + ".sha256").read_text(encoding="ascii").split()[0])
        self.assertEqual(hashlib.sha256(archive.read_bytes()).hexdigest(), expected)

    def write(self, path: Path, content: str) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")


if __name__ == "__main__":
    unittest.main()

