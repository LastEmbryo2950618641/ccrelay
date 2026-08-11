#!/usr/bin/env python3
from __future__ import annotations

import subprocess
import shutil
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import install_codex_skill as installer_module


REPO_ROOT = Path(__file__).resolve().parents[1]
INSTALLER = REPO_ROOT / "scripts" / "install_codex_skill.py"
SKILL_SOURCE = REPO_ROOT / "codex-skill" / "ccrelay"


class InstallCodexSkillTest(unittest.TestCase):

    @unittest.skipUnless(os.name == "nt", "Windows long-path behavior")
    def test_uses_extended_windows_path_for_recursive_deletion(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            target = Path(temporary_directory) / "installed-skill"

            deletion_path = installer_module.deletion_path(target)

            self.assertTrue(str(deletion_path).startswith("\\\\?\\"))

    def test_uses_merge_upgrade_when_windows_rmtree_leaves_target_directory(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            target = Path(temporary_directory) / "installed-skill"
            target.mkdir()
            with patch.object(installer_module.shutil, "rmtree", side_effect=OSError(145, "directory not empty")):
                self.assertTrue(installer_module.remove_target_or_require_merge(target))

    def test_retires_legacy_directory_before_installing_new_name(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            install_root = Path(temporary_directory)
            legacy_directory = install_root / "wdsavs-ai-agent-runtime"
            legacy_directory.mkdir()
            (legacy_directory / "marker.txt").write_text("legacy", encoding="utf-8")

            retired_directory = installer_module.retire_legacy_skill(legacy_directory, install_root)

            self.assertIsNotNone(retired_directory)
            self.assertFalse(legacy_directory.exists())
            self.assertEqual("legacy", (retired_directory / "marker.txt").read_text(encoding="utf-8"))

    def test_force_upgrade_preserves_skill_local_credentials(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            codex_home = Path(temporary_directory) / ".codex"
            self.install(codex_home)
            local_directory = codex_home / "skills" / "ccrelay" / ".local"
            self.assertFalse(local_directory.exists())
            local_directory.mkdir(parents=True, exist_ok=True)
            credential_file = local_directory / "ssh-credentials.json"
            credential_file.write_text('{"version":1,"marker":"preserved"}\n', encoding="utf-8")

            self.install(codex_home)

            self.assertTrue(credential_file.is_file())
            self.assertIn("preserved", credential_file.read_text(encoding="utf-8"))

    def test_force_upgrade_migrates_legacy_skill_local_state(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            codex_home = Path(temporary_directory) / ".codex"
            legacy_directory = codex_home / "skills" / "wdsavs-ai-agent-runtime"
            local_directory = legacy_directory / ".local"
            local_directory.mkdir(parents=True)
            credential_file = local_directory / "ssh-credentials.json"
            credential_file.write_text('{"marker":"legacy"}\n', encoding="utf-8")

            output = self.install(codex_home)

            installed_directory = codex_home / "skills" / "ccrelay"
            self.assertTrue(installed_directory.is_dir())
            self.assertFalse(legacy_directory.exists())
            self.assertIn(
                "legacy",
                (installed_directory / ".local" / "ssh-credentials.json").read_text(encoding="utf-8"),
            )
            self.assertIn(f"migrated legacy skill: {legacy_directory}", output)

    def test_force_upgrade_cleans_deferred_legacy_directory(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            codex_home = Path(temporary_directory) / ".codex"
            self.install(codex_home)
            deferred_directory = codex_home / "skills" / ".ccrelay-legacy-deferred"
            deferred_directory.mkdir()
            (deferred_directory / "marker.txt").write_text("stale", encoding="utf-8")

            self.install(codex_home)

            self.assertFalse(deferred_directory.exists())

    def test_recovers_local_credentials_after_interrupted_upgrade(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            codex_home = Path(temporary_directory) / ".codex"
            self.install(codex_home)
            skill_directory = codex_home / "skills" / "ccrelay"
            local_directory = skill_directory / ".local"
            local_directory.mkdir(parents=True)
            credential_file = local_directory / "ssh-credentials.json"
            credential_file.write_text('{"marker":"recovered"}\n', encoding="utf-8")
            orphaned_root = codex_home / "skills" / ".ccrelay-upgrade-interrupted"
            shutil.copytree(local_directory, orphaned_root / ".local")
            shutil.rmtree(local_directory)

            self.install(codex_home)

            restored_file = skill_directory / ".local" / "ssh-credentials.json"
            self.assertIn("recovered", restored_file.read_text(encoding="utf-8"))
            self.assertFalse(orphaned_root.exists())

    def test_prefers_non_empty_upgrade_backup_over_empty_legacy_local_directory(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            codex_home = Path(temporary_directory) / ".codex"
            skills_directory = codex_home / "skills"
            legacy_local_directory = skills_directory / "wdsavs-ai-agent-runtime" / ".local"
            legacy_local_directory.mkdir(parents=True)
            orphaned_local_directory = skills_directory / ".ccrelay-upgrade-interrupted" / ".local"
            orphaned_local_directory.mkdir(parents=True)
            credential_file = orphaned_local_directory / "ssh-credentials.json"
            credential_file.write_text('{"marker":"upgrade-backup"}\n', encoding="utf-8")

            self.install(codex_home)

            restored_file = skills_directory / "ccrelay" / ".local" / "ssh-credentials.json"
            self.assertIn("upgrade-backup", restored_file.read_text(encoding="utf-8"))
            self.assertFalse(legacy_local_directory.parent.exists())

    def test_merges_upgrade_backup_with_current_local_state(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            codex_home = Path(temporary_directory) / ".codex"
            self.install(codex_home)
            skills_directory = codex_home / "skills"
            current_local_directory = skills_directory / "ccrelay" / ".local"
            current_local_directory.mkdir(parents=True)
            (current_local_directory / "current-state.json").write_text("current\n", encoding="utf-8")
            orphaned_local_directory = skills_directory / ".ccrelay-upgrade-interrupted" / ".local"
            orphaned_local_directory.mkdir(parents=True)
            (orphaned_local_directory / "ssh-credentials.json").write_text("backup\n", encoding="utf-8")

            self.install(codex_home)

            installed_local_directory = skills_directory / "ccrelay" / ".local"
            self.assertEqual("current\n", (installed_local_directory / "current-state.json").read_text(encoding="utf-8"))
            self.assertEqual("backup\n", (installed_local_directory / "ssh-credentials.json").read_text(encoding="utf-8"))

    @unittest.skipUnless(os.name == "nt" and shutil.which("ssh-keygen"), "Windows OpenSSH required")
    def test_force_upgrade_restores_private_key_with_openssh_compatible_acl(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            codex_home = Path(temporary_directory) / ".codex"
            self.install(codex_home)
            key_directory = codex_home / "skills" / "ccrelay" / ".local" / "keys" / "default"
            key_directory.mkdir(parents=True)
            key_path = key_directory / "cluster_ed25519"
            generated = subprocess.run(
                [shutil.which("ssh-keygen"), "-q", "-t", "ed25519", "-N", "", "-f", str(key_path)],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(0, generated.returncode, generated.stderr)

            self.install(codex_home)

            validated = subprocess.run(
                [shutil.which("ssh-keygen"), "-y", "-f", str(key_path)],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(0, validated.returncode, validated.stderr)
            self.assertTrue(validated.stdout.startswith("ssh-ed25519 "))

    def install(self, codex_home: Path):
        completed = subprocess.run(
            [sys.executable, str(INSTALLER), str(SKILL_SOURCE), "--codex-home", str(codex_home), "--force"],
            cwd=str(REPO_ROOT),
            text=True,
            encoding="utf-8",
            errors="replace",
            capture_output=True,
            check=False,
        )
        self.assertEqual(0, completed.returncode, completed.stderr)
        return completed.stdout


if __name__ == "__main__":
    unittest.main()
