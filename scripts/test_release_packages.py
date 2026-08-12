from __future__ import annotations

import hashlib
import json
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
            "windows-x64/7zr.exe",
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

        notice = notice_path.read_text(encoding="utf-8")
        self.assertIn("7-Zip", notice)
        self.assertIn("GNU LGPL", notice)
        self.assertIn("https://www.7-zip.org/", notice)

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
        self.assertIn("attach_files/$archive_id/download", shell_bootstrap)
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
                self.assertEqual({
                    "ccrelay/SKILL.md",
                    "ccrelay/scripts/install-latest.ps1",
                    "ccrelay/scripts/install-latest.sh",
                }, names)
                self.assertNotIn("ccrelay/assets/runtime-bundle/ccrelay/app.jar", names)
            self.assert_checksum(release / "ccrelay-full.zip")
            self.assert_checksum(release / "ccrelay-bootstrap.zip")
            stored_manifest = json.loads((release / "release-manifest.json").read_text(encoding="utf-8"))
            self.assertEqual("0.1.1", stored_manifest["version"])

    def assert_checksum(self, archive: Path) -> None:
        expected = (archive.with_name(archive.name + ".sha256").read_text(encoding="ascii").split()[0])
        self.assertEqual(hashlib.sha256(archive.read_bytes()).hexdigest(), expected)

    def write(self, path: Path, content: str) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")


if __name__ == "__main__":
    unittest.main()

