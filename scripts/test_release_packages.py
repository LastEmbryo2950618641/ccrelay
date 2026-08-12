from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

import package_skill_release
import publish_gitee_release


class ReleasePackageTest(unittest.TestCase):

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
        self.assertIn("python scripts/publish_gitee_release.py", workflow)
        self.assertIn("GITEE_TOKEN: ${{ secrets.GITEE_TOKEN }}", workflow)
        self.assertIn('--tag "v$version"', workflow)
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

    def test_gitee_publish_replaces_release_assets(self):
        with tempfile.TemporaryDirectory() as temporary:
            release_dir = Path(temporary)
            for name in publish_gitee_release.ASSET_NAMES:
                self.write(release_dir / name, name)
            client = mock.Mock()
            client.owner = "owner"
            client.repo = "repo"
            client.release_by_tag.return_value = {"id": 42}
            client.request.side_effect = [
                {"id": 42},
                [{"id": 7, "name": "ccrelay-full.zip"}, {"id": 8, "name": "keep.txt"}],
                None,
                [{"name": name} for name in publish_gitee_release.ASSET_NAMES],
            ]
            client.upload.side_effect = lambda _release_id, path: {
                "name": path.name,
                "size": path.stat().st_size,
            }
            args = mock.Mock(
                owner="owner",
                repo="repo",
                release_dir=str(release_dir),
                target_commitish="v0.1.1",
                tag="latest",
                name="Latest CC Relay build",
            )
            with mock.patch.dict("os.environ", {"GITEE_TOKEN": "test-token"}), mock.patch.object(
                publish_gitee_release, "GiteeClient", return_value=client
            ):
                result = publish_gitee_release.publish(args)

            self.assertEqual(42, result["releaseId"])
            self.assertEqual(5, len(result["assets"]))
            delete_calls = [call for call in client.request.call_args_list if call.args[0] == "DELETE"]
            self.assertEqual(1, len(delete_calls))
            self.assertIn("/attach_files/7", delete_calls[0].args[1])

    def assert_checksum(self, archive: Path) -> None:
        expected = (archive.with_name(archive.name + ".sha256").read_text(encoding="ascii").split()[0])
        self.assertEqual(hashlib.sha256(archive.read_bytes()).hexdigest(), expected)

    def write(self, path: Path, content: str) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
