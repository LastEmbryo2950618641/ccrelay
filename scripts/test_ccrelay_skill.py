#!/usr/bin/env python3

import argparse
import json
import sys
import tempfile
import threading
import unittest
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO_ROOT / "codex-skill" / "ccrelay" / "scripts"))
import ccrelay_skill


class SkillInstallHandler(BaseHTTPRequestHandler):
    uploaded_artifact = b""

    def do_POST(self):
        if self.path != "/api/skill/catalog/install":
            self.send_error(404)
            return
        length = int(self.headers.get("Content-Length", "0"))
        self.__class__.uploaded_artifact = self.rfile.read(length)
        response = json.dumps({
            "skillId": "gugugaga-roleplay",
            "sha256": "center-sha256",
            "status": "ACTIVE",
        }).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(response)))
        self.end_headers()
        self.wfile.write(response)

    def log_message(self, format, *args):
        return


class CcRelaySkillTest(unittest.TestCase):

    def test_repository_role_skill_uploads_through_cli_install_flow(self):
        skill = REPO_ROOT / "example-skills" / "gugugaga-roleplay"
        SkillInstallHandler.uploaded_artifact = b""
        server = ThreadingHTTPServer(("127.0.0.1", 0), SkillInstallHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            args = argparse.Namespace(
                path=str(skill),
                center=f"http://127.0.0.1:{server.server_port}",
                timeout=30,
            )
            response = ccrelay_skill.install_skill(args)
            self.assertEqual("gugugaga-roleplay", response["skillId"])
            self.assertEqual("ACTIVE", response["status"])
            self.assertEqual(ccrelay_skill.canonical_sha256(skill), response["localSha256"])
            self.assertTrue(SkillInstallHandler.uploaded_artifact)

            with tempfile.TemporaryDirectory() as temporary:
                archive = Path(temporary) / "uploaded.zip"
                archive.write_bytes(SkillInstallHandler.uploaded_artifact)
                extracted = ccrelay_skill.extract_zip(archive, Path(temporary) / "extracted")
                self.assertEqual(
                    ccrelay_skill.canonical_sha256(skill),
                    ccrelay_skill.canonical_sha256(extracted),
                )
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)

    def test_repository_role_skill_round_trips_through_standard_package(self):
        skill = REPO_ROOT / "example-skills" / "gugugaga-roleplay"
        ccrelay_skill.validate_skill_root(skill)
        self.assertEqual("gugugaga-roleplay", ccrelay_skill.read_skill_id(skill))
        expected_files = {
            "SKILL.md",
            "agents/openai.yaml",
            "roles/咕咕嘎嘎/性格.md",
            "roles/咕咕嘎嘎/对话例子.md",
            "roles/咕咕嘎嘎/背景设定.md",
            "roles/咕咕嘎嘎/知识.md",
        }
        self.assertEqual(expected_files, {name for name, _ in ccrelay_skill.skill_files(skill)})
        self.assertNotIn("TODO", (skill / "SKILL.md").read_text(encoding="utf-8"))

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "gugugaga-roleplay.zip"
            ccrelay_skill.create_deterministic_zip(skill, archive)
            extracted = ccrelay_skill.extract_zip(archive, root / "extracted")
            self.assertEqual(
                ccrelay_skill.canonical_sha256(skill),
                ccrelay_skill.canonical_sha256(extracted),
            )

    def test_canonical_sha_and_zip_are_deterministic(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            first = self.create_skill(root / "first", reverse=False)
            second = self.create_skill(root / "second", reverse=True)

            self.assertEqual(ccrelay_skill.canonical_sha256(first), ccrelay_skill.canonical_sha256(second))
            first_zip = root / "first.zip"
            second_zip = root / "second.zip"
            ccrelay_skill.create_deterministic_zip(first, first_zip)
            ccrelay_skill.create_deterministic_zip(second, second_zip)
            self.assertEqual(first_zip.read_bytes(), second_zip.read_bytes())

    def test_local_and_cache_directories_are_not_packaged(self):
        with tempfile.TemporaryDirectory() as temporary:
            skill = self.create_skill(Path(temporary) / "skill", reverse=False)
            (skill / ".local").mkdir()
            (skill / ".local" / "secret.txt").write_text("secret", encoding="utf-8")
            (skill / "__pycache__").mkdir()
            (skill / "__pycache__" / "cache.pyc").write_bytes(b"cache")

            names = [name for name, _ in ccrelay_skill.skill_files(skill)]
            self.assertNotIn(".local/secret.txt", names)
            self.assertNotIn("__pycache__/cache.pyc", names)

    def test_relative_path_segments_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "invalid.zip"
            with zipfile.ZipFile(archive, "w") as package:
                package.writestr("nested/../SKILL.md", "---\nname: sample-skill\n---\n")

            with self.assertRaises(ccrelay_skill.SkillError):
                ccrelay_skill.extract_zip(archive, root / "extracted")

    def create_skill(self, root: Path, reverse: bool) -> Path:
        (root / "references").mkdir(parents=True)
        files = [
            (root / "SKILL.md", "---\nname: sample-skill\ndescription: sample\n---\n\n# Sample\n"),
            (root / "references" / "knowledge.md", "knowledge"),
        ]
        if reverse:
            files.reverse()
        for path, text in files:
            path.write_text(text, encoding="utf-8")
        return root


if __name__ == "__main__":
    unittest.main()
