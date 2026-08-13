#!/usr/bin/env python3
import argparse
import base64
import hashlib
import hmac
import importlib.util
import io
import json
import os
import subprocess
import sys
import tarfile
import tempfile
import threading
import time
import unittest
import zipfile
from contextlib import ExitStack, redirect_stdout
from unittest.mock import call, patch
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path, PureWindowsPath
from urllib.parse import parse_qs, urlparse


REPO_ROOT = Path(__file__).resolve().parents[1]
CLI = REPO_ROOT / "codex-skill" / "ccrelay" / "scripts" / "ccrelay-cli.py"
sys.path.insert(0, str(CLI.parent))
import ccrelay_ssh
import ccrelay_identity
import ccrelay_center
import ccrelay_bootstrap
CLI_SPEC = importlib.util.spec_from_file_location("ccrelay_cli_module", CLI)
ccrelay_cli = importlib.util.module_from_spec(CLI_SPEC)
CLI_SPEC.loader.exec_module(ccrelay_cli)
PREPARE_CC_CONFIG = REPO_ROOT / "codex-skill" / "ccrelay" / "scripts" / "prepare_cc_config.py"
PREPARE_SPEC = importlib.util.spec_from_file_location("prepare_cc_config_module", PREPARE_CC_CONFIG)
prepare_cc_config = importlib.util.module_from_spec(PREPARE_SPEC)
PREPARE_SPEC.loader.exec_module(prepare_cc_config)
INSTALL_RELAY = REPO_ROOT / "scripts" / "install-relay.sh"
INSTALL_RELAY_WINDOWS = REPO_ROOT / "scripts" / "install-relay.ps1"
BUILD_RUNTIME_BUNDLE = REPO_ROOT / "scripts" / "build_runtime_bundle.ps1"
SECRET = "wdsavs-ai-agent-center-dev-secret"


class RecordingHandler(BaseHTTPRequestHandler):
    records = []
    task_get_count = 0

    def do_GET(self):
        self.__class__.records.append({"method": "GET", "path": self.path, "body": None})
        path_only = urlparse(self.path).path
        query = parse_qs(urlparse(self.path).query)
        if path_only == "/api/skill/health":
            self.write_json({"status": "UP"})
        elif path_only.startswith("/api/skill/config/history/"):
            self.write_json(
                [
                    {
                        "key": path_only.rsplit("/", 1)[-1],
                        "action": "UPDATED",
                        "eventType": "CONFIG_UPDATED",
                        "decision": "ALLOW",
                        "version": 2,
                        "updateTime": "1785239900000",
                        "operatorId": "liuqi",
                        "detail": {"key": "sample", "newValueMasked": "***"},
                    }
                ]
            )
        elif path_only == "/api/skill/config":
            self.write_json(
                [
                    {
                        "key": "wdsavs.ai.observation.default-limit",
                        "value": "60",
                        "maskedValue": "60",
                        "secret": False,
                    }
                ]
            )
        elif path_only.startswith("/api/skill/config/"):
            key = path_only.rsplit("/", 1)[-1]
            if key == "wdsavs.ai.relay.hmac-secret":
                self.write_json(
                    {
                        "key": key,
                        "secret": True,
                        "value": "should-not-leak",
                        "maskedValue": "********",
                        "fingerprint": "fp-secret",
                        "dynamic": True,
                        "restartRequired": False,
                    }
                )
            else:
                self.write_json(
                    {
                        "key": key,
                        "value": "60",
                        "maskedValue": "60",
                        "fingerprint": "fp-60",
                        "dynamic": True,
                        "restartRequired": False,
                        "secret": False,
                    }
                )
        elif path_only == "/api/skill/relay/heartbeat/scan":
            self.write_json({"nodeStatuses": {"node-b:18091": "AVAILABLE"}, "availableCount": 1})
        elif path_only == "/api/skill/relay/access/grant-auto":
            self.write_json(
                {
                    "grantId": "grant-auto",
                    "sessionId": "session-auto",
                    "sourceNodeId": "node-a:18091",
                    "targetNodeId": "node-b:18091",
                    "expiresAt": "1785239900000",
                    "allowedCapabilities": [
                        "A2A_MESSAGE_SEND",
                        "A2A_TASK_CREATE",
                    ],
                }
            )
        elif path_only == "/api/skill/ssh/center-key":
            self.write_json(
                {
                    "algorithm": "ssh-ed25519",
                    "publicKey": "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAITestCenterKey ccrelay-center",
                    "fingerprint": "SHA256:test-center-key",
                }
            )
        elif path_only == "/api/skill/relay/nodes/node-b%3A18091":
            self.write_json({"nodeId": "node-b:18091", "relayEndpoint": "http://node-b:18091/relay"})
        elif path_only == "/api/skill/relay/nodes/node-c%3A18091":
            self.write_json({"nodeId": "node-c:18091", "relayEndpoint": "http://node-c:18091/relay"})
        elif path_only.startswith("/api/skill/session/") and path_only.endswith("/collaboration"):
            self.write_json(
                {
                    "sessionId": path_only.split("/api/skill/session/", 1)[1].split("/", 1)[0],
                    "collaborationMode": "DISCUSSION",
                    "coordinatorNodeId": "node-b:18091",
                    "coordinatorEpoch": 1,
                    "participantNodeIds": ["node-b:18091", "node-c:18091"],
                    "collaborationPolicy": {},
                }
            )
        elif path_only.startswith("/api/skill/session/") and path_only.endswith("/context/delta"):
            self.write_json({"sessionId": "session-agent", "afterCursor": 0, "events": [], "headCursor": 0})
        elif path_only.startswith("/api/skill/a2a/tasks/"):
            self.__class__.task_get_count += 1
            task_id = path_only.split("/api/skill/a2a/tasks/", 1)[1]
            self.write_json({"result": {"taskId": task_id, "status": "RUNNING"}})
        elif path_only.startswith("/api/skill/observations/tasks/"):
            task_id = path_only.split("/api/skill/observations/tasks/", 1)[1].split("/", 1)[0]
            self.write_json(
                {
                    "taskId": task_id,
                    "status": "RUNNING",
                    "observationSource": "CENTER_LOCAL",
                    "events": [{"eventType": "STEP_STARTED", "sequenceNo": 1}],
                    "window": {
                        "sinceSequenceNo": 7,
                        "sinceCreatedTimeMs": 1785230000000,
                        "lastMs": 9000,
                        "limit": 9,
                        "tailLines": 4,
                        "maxBytes": 1024,
                        "perEventMaxBytes": 256,
                        "returnedCount": 1,
                        "matchedCount": 1,
                        "truncated": False,
                    },
                }
            )
        elif path_only == "/api/skill/observations/tasks":
            self.write_json(
                {
                    "parentTaskId": query.get("parentTaskId", [None])[0],
                    "truncated": False,
                    "summary": {"total": 2, "running": 1, "success": 1, "failed": 0, "cancelled": 0, "timeout": 0, "unreachable": 0},
                    "observations": [
                        {"taskId": "task-a", "status": "RUNNING", "observationSource": "CENTER_LOCAL"},
                        {"taskId": "task-b", "status": "SUCCESS", "observationSource": "CENTER_LOCAL"},
                    ],
                }
            )
        else:
            self.write_json({"path": self.path})

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", "0") or "0")).decode("utf-8")
        parsed = json.loads(body) if body else None
        self.__class__.records.append({"method": "POST", "path": self.path, "body": parsed})
        path_only = urlparse(self.path).path
        if path_only == "/api/skill/session/open":
            self.write_json({"sessionId": "session-agent"})
        elif path_only.startswith("/api/skill/session/") and path_only.endswith("/collaboration/initialize"):
            participants = parsed.get("participantNodeIds") or []
            self.write_json(
                {
                    "sessionId": path_only.split("/api/skill/session/", 1)[1].split("/", 1)[0],
                    "collaborationMode": parsed.get("collaborationMode"),
                    "coordinatorNodeId": participants[0] if participants else None,
                    "coordinatorEpoch": 1,
                    "participantNodeIds": participants,
                    "collaborationPolicy": parsed.get("collaborationPolicy") or {},
                }
            )
        elif path_only == "/api/skill/relay/access/request":
            self.write_json(
                {
                    "decision": "ALLOW",
                    "grantId": "grant-agent",
                    "sessionId": parsed.get("sessionId"),
                    "sourceNodeId": parsed.get("sourceNodeId"),
                    "targetNodeId": parsed.get("targetNodeId"),
                    "signedToken": "token-agent",
                    "expiresAt": "1785239900000",
                    "allowedCapabilities": parsed.get("requiredCapabilities"),
                }
            )
        elif path_only == "/api/skill/a2a/message/send":
            self.write_json({"jsonrpc": "2.0", "result": {"status": "SUCCESS", "answer": "ok"}, "error": None})
        elif path_only == "/api/skill/a2a/tasks/create":
            task_id = parsed.get("params", {}).get("taskId") or f"task-{len(self.__class__.records)}"
            self.write_json({"jsonrpc": "2.0", "result": {"taskId": task_id, "status": "ACCEPTED"}, "error": None})
        elif path_only.startswith("/api/skill/a2a/tasks/") and path_only.endswith("/cancel"):
            self.write_json({"result": {"status": "CANCELLED"}})
        elif path_only.startswith("/api/skill/session/") and path_only.endswith("/close"):
            self.write_json({"closed": True})
        elif path_only == "/api/skill/config":
            self.write_json(
                {
                    "key": parsed.get("key"),
                    "value": parsed.get("value"),
                    "maskedValue": "***",
                    "fingerprint": "fp-set",
                    "secret": False,
                    "dynamic": True,
                }
            )
        elif path_only == "/api/skill/config/secret":
            self.write_json(
                {
                    "key": parsed.get("key"),
                    "value": None,
                    "maskedValue": "********",
                    "fingerprint": "fp-secret",
                    "secret": True,
                    "dynamic": True,
                }
            )
        elif path_only == "/api/skill/config/unset":
            self.write_json({"key": parsed.get("key"), "value": None, "secret": False})
        elif path_only == "/api/skill/config/reload":
            self.write_json({"reloaded": True, "itemCount": 2, "updateTime": "1785239900000"})
        elif path_only == "/api/skill/ssh/preflight":
            self.write_json(
                {
                    "success": True,
                    "status": "READY",
                    "host": parsed.get("host"),
                    "port": parsed.get("port"),
                    "username": parsed.get("username"),
                }
            )
        elif path_only.startswith("/api/skill/relay/deploy/") and path_only.endswith("/resume"):
            self.write_json(True)
        else:
            self.write_json({"accepted": True, "echo": parsed})

    def log_message(self, *_args):
        return

    def write_json(self, payload):
        data = json.dumps(payload).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


class CcRelayCliTest(unittest.TestCase):
    def test_bundled_jre_is_extracted_once_for_concurrent_local_calls(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            skill_root = Path(temporary_directory)
            bundle = skill_root / "assets" / "runtime-bundle" / "ccrelay"
            source = skill_root / "source-jre"
            java_name = "java.exe" if os.name == "nt" else "java"
            java_source = source / "bin" / java_name
            java_source.parent.mkdir(parents=True)
            java_source.write_bytes(b"java")
            if os.name == "nt":
                archive = bundle / "runtime-windows.zip"
                archive.parent.mkdir(parents=True)
                with zipfile.ZipFile(archive, "w") as package:
                    package.write(java_source, f"jdk-21/bin/{java_name}")
            else:
                archive = bundle / "runtime.tar.gz"
                archive.parent.mkdir(parents=True)
                with tarfile.open(archive, "w:gz") as package:
                    package.add(source, arcname="jdk-21")

            results = []
            failures = []
            barrier = threading.Barrier(4)

            def resolve_runtime():
                try:
                    barrier.wait()
                    results.append(ccrelay_cli._ensure_local_jre(bundle))
                except Exception as exc:
                    failures.append(exc)

            with patch.object(ccrelay_cli, "_skill_root", return_value=skill_root):
                threads = [threading.Thread(target=resolve_runtime) for _ in range(4)]
                for thread in threads:
                    thread.start()
                for thread in threads:
                    thread.join()

            self.assertEqual([], failures)
            self.assertEqual(4, len(results))
            self.assertEqual(1, len({str(item) for item in results}))
            self.assertTrue(results[0].is_file())
            self.assertFalse(any((skill_root / ".local" / "runtime").glob("*.lock")))

    @classmethod
    def setUpClass(cls):
        RecordingHandler.records = []
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), RecordingHandler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.center = f"http://127.0.0.1:{cls.server.server_address[1]}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def setUp(self):
        RecordingHandler.records.clear()
        RecordingHandler.task_get_count = 0
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.skill_root_patch = patch.dict(
            os.environ,
            {"CCRELAY_SKILL_ROOT": self.temporary_directory.name},
        )
        self.skill_root_patch.start()
        self.ssh_config_path = Path(self.temporary_directory.name) / "ssh-credentials.json"
        self.bootstrap_db_path = Path(self.temporary_directory.name) / "bootstrap-state.db"
        self.fake_bin = Path(self.temporary_directory.name) / "bin"
        self.fake_bin.mkdir()
        if os.name == "nt":
            fake_ssh = self.fake_bin / "ssh.cmd"
            fake_ssh.write_text(
                "@echo off\r\nif \"%CCRELAY_TEST_SSH_SUCCESS%\"==\"1\" (echo CCRELAY_SSH_OK & exit /b 0)\r\n"
                "echo Permission denied 1>&2\r\nexit /b 255\r\n",
                encoding="utf-8",
            )
        else:
            fake_ssh = self.fake_bin / "ssh"
            fake_ssh.write_text(
                "#!/bin/sh\nif [ \"${CCRELAY_TEST_SSH_SUCCESS:-0}\" = \"1\" ]; then "
                "echo CCRELAY_SSH_OK; exit 0; fi\necho 'Permission denied' >&2\nexit 255\n",
                encoding="utf-8",
            )
            fake_ssh.chmod(0o700)

    def tearDown(self):
        self.skill_root_patch.stop()
        self.temporary_directory.cleanup()

    def test_production_default_center_is_local(self):
        self.assertEqual("http://127.0.0.1:18191", ccrelay_cli.DEFAULT_CENTER_URL)
        self.assertNotIn("47.93.195.246", CLI.read_text(encoding="utf-8"))

    def test_skill_templates_auto_fill_deterministic_ssh_fields(self):
        skill = (REPO_ROOT / "codex-skill" / "ccrelay" / "SKILL.md").read_text(encoding="utf-8")

        self.assertIn("SSH 端口: 22", skill)
        self.assertIn("从用户原始请求提取的节点", skill)
        self.assertIn("保存为通用配置: 是", skill)
        self.assertNotIn("保存为通用配置（是/否）", skill)

    @patch.object(ccrelay_cli.subprocess, "Popen")
    @patch.object(ccrelay_cli, "_discover_local_center_pids", return_value=[])
    @patch.object(ccrelay_cli, "_local_center_health", return_value={"status": "UP", "component": "center"})
    def test_center_resolve_reuses_healthy_local_center(self, _health, _discover, popen):
        args = argparse.Namespace(
            center=ccrelay_cli.DEFAULT_CENTER_URL,
            timeout=5.0,
            center_configured_by="DEFAULT",
        )

        result = ccrelay_cli.center_resolve(args)

        self.assertEqual("LOCAL", result["mode"])
        self.assertEqual("UP", result["status"])
        self.assertEqual("DEFAULT", result["configuredBy"])
        popen.assert_not_called()

    def run_cli(self, *args, extra_env=None):
        result = subprocess.run(
            [sys.executable, str(CLI), "--center", self.center, *args],
            cwd=str(REPO_ROOT),
            text=True,
            encoding="utf-8",
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
            env=self.cli_environment(extra_env),
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        return json.loads(result.stdout)

    def run_cli_result(self, *args, extra_env=None):
        environment = self.cli_environment(extra_env)
        return subprocess.run(
            [sys.executable, str(CLI), "--center", self.center, *args],
            cwd=str(REPO_ROOT),
            text=True,
            encoding="utf-8",
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
            env=environment,
        )

    def cli_environment(self, extra_env=None):
        environment = {
            **os.environ,
            "CCRELAY_SSH_CONFIG": str(self.ssh_config_path),
            "CCRELAY_BOOTSTRAP_DB": str(self.bootstrap_db_path),
            "WDSAVS_AI_HMAC_SECRET": SECRET,
            "PATH": str(self.fake_bin) + os.pathsep + os.environ.get("PATH", ""),
        }
        environment.update(extra_env or {})
        return environment

    def test_health_uses_center_health_api(self):
        output = self.run_cli("health")

        self.assertEqual({"status": "UP"}, output)
        self.assertEqual("GET", RecordingHandler.records[0]["method"])
        self.assertEqual("/api/skill/health", RecordingHandler.records[0]["path"])

    def test_bootstrap_next_without_state_enters_ssh_onboarding(self):
        output = self.run_cli("bootstrap", "next", "--node", "192.0.2.10:22")

        self.assertEqual("SSH_CREDENTIALS_REQUIRED", output["stage"])
        self.assertEqual("UNINITIALIZED", output["bootstrapStage"])
        self.assertEqual("RETURN_VERBATIM_RESPONSE_AND_STOP", output["agentAction"])
        self.assertTrue(output["mustStopCurrentTurn"])
        self.assertIn("1. 配置通用凭据，并按需补充节点独立凭据", output["verbatimResponse"])
        self.assertIn("请回复选项序号", output["verbatimResponse"])
        self.assertNotIn("CONFIGURE_DEFAULT_AND_NODE_OVERRIDES", output["verbatimResponse"])
        self.assertNotIn("defaultUsername", output["verbatimResponse"])
        self.assertNotIn("set-passwordless-default", output["verbatimResponse"])
        self.assertFalse(self.bootstrap_db_path.exists())

    def test_bootstrap_next_accepts_comma_separated_nodes_alias(self):
        output = self.run_cli(
            "bootstrap", "next", "--nodes", "192.0.2.10:22, 192.0.2.11:2222")

        self.assertEqual(
            ["192.0.2.10:22", "192.0.2.11:2222"],
            [item["node"] for item in output["fields"][4]["default"]],
        )

    def test_bootstrap_next_persists_complete_target_scope_before_credentials(self):
        output = self.run_cli(
            "bootstrap", "next", "--nodes",
            "192.0.2.10:22,192.0.2.11:22,192.0.2.12:22",
        )

        self.assertEqual("SSH_CREDENTIALS_REQUIRED", output["stage"])
        config = json.loads(self.ssh_config_path.read_text(encoding="utf-8"))
        self.assertEqual(
            ["192.0.2.10:22", "192.0.2.11:22", "192.0.2.12:22"],
            [item["nodeKey"] for item in config["clusterIdentity"]["targetNodes"]],
        )
        self.assertEqual([], config["clusterIdentity"]["managedNodes"])

    def test_identity_command_subset_reuses_persisted_complete_target_scope(self):
        with patch.dict(os.environ, {"CCRELAY_SSH_CONFIG": str(self.ssh_config_path)}):
            ccrelay_identity.resolve_target_nodes(
                "default",
                ["192.0.2.10:22,192.0.2.11:22,192.0.2.12:22"],
            )
            args = argparse.Namespace(
                cluster_id="default", nodes=["192.0.2.10:22"], center_node=None,
            )
            resolved = ccrelay_cli.resolve_identity_target_args(args, initialize=True)

        self.assertEqual(3, len(resolved))
        self.assertEqual(
            ["192.0.2.10:22", "192.0.2.11:22", "192.0.2.12:22"],
            args.nodes,
        )

    def test_identity_target_exclusion_requires_explicit_confirmation(self):
        with patch.dict(os.environ, {"CCRELAY_SSH_CONFIG": str(self.ssh_config_path)}):
            ccrelay_identity.resolve_target_nodes(
                "default", ["192.0.2.10:22,192.0.2.11:22"])
            with self.assertRaises(ccrelay_ssh.SshCredentialError):
                ccrelay_identity.update_target_nodes(
                    "default", ["192.0.2.11:22"], "EXCLUDE", False)
            unchanged = ccrelay_identity.target_status()
            changed = ccrelay_identity.update_target_nodes(
                "default", ["192.0.2.11:22"], "EXCLUDE", True)

        self.assertEqual(2, unchanged["activeTargetNodeCount"])
        self.assertEqual(1, changed["activeTargetNodeCount"])
        self.assertEqual(1, changed["excludedNodeCount"])

    def test_failed_nodes_remain_in_active_target_scope(self):
        with patch.dict(os.environ, {"CCRELAY_SSH_CONFIG": str(self.ssh_config_path)}):
            ccrelay_identity.resolve_target_nodes(
                "default", ["192.0.2.10:22,192.0.2.11:22"])
            ccrelay_identity.persist_failed_nodes([{
                "node": "192.0.2.11:22",
                "failureType": "NETWORK_UNREACHABLE",
                "summary": "unreachable",
            }])
            status = ccrelay_identity.target_status()

        self.assertEqual(2, status["activeTargetNodeCount"])
        self.assertEqual(1, status["failedNodeCount"])
        self.assertEqual("192.0.2.11:22", status["failedNodes"][0]["nodeKey"])

    def test_legacy_managed_nodes_are_migrated_to_target_scope_on_load(self):
        self.ssh_config_path.write_text(json.dumps({
            "clusterIdentity": {
                "managedNodes": [{
                    "host": "192.0.2.10", "port": 22,
                    "nodeKey": "192.0.2.10:22", "bootstrapUsername": "tester",
                }],
            },
        }), encoding="utf-8")
        with patch.dict(os.environ, {"CCRELAY_SSH_CONFIG": str(self.ssh_config_path)}):
            config = ccrelay_ssh.load_config()

        self.assertEqual(
            ["192.0.2.10:22"],
            [item["nodeKey"] for item in config["clusterIdentity"]["targetNodes"]],
        )
        self.assertEqual("tester", config["clusterIdentity"]["targetNodes"][0]["username"])

    def test_normalize_nodes_accepts_repeated_and_comma_separated_values(self):
        nodes = ccrelay_identity.normalize_nodes([
            "192.0.2.10:22,192.0.2.11:2222=tester",
            "192.0.2.10:22",
        ])

        self.assertEqual(["192.0.2.10:22", "192.0.2.11:2222"], [item["nodeKey"] for item in nodes])
        self.assertEqual("tester", nodes[1]["username"])

    def test_multi_node_commands_accept_concurrency(self):
        with patch.object(ccrelay_cli, "_center_hmac_secret", return_value="test-secret"):
            parser = ccrelay_cli.build_parser()
        bootstrap = parser.parse_args([
            "bootstrap", "next", "--nodes", "192.0.2.10:22,192.0.2.11:22",
            "--concurrency", "2",
        ])
        identity = parser.parse_args([
            "ssh", "identity", "verify", "--center-node", "192.0.2.10:22",
            "--nodes", "192.0.2.10:22,192.0.2.11:22", "--concurrency", "3",
        ])
        center = parser.parse_args([
            "center", "plan", "--nodes", "192.0.2.10:22,192.0.2.11:22",
            "--concurrency", "4",
        ])

        self.assertEqual(2, bootstrap.concurrency)
        self.assertEqual(3, identity.concurrency)
        self.assertEqual(4, center.concurrency)

    def test_concurrency_rejects_values_outside_supported_range(self):
        with patch.object(ccrelay_cli, "_center_hmac_secret", return_value="test-secret"):
            parser = ccrelay_cli.build_parser()
        for value in ("0", "33", "invalid"):
            with self.subTest(value=value), self.assertRaises(SystemExit):
                parser.parse_args([
                    "bootstrap", "next", "--node", "192.0.2.10:22",
                    "--concurrency", value,
                ])

    def test_parallel_map_is_bounded_ordered_and_failure_isolated(self):
        lock = threading.Lock()
        running = 0
        peak = 0

        def operation(value):
            nonlocal running, peak
            with lock:
                running += 1
                peak = max(peak, running)
            try:
                time.sleep(0.03)
                if value == 2:
                    raise RuntimeError("node failed")
                return value * 10
            finally:
                with lock:
                    running -= 1

        result = ccrelay_identity.parallel_map_ordered(
            [1, 2, 3, 4], operation, 2,
            lambda value, exc: f"{value}:{exc}",
        )

        self.assertEqual([10, "2:node failed", 30, 40], result)
        self.assertEqual(2, peak)

    def test_identity_plan_runs_node_probes_with_configured_concurrency(self):
        lock = threading.Lock()
        running = 0
        peak = 0

        def probe_connection(host, _port, username, _timeout):
            nonlocal running, peak
            with lock:
                running += 1
                peak = max(peak, running)
            try:
                time.sleep(0.03)
                return {"success": True, "status": "READY", "username": username or "tester"}
            finally:
                with lock:
                    running -= 1

        capability = {"osType": "LINUX", "canCreateAccount": True, "canInstallKey": True}
        with patch.object(ccrelay_ssh, "probe_connection", side_effect=probe_connection), \
                patch.object(ccrelay_identity, "probe_environment", return_value=capability):
            result = ccrelay_identity.plan(
                "default",
                "192.0.2.10:22",
                ["192.0.2.10:22,192.0.2.11:22,192.0.2.12:22"],
                True,
                "ccrelay",
                10,
                concurrency=2,
            )

        self.assertEqual("READY_TO_APPLY", result["status"])
        self.assertEqual(
            ["192.0.2.10:22", "192.0.2.11:22", "192.0.2.12:22"],
            [item["nodeKey"] for item in result["nodes"]],
        )
        self.assertEqual(2, peak)

    def test_identity_full_mesh_verification_uses_same_concurrency_limit(self):
        nodes = [
            {
                "host": f"192.0.2.{index}", "port": 22,
                "nodeKey": f"192.0.2.{index}:22", "bootstrapUsername": "tester",
                "runtimeUsername": "ccrelay",
                "privilegeSummary": {"shPath": "/bin/sh", "sshPath": "/usr/bin/ssh"},
            }
            for index in (10, 11, 12)
        ]
        lock = threading.Lock()
        running = 0
        peak = 0

        def run_command(*_args, **_kwargs):
            nonlocal running, peak
            with lock:
                running += 1
                peak = max(peak, running)
            try:
                time.sleep(0.02)
                return {"success": True, "latencyMs": 20, "summary": ""}
            finally:
                with lock:
                    running -= 1

        with patch.object(ccrelay_identity, "ensure_cluster_key", return_value=Path("cluster-key")), \
                patch.object(ccrelay_ssh, "ssh_arguments_for", return_value=[]), \
                patch.object(ccrelay_ssh, "run_authenticated_command", side_effect=run_command):
            result = ccrelay_identity.verify(
                "default", "192.0.2.10:22", nodes, True, "ccrelay", 10,
                "SHA256:test", "SHARED_KEYPAIR", concurrency=2)

        self.assertEqual("FULL_MESH", result["effectiveCapability"])
        self.assertEqual(6, len(result["trustEdges"]))
        self.assertEqual(
            [
                ("192.0.2.10:22", "192.0.2.11:22"),
                ("192.0.2.10:22", "192.0.2.12:22"),
                ("192.0.2.11:22", "192.0.2.10:22"),
                ("192.0.2.11:22", "192.0.2.12:22"),
                ("192.0.2.12:22", "192.0.2.10:22"),
                ("192.0.2.12:22", "192.0.2.11:22"),
            ],
            [(item["sourceNodeKey"], item["targetNodeKey"]) for item in result["trustEdges"]],
        )
        self.assertEqual(2, peak)

    def test_persisted_multi_node_scope_cannot_be_full_mesh_from_one_node_result(self):
        config = ccrelay_ssh.default_config()
        config["clusterIdentity"]["targetNodes"] = [
            {"host": "192.0.2.10", "port": 22, "nodeKey": "192.0.2.10:22"},
            {"host": "192.0.2.11", "port": 22, "nodeKey": "192.0.2.11:22"},
            {"host": "192.0.2.12", "port": 22, "nodeKey": "192.0.2.12:22"},
        ]
        node = {
            "host": "192.0.2.10", "port": 22, "nodeKey": "192.0.2.10:22",
            "bootstrapUsername": "tester", "runtimeUsername": "ccrelay",
            "privilegeSummary": {"shPath": "/bin/sh", "sshPath": "/usr/bin/ssh"},
        }
        with patch.object(ccrelay_identity, "ensure_cluster_key", return_value=Path("cluster-key")), \
                patch.object(ccrelay_identity.ccrelay_ssh, "load_config", return_value=config):
            result = ccrelay_identity.verify(
                "default", "192.0.2.10:22", [node], True, "ccrelay", 10,
                "SHA256:test", "SHARED_KEYPAIR",
                expected_target_nodes=config["clusterIdentity"]["targetNodes"])

        self.assertEqual("DEGRADED", result["effectiveCapability"])
        self.assertEqual(3, result["targetNodeCount"])
        self.assertEqual(1, result["verifiedNodeCount"])
        self.assertEqual(6, result["expectedTrustEdgeCount"])
        self.assertEqual(0, result["verifiedTrustEdgeCount"])

    def test_two_node_full_mesh_requires_both_directed_edges(self):
        nodes = [
            {
                "host": f"192.0.2.{index}", "port": 22,
                "nodeKey": f"192.0.2.{index}:22", "bootstrapUsername": "tester",
                "runtimeUsername": "ccrelay",
                "privilegeSummary": {"shPath": "/bin/sh", "sshPath": "/usr/bin/ssh"},
            }
            for index in (10, 11)
        ]
        responses = [
            {"success": True, "latencyMs": 1, "summary": ""},
            {"success": True, "latencyMs": 1, "summary": ""},
            {"success": False, "latencyMs": 1, "summary": "failed"},
        ]
        with patch.object(ccrelay_identity, "ensure_cluster_key", return_value=Path("cluster-key")), \
                patch.object(ccrelay_ssh, "ssh_arguments_for", return_value=[]), \
                patch.object(ccrelay_ssh, "run_authenticated_command", side_effect=responses), \
                patch.object(ccrelay_ssh, "run_key_command", return_value={
                    "success": False, "latencyMs": 1, "summary": "failed",
                }):
            result = ccrelay_identity.verify(
                "default", "192.0.2.10:22", nodes, True, "ccrelay", 10,
                "SHA256:test", "SHARED_KEYPAIR", concurrency=1)

        self.assertEqual("CENTER_ONLY", result["effectiveCapability"])
        self.assertEqual(2, result["expectedTrustEdgeCount"])
        self.assertEqual(1, result["verifiedTrustEdgeCount"])

    def test_center_candidates_accept_comma_separated_nodes(self):
        with patch.object(ccrelay_center.ccrelay_ssh, "load_config", return_value={}):
            candidates = ccrelay_center.discover_candidates([
                "192.0.2.10:22,192.0.2.11:2222=tester",
            ])

        self.assertEqual(
            ["192.0.2.10:22", "192.0.2.11:2222"],
            [item["nodeKey"] for item in candidates],
        )
        self.assertEqual("tester", candidates[1]["username"])

    def test_bootstrap_stage_set_get_and_reset(self):
        with patch.dict(os.environ, {"CCRELAY_BOOTSTRAP_DB": str(self.bootstrap_db_path)}):
            created = ccrelay_bootstrap.set_stage("cluster-a", ccrelay_bootstrap.SSH_READY)
            loaded = ccrelay_bootstrap.get_stage("cluster-a")
            reset = ccrelay_bootstrap.reset("cluster-a")

        self.assertEqual("SSH_READY", created["stage"])
        self.assertEqual("SSH_READY", loaded["stage"])
        self.assertEqual("UNINITIALIZED", reset["stage"])

    def test_bootstrap_mark_deployed_requires_ssh_ready_and_is_idempotent(self):
        blocked = self.run_cli("bootstrap", "mark-deployed")
        self.assertEqual("SSH_READY_REQUIRED", blocked["failureType"])
        self.assertEqual("UNINITIALIZED", blocked["stage"])

        with patch.dict(os.environ, {"CCRELAY_BOOTSTRAP_DB": str(self.bootstrap_db_path)}):
            ccrelay_bootstrap.set_stage("default", ccrelay_bootstrap.SSH_READY)
        deployed = self.run_cli("bootstrap", "mark-deployed")
        repeated = self.run_cli("bootstrap", "mark-deployed")

        self.assertEqual("DEPLOYED", deployed["stage"])
        self.assertTrue(deployed["changed"])
        self.assertFalse(repeated["changed"])

    @patch.object(ccrelay_cli, "persist_identity_state", return_value={"status": "DEFERRED"})
    @patch.object(ccrelay_cli.ccrelay_center, "model_config_bootstrap_gate", return_value={"ready": True})
    @patch.object(ccrelay_cli, "validate_identity_credentials", return_value=None)
    @patch.object(ccrelay_cli.ccrelay_ssh, "load_config", return_value={"default": {"username": "tester"}})
    def test_identity_apply_marks_ssh_ready_for_existing_account(
            self, _load_config, _validate, _model_gate, _persist):
        args = argparse.Namespace(
            cluster_id="default",
            center_node="192.0.2.10:22",
            nodes=["192.0.2.10:22"],
            allow_create=False,
            dedicated_username=None,
            remote_directory_template=None,
            confirm_details=False,
            connect_timeout=15,
            execution_mode="AUTO_EXECUTE_REMAINING",
            center=self.center,
            operator_id="tester",
        )
        identity_state = {
            "dedicatedAccountCreationAllowed": False,
            "effectiveCapability": "CENTER_ONLY",
        }
        with patch.object(ccrelay_cli.ccrelay_identity, "apply", return_value=identity_state), \
                patch.dict(os.environ, {"CCRELAY_BOOTSTRAP_DB": str(self.bootstrap_db_path)}):
            result = ccrelay_cli.ssh_identity_apply(args)

        self.assertEqual("SSH_READY", result["bootstrapStage"])

    def test_dedicated_identity_requires_full_mesh_before_ssh_ready(self):
        self.assertFalse(ccrelay_cli.identity_capability_is_ready({
            "dedicatedAccountCreationAllowed": True,
            "effectiveCapability": "CENTER_ONLY",
        }))
        self.assertTrue(ccrelay_cli.identity_capability_is_ready({
            "dedicatedAccountCreationAllowed": True,
            "effectiveCapability": "FULL_MESH",
        }))

    def test_identity_partial_failure_requires_user_decision(self):
        state = {
            "dedicatedAccountCreationAllowed": True,
            "effectiveCapability": "DEGRADED",
            "targetNodeCount": 2,
            "nodes": [
                {"nodeKey": "192.0.2.10:22", "accountStatus": "ACTIVE", "centerAccessStatus": "READY"},
                {"nodeKey": "192.0.2.11:22", "accountStatus": "FAILED", "centerAccessStatus": "FAILED",
                 "lastErrorSummary": "sudo permission denied",
                 "applyResult": {"success": False, "failureType": "PERMISSION_DENIED"}},
            ],
            "trustEdges": [],
        }

        decision = ccrelay_cli.identity_failure_decision(state)

        self.assertEqual("PARTIAL_FAILURE_REQUIRES_DECISION", decision["status"])
        self.assertEqual(1, decision["failedNodeCount"])
        self.assertIn("根据失败原因处理失败节点", decision["verbatimResponse"])
        self.assertIn("放弃失败节点", decision["verbatimResponse"])
        self.assertEqual("PERMISSION_DENIED", decision["failureReport"][0]["failureType"])

    def test_identity_failed_trust_edge_marks_both_nodes_failed(self):
        with patch.dict(os.environ, {"CCRELAY_SSH_CONFIG": str(self.ssh_config_path)}):
            config = ccrelay_ssh.load_config()
            config["clusterIdentity"]["targetNodes"] = [
                {"host": "192.0.2.10", "port": 22, "nodeKey": "192.0.2.10:22"},
                {"host": "192.0.2.11", "port": 22, "nodeKey": "192.0.2.11:22"},
            ]
            ccrelay_ssh.save_config(config)
            state = {
                "dedicatedAccountCreationAllowed": True,
                "nodes": [
                    {"nodeKey": "192.0.2.10:22", "accountStatus": "ACTIVE", "centerAccessStatus": "READY"},
                    {"nodeKey": "192.0.2.11:22", "accountStatus": "ACTIVE", "centerAccessStatus": "READY"},
                ],
                "trustEdges": [{
                    "sourceNodeKey": "192.0.2.10:22",
                    "targetNodeKey": "192.0.2.11:22",
                    "status": "FAILED",
                    "lastErrorSummary": "trust denied",
                }],
            }
            ccrelay_identity.persist_identity_outcome(state)
            persisted = ccrelay_identity.target_status()

        self.assertEqual(2, persisted["failedNodeCount"])
        self.assertEqual(
            {"192.0.2.10:22", "192.0.2.11:22"},
            {item["nodeKey"] for item in persisted["failedNodes"]},
        )

    def test_identity_account_mode_uses_human_template(self):
        interaction = ccrelay_cli.identity_mode_interaction({})

        self.assertIn("1. 创建 Skill 专用账号", interaction["verbatimResponse"])
        self.assertIn("2. 使用当前 SSH 账号", interaction["verbatimResponse"])
        self.assertNotIn("ALLOW_DEDICATED_ACCOUNT", interaction["verbatimResponse"])
        self.assertNotIn("SHOW_PLAN", interaction["verbatimResponse"])

    def test_ssh_missing_default_returns_structured_user_input(self):
        output = self.run_cli("ssh", "preflight", "--host", "192.0.2.10", "--port", "22")

        self.assertEqual("NEED_USER_INPUT", output["status"])
        self.assertEqual("SSH_DEFAULT_CREDENTIAL_MISSING", output["failureType"])
        self.assertFalse(output["readyForCenterDeploy"])
        self.assertEqual("CREATE_DEFAULT", output["interaction"]["options"][0]["id"])
        self.assertIn(
            "USE_EXISTING_PASSWORDLESS_ACCOUNT",
            [option["id"] for option in output["interaction"]["options"]]
        )
        field_names = [item["name"] for item in output["interaction"]["fields"]]
        self.assertIn("username", field_names)
        self.assertIn("password", field_names)
        self.assertIn("passwordlessUsername", field_names)

    def test_ssh_password_input_without_tty_returns_secure_terminal_and_risky_fallback(self):
        result = self.run_cli_result(
            "ssh", "config", "set-default", "--username", "tester", "--port", "22"
        )

        self.assertEqual(2, result.returncode)
        output = json.loads(result.stdout)
        self.assertEqual("SSH_PASSWORD_INPUT_REQUIRED", output["failureType"])
        self.assertEqual("NO_INTERACTIVE_TTY", output["reason"])
        launch = next(item for item in output["options"] if item["id"] == "LAUNCH_SECURE_TERMINAL")
        secure = next(item for item in output["options"] if item["id"] == "SECURE_TERMINAL_COMMAND")
        manual = next(item for item in output["options"] if item["id"] == "MANUAL_VISIBLE_INPUT")
        self.assertTrue(output["userChoiceRequired"])
        self.assertFalse(launch["recommended"])
        self.assertFalse(secure["recommended"])
        self.assertIn("高风险", launch["applicability"])
        self.assertIn("无桌面 GUI", secure["applicability"])
        self.assertIn("ccrelay-cli", secure["command"])
        self.assertIn("set-default", secure["command"])
        self.assertIn("--launch-secure-terminal", launch["command"])
        self.assertIn("--prompt-password", secure["command"])
        self.assertTrue(manual["securityWarning"])
        self.assertEqual([], output["fields"])
        self.assertIn("3. 明文输入", output["verbatimResponse"])
        self.assertNotIn("SSH 用户名", output["verbatimResponse"])
        self.assertNotIn("SSH 端口", output["verbatimResponse"])
        self.assertEqual(["password"], [field["name"] for field in manual["fields"]])
        self.assertNotIn("tester-password", result.stdout)
        self.assertNotIn("tester-password", result.stderr)

    @patch.object(ccrelay_ssh.sys.stdin, "isatty", return_value=False)
    def test_password_reader_raises_structured_input_required(self, _isatty):
        with self.assertRaises(ccrelay_ssh.SshCredentialInputRequired) as raised:
            ccrelay_ssh.read_password_input(None, None, "密码: ")
        interaction = raised.exception.interaction
        self.assertEqual("NO_INTERACTIVE_TTY", interaction["reason"])
        self.assertIn("manualVisibleFallbackAvailable", interaction["inputCapabilities"])

    @patch.object(ccrelay_ssh.getpass, "getpass")
    @patch.object(ccrelay_ssh.sys.stdin, "isatty", return_value=True)
    def test_password_reader_does_not_block_on_implicit_tool_tty(self, _isatty, getpass):
        with self.assertRaises(ccrelay_ssh.SshCredentialInputRequired):
            ccrelay_ssh.read_password_input(None, None, "密码: ")

        getpass.assert_not_called()

    @patch.object(ccrelay_ssh.getpass, "getpass", return_value="secret-password")
    @patch.object(ccrelay_ssh.sys.stdin, "isatty", return_value=True)
    def test_password_reader_prompts_only_when_explicitly_requested(self, _isatty, getpass):
        password = ccrelay_ssh.read_password_input(None, None, "密码: ", prompt_password=True)

        self.assertEqual("secret-password", password)
        getpass.assert_called_once_with("密码: ")

    @unittest.skipUnless(os.name == "nt", "Windows secure terminal launcher test")
    @patch.object(ccrelay_ssh.subprocess, "Popen")
    @patch.object(ccrelay_ssh, "input_capabilities", return_value={
        "platform": "WINDOWS",
        "currentProcessTty": True,
        "secureHiddenInput": False,
        "guiTerminalLauncherDetected": True,
        "guiTerminalLauncher": "powershell.exe",
        "manualVisibleFallbackAvailable": True,
        "passwordFileAvailable": True,
        "passwordEnvironmentAvailable": True,
    })
    def test_ssh_password_can_launch_independent_secure_terminal(self, _capabilities, popen):
        response = ccrelay_ssh.launch_secure_terminal([
            "ssh", "config", "set-default", "--username", "tester", "--port", "22",
        ])

        self.assertEqual("SECURE_TERMINAL_LAUNCHED", response["status"])
        launch_args = popen.call_args.args[0]
        self.assertIn("powershell.exe", launch_args)
        self.assertIn("--prompt-password", launch_args[-1])
        self.assertEqual(
            getattr(subprocess, "CREATE_NEW_CONSOLE", 0),
            popen.call_args.kwargs["creationflags"],
        )

    @unittest.skipUnless(os.name == "nt", "Windows desktop detection test")
    @patch.object(ccrelay_ssh, "windows_interactive_desktop_available", return_value=True)
    @patch.object(ccrelay_ssh.shutil, "which", return_value=None)
    def test_windows_secure_terminal_does_not_require_wt(self, _which, _desktop):
        self.assertEqual("powershell.exe", ccrelay_ssh.detect_gui_terminal_launcher())

    @patch.object(ccrelay_ssh, "_run_ssh", return_value={"success": True, "exitCode": 0})
    def test_ssh_test_can_use_protected_dedicated_password_override(self, run_ssh):
        result = ccrelay_ssh.test_connection(
            "192.0.2.10", 22, "ccrelay", 10, password_override="generated-password")

        self.assertTrue(result["success"])
        self.assertEqual("generated-password", run_ssh.call_args.kwargs["password"])
        self.assertIsNone(run_ssh.call_args.kwargs["key_path"])
        self.assertEqual("CREDENTIAL_VALID", result["status"])
        self.assertEqual("PASSWORD", result["authMode"])
        self.assertFalse(result["readyForCenterDeploy"])

    def test_ssh_test_without_bootstrap_is_read_only_when_passwordless_is_enabled(self):
        credential = {
            "username": "tester",
            "password": "secret",
            "enablePasswordless": True,
        }
        attempts = [
            {"success": False, "exitCode": 255, "summary": "public key rejected"},
            {"success": True, "exitCode": 0, "summary": "CCRELAY_SSH_OK", "latencyMs": 1},
        ]
        with patch.object(ccrelay_ssh, "check_local_tools", return_value={"missingTools": []}), \
                patch.object(ccrelay_ssh, "resolve_credential", return_value=(credential, "DEFAULT")), \
                patch.object(ccrelay_ssh, "configured_key_path", return_value=Path("test-key")), \
                patch.object(ccrelay_ssh, "_run_ssh", side_effect=attempts) as run_ssh, \
                patch.object(ccrelay_ssh, "bootstrap_public_key") as bootstrap_public_key:
            result = ccrelay_ssh.test_connection(
                "192.0.2.10", 22, "tester", 10, bootstrap_key=False)

        self.assertTrue(result["success"])
        self.assertEqual("CREDENTIAL_VALID", result["status"])
        self.assertEqual("PASSWORD", result["authMode"])
        self.assertFalse(result["readyForCenterDeploy"])
        self.assertNotIn("interaction", result)
        self.assertEqual(2, run_ssh.call_count)
        bootstrap_public_key.assert_not_called()

    def test_ssh_test_respects_disabled_passwordless_policy_when_bootstrap_is_requested(self):
        credential = {
            "username": "tester",
            "password": "secret",
            "enablePasswordless": False,
        }
        attempts = [
            {"success": False, "exitCode": 255, "summary": "public key rejected"},
            {"success": True, "exitCode": 0, "summary": "CCRELAY_SSH_OK", "latencyMs": 1},
        ]
        with patch.object(ccrelay_ssh, "check_local_tools", return_value={"missingTools": []}), \
                patch.object(ccrelay_ssh, "resolve_credential", return_value=(credential, "DEFAULT")), \
                patch.object(ccrelay_ssh, "configured_key_path", return_value=Path("test-key")), \
                patch.object(ccrelay_ssh, "_run_ssh", side_effect=attempts), \
                patch.object(ccrelay_ssh, "bootstrap_public_key") as bootstrap_public_key:
            result = ccrelay_ssh.test_connection(
                "192.0.2.10", 22, "tester", 10, bootstrap_key=True)

        self.assertTrue(result["success"])
        self.assertEqual("PASSWORD_AUTHENTICATED_KEY_REQUIRED", result["status"])
        bootstrap_public_key.assert_not_called()

    def test_read_only_probe_does_not_report_password_auth_as_deploy_ready(self):
        credential = {"username": "tester", "password": "secret"}
        with patch.object(ccrelay_ssh, "resolve_credential", return_value=(credential, "DEFAULT")), \
                patch.object(ccrelay_ssh, "run_authenticated_command", return_value={
                    "success": True,
                    "authMode": "PASSWORD",
                    "summary": "CCRELAY_SSH_OK",
                    "latencyMs": 1,
                }):
            result = ccrelay_ssh.probe_connection("192.0.2.10", 22, "tester", 10)

        self.assertTrue(result["success"])
        self.assertEqual("CREDENTIAL_VALID", result["status"])
        self.assertFalse(result["readyForCenterDeploy"])

    @patch.object(ccrelay_ssh, "_run_ssh", return_value={"success": True, "exitCode": 0})
    @patch.object(ccrelay_ssh, "resolve_credential", return_value=(None, None))
    def test_center_key_bootstrap_can_use_dedicated_password_override(self, _resolve, run_ssh):
        result = ccrelay_ssh.bootstrap_public_key_value(
            "192.0.2.10", 22, "ccrelay", "ssh-ed25519 AAAATEST center", 10,
            password_override="generated-password")

        self.assertTrue(result["success"])
        self.assertEqual("generated-password", run_ssh.call_args.kwargs["password"])
        self.assertIsNone(run_ssh.call_args.kwargs["key_path"])

    def test_ssh_config_masks_password_and_persists_locally(self):
        result = self.run_cli_result(
            "ssh",
            "config",
            "set-default",
            "--username",
            "tester",
            "--password-env",
            "TEST_SSH_PASSWORD",
            extra_env={"TEST_SSH_PASSWORD": "secret-password"},
        )
        self.assertEqual(0, result.returncode, result.stderr)
        output = json.loads(result.stdout)

        self.assertEqual("********", output["password"])
        self.assertNotIn("secret-password", result.stdout)
        self.assertTrue(self.ssh_config_path.is_file())
        stored = self.ssh_config_path.read_text(encoding="utf-8")
        if os.name == "nt":
            self.assertNotIn("secret-password", stored)
        else:
            self.assertEqual(0o600, self.ssh_config_path.stat().st_mode & 0o777)

        shown = self.run_cli("ssh", "config", "show")
        self.assertEqual("tester", shown["default"]["username"])
        self.assertEqual("********", shown["default"]["password"])
        with patch.dict(os.environ, {"CCRELAY_SSH_CONFIG": str(self.ssh_config_path)}):
            stored_config = ccrelay_ssh.load_config()
        self.assertEqual("USER_PROVIDED", stored_config["default"]["credentialSource"])

    def test_ssh_config_can_store_existing_passwordless_default_without_password(self):
        private_key = Path(self.temporary_directory.name) / "existing-key"
        private_key.write_text("test-key", encoding="utf-8")

        output = self.run_cli(
            "ssh",
            "config",
            "set-passwordless-default",
            "--username",
            "existing-user",
            "--port",
            "2222",
            "--private-key-file",
            str(private_key),
        )

        self.assertEqual("existing-user", output["username"])
        self.assertEqual(2222, output["port"])
        self.assertEqual("PUBLIC_KEY", output["authenticationMode"])
        self.assertTrue(output["privateKeyConfigured"])
        self.assertFalse(output["passwordConfigured"])
        self.assertIsNone(output["password"])
        with patch.dict(os.environ, {"CCRELAY_SSH_CONFIG": str(self.ssh_config_path)}):
            stored = ccrelay_ssh.load_config()["default"]
        self.assertEqual("EXISTING_PASSWORDLESS", stored["credentialSource"])
        self.assertNotIn("password", stored)
        self.assertNotIn("protectedPassword", stored)

    def test_ssh_config_can_use_user_provided_arguments_without_default_options(self):
        output = self.run_cli(
            "ssh",
            "config",
            "set-passwordless-default",
            "--username",
            "existing-user",
            "--port",
            "22",
            "--ssh-arguments-mode",
            "USER_PROVIDED",
            "--ssh-argument=-o",
            "--ssh-argument=StrictHostKeyChecking=no",
            "--ssh-argument=-o",
            "--ssh-argument=ConnectTimeout=8",
        )

        self.assertEqual("USER_PROVIDED", output["sshArgumentsMode"])
        self.assertEqual(4, output["sshArgumentCount"])
        completed = subprocess.CompletedProcess([], 0, stdout="CCRELAY_SSH_OK", stderr="")
        with patch.dict(os.environ, {"CCRELAY_SSH_CONFIG": str(self.ssh_config_path)}), \
                patch.object(ccrelay_ssh.shutil, "which", return_value="ssh"), \
                patch.object(ccrelay_ssh.subprocess, "run", return_value=completed) as run_command:
            result = ccrelay_ssh.run_authenticated_command(
                "192.0.2.10", 22, "existing-user", "printf ok")

        command = run_command.call_args.args[0]
        self.assertTrue(result["success"])
        self.assertIn("StrictHostKeyChecking=no", command)
        self.assertIn("ConnectTimeout=8", command)
        self.assertNotIn("StrictHostKeyChecking=accept-new", command)
        self.assertNotIn("ConnectionAttempts=1", command)
        self.assertNotIn("BatchMode=yes", command)

    def test_default_ssh_and_scp_arguments_use_legacy_host_key_compatible_mode(self):
        credential = {"username": "tester", "authenticationMode": "PUBLIC_KEY"}
        completed = subprocess.CompletedProcess([], 0, stdout="CCRELAY_SSH_OK", stderr="")
        with patch.dict(os.environ, {"CCRELAY_SSH_CONFIG": str(self.ssh_config_path)}), \
                patch.object(ccrelay_ssh, "resolve_credential", return_value=(credential, "DEFAULT")), \
                patch.object(ccrelay_ssh.shutil, "which", return_value="ssh"), \
                patch.object(ccrelay_ssh.subprocess, "run", return_value=completed) as run_command:
            ccrelay_ssh._run_ssh("192.0.2.10", 22, "tester", 10)
            ccrelay_ssh._run_scp("192.0.2.10", 22, "tester", ["source"], "/tmp/target", 10)

        commands = [call.args[0] for call in run_command.call_args_list]
        self.assertTrue(all("StrictHostKeyChecking=no" in command for command in commands))
        self.assertTrue(all("StrictHostKeyChecking=accept-new" not in command for command in commands))

    def test_existing_passwordless_default_uses_configured_private_key_without_password_fallback(self):
        private_key = Path(self.temporary_directory.name) / "existing-key"
        private_key.write_text("test-key", encoding="utf-8")
        credential = {
            "username": "existing-user",
            "authenticationMode": "PUBLIC_KEY",
            "privateKeyPath": str(private_key),
        }
        with patch.object(ccrelay_ssh, "resolve_credential", return_value=(credential, "DEFAULT")), \
                patch.object(ccrelay_ssh, "_run_ssh", return_value={
                    "success": True, "exitCode": 0, "authMode": "PUBLIC_KEY",
                }) as run_ssh:
            result = ccrelay_ssh.run_authenticated_command(
                "192.0.2.10", 22, "existing-user", "printf ok")

        self.assertTrue(result["success"])
        self.assertEqual(private_key.resolve(), run_ssh.call_args.kwargs["key_path"])
        self.assertIsNone(run_ssh.call_args.kwargs.get("password"))
        self.assertEqual(1, run_ssh.call_count)

    @patch.object(ccrelay_ssh, "_run_ssh", return_value={"success": True, "exitCode": 0})
    @patch.object(ccrelay_ssh, "resolve_credential", return_value=({"username": "tester", "password": "secret"}, "DEFAULT"))
    def test_ssh_key_bootstrap_sets_posix_path_for_restricted_login(self, _resolve, run_ssh):
        result = ccrelay_ssh.bootstrap_public_key_value(
            "192.0.2.10",
            22,
            "tester",
            "ssh-ed25519 AAAATEST ccrelay-center",
        )

        self.assertTrue(result["success"])
        command = run_ssh.call_args.kwargs["remote_command"]
        self.assertTrue(command.startswith("export PATH=\"/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH\";"))

    @patch.object(ccrelay_ssh.shutil, "which", return_value="C:/Windows/System32/OpenSSH/ssh.exe")
    @patch.object(ccrelay_ssh.subprocess, "run")
    def test_ssh_script_stdin_uses_binary_lf_on_windows(self, run_process, _which):
        run_process.return_value = subprocess.CompletedProcess(
            args=[], returncode=0, stdout=b"CCRELAY_OK\n", stderr=b"")

        result = ccrelay_ssh._run_ssh(
            "192.0.2.10", 22, "tester", 10,
            remote_command="/bin/bash -s", stdin_text="set -eu\r\nprintf ok\r\n", include_output=True)

        self.assertTrue(result["success"])
        self.assertEqual(b"set -eu\nprintf ok\n", run_process.call_args.kwargs["input"])
        self.assertNotIn("text", run_process.call_args.kwargs)
        self.assertEqual("CCRELAY_OK\n", result["stdout"])

    def test_identity_requires_ssh_credentials_before_account_mode(self):
        result = self.run_cli_result(
            "ssh",
            "identity",
            "plan",
            "--center-node",
            "192.0.2.10:22",
            "--node",
            "192.0.2.10:22",
        )
        self.assertEqual(0, result.returncode, result.stderr)
        output = json.loads(result.stdout)
        self.assertEqual("NEED_USER_INPUT", output["status"])
        self.assertEqual("SSH_CREDENTIALS_REQUIRED", output["stage"])
        self.assertEqual("CONFIGURE_DEFAULT_AND_NODE_OVERRIDES", output["options"][0]["id"])
        passwordless_option = next(
            option for option in output["options"]
            if option["id"] == "USE_EXISTING_PASSWORDLESS_ACCOUNT")
        self.assertIn("set-passwordless-default", passwordless_option["command"])
        self.assertEqual(output["options"], output["interaction"]["options"])
        self.assertEqual(
            [
                "defaultUsername", "defaultPassword", "defaultSshPort", "saveAsDefault", "nodeOverrides",
                "passwordlessUsername", "passwordlessSshPort", "passwordlessPrivateKeyPath",
            ],
            [field["name"] for field in output["fields"]],
        )
        passwordless_fields = output["fields"][-3:]
        self.assertTrue(all(
            field["visibleWhen"]["optionId"] == "USE_EXISTING_PASSWORDLESS_ACCOUNT"
            for field in passwordless_fields))
        self.assertIsNone(output["fields"][0]["default"])
        self.assertIn("不从本机用户名", output["fields"][0]["hint"])
        self.assertFalse(RecordingHandler.records)

    def test_fresh_config_never_infers_operating_system_username(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            config_path = Path(temporary_directory) / "missing" / "ssh-credentials.json"
            with patch.dict(os.environ, {
                "CCRELAY_SSH_CONFIG": str(config_path),
                "USERNAME": "should-not-be-ssh-default",
                "USER": "should-not-be-ssh-default",
            }):
                config = ccrelay_ssh.load_config()
                fields = ccrelay_ssh.default_fields(22)

        self.assertIsNone(config["default"])
        self.assertIsNone(fields[0]["default"])
        self.assertNotIn("should-not-be-ssh-default", json.dumps(fields, ensure_ascii=False))

    def test_identity_requires_explicit_account_mode_after_credentials(self):
        self.seed_default_ssh_credential()
        result = self.run_cli_result(
            "ssh",
            "identity",
            "plan",
            "--node",
            "192.0.2.10:22",
            extra_env={"CCRELAY_TEST_SSH_SUCCESS": "1"},
        )
        self.assertEqual(0, result.returncode, result.stderr)
        output = json.loads(result.stdout)
        self.assertEqual("NEED_USER_INPUT", output["status"])
        self.assertEqual("SSH_ACCOUNT_MODE_REQUIRED", output["stage"])
        self.assertEqual("ALLOW_DEDICATED_ACCOUNT", output["options"][0]["id"])
        self.assertFalse(RecordingHandler.records)

    def test_identity_blocks_account_mode_until_all_credentials_are_valid(self):
        self.seed_default_ssh_credential()
        result = self.run_cli_result(
            "ssh",
            "identity",
            "plan",
            "--center-node",
            "192.0.2.10:22",
            "--node",
            "192.0.2.10:22",
        )

        self.assertEqual(0, result.returncode, result.stderr)
        output = json.loads(result.stdout)
        self.assertEqual("NEED_USER_INPUT", output["status"])
        self.assertEqual("SSH_CREDENTIALS_INVALID", output["stage"])
        self.assertEqual("CONFIGURE_NODE_OVERRIDES", output["options"][0]["id"])
        self.assertEqual(output["fields"], output["interaction"]["fields"])
        self.assertEqual(1, output["validation"]["failedNodeCount"])
        self.assertEqual("192.0.2.10:22", output["validation"]["failedNodes"][0]["node"])
        self.assertFalse(RecordingHandler.records)

    def test_identity_requires_dedicated_account_details_confirmation(self):
        self.seed_default_ssh_credential()
        output = self.run_cli(
            "ssh",
            "identity",
            "plan",
            "--allow-create",
            "true",
            "--node",
            "192.0.2.10:22",
            extra_env={"CCRELAY_TEST_SSH_SUCCESS": "1"},
        )

        self.assertEqual("DEDICATED_ACCOUNT_DETAILS_CONFIRMATION", output["stage"])
        self.assertEqual("ccrelay", output["summary"]["dedicatedUsername"])
        self.assertEqual(
            "/home/ccrelay/ccrelay/192.0.2.10-<auto-relay-port>",
            output["summary"]["deploymentDirectories"][0]["directory"],
        )
        self.assertEqual(
            ["CONFIRM_DETAILS", "CHANGE_DETAILS", "CANCEL"],
            [option["id"] for option in output["options"]],
        )
        self.assertEqual(
            "即将创建以下专用账号：\n\n"
            "账号: ccrelay\n"
            "部署目录: 按节点和 Relay 端口自动生成\n"
            "节点间免密: 开启\n"
            "端口: 部署时自动选择\n\n"
            "1. 确认\n"
            "2. 修改账号或目录\n"
            "3. 取消\n\n"
            "请回复选项序号。",
            output["verbatimResponse"],
        )
        self.assertNotIn("需要填写", output["verbatimResponse"])
        self.assertNotIn("${runtimeUser}", output["verbatimResponse"])
        self.assertNotIn("改用现有账号", output["verbatimResponse"])
        self.assertFalse(RecordingHandler.records)

    def test_identity_apply_is_blocked_until_details_are_confirmed(self):
        self.seed_default_ssh_credential()
        output = self.run_cli(
            "ssh",
            "identity",
            "apply",
            "--allow-create",
            "true",
            "--center-node",
            "192.0.2.10:22",
            "--node",
            "192.0.2.10:22",
            extra_env={"CCRELAY_TEST_SSH_SUCCESS": "1"},
        )

        self.assertEqual("DEDICATED_ACCOUNT_DETAILS_CONFIRMATION", output["stage"])
        self.assertFalse(output["taskCreated"])
        self.assertFalse(RecordingHandler.records)

    def test_identity_details_confirmation_requires_execution_mode_before_remote_change(self):
        self.seed_default_ssh_credential()
        output = self.run_cli(
            "ssh",
            "identity",
            "apply",
            "--allow-create",
            "true",
            "--confirm-details",
            "true",
            "--center-node",
            "192.0.2.10:22",
            "--node",
            "192.0.2.10:22",
            extra_env={"CCRELAY_TEST_SSH_SUCCESS": "1"},
        )

        self.assertEqual("MODEL_CONFIG_PREPARE", output["stage"])
        self.assertFalse(output["taskCreated"])
        self.assertEqual("ssh identity apply", output["blockedCommand"])
        self.assertFalse(RecordingHandler.records)

    def test_identity_plan_requires_execution_mode_after_details_confirmation(self):
        self.seed_default_ssh_credential()
        output = self.run_cli(
            "ssh",
            "identity",
            "plan",
            "--allow-create",
            "true",
            "--confirm-details",
            "true",
            "--node",
            "192.0.2.10:22",
            extra_env={"CCRELAY_TEST_SSH_SUCCESS": "1"},
        )

        self.assertEqual("MODEL_CONFIG_PREPARE", output["stage"])
        self.assertFalse(output["taskCreated"])
        self.assertFalse(RecordingHandler.records)

    def test_identity_plan_defers_center_selection_until_execution_mode_is_confirmed(self):
        self.seed_default_ssh_credential()
        model_config = Path(self.temporary_directory.name) / "cc-model-config.yml"
        model_config.write_text(
            "model: test-model\nbaseUrl: http://127.0.0.1:1/anthropic\napiKey: test-key\n",
            encoding="utf-8",
        )
        output = self.run_cli(
            "ssh",
            "identity",
            "plan",
            "--allow-create",
            "true",
            "--confirm-details",
            "true",
            "--execution-mode",
            "AUTO_EXECUTE_REMAINING",
            "--node",
            "192.0.2.10:22",
            extra_env={
                "CCRELAY_TEST_SSH_SUCCESS": "1",
                "CCRELAY_MODEL_CONFIG_FILE": str(model_config),
            },
        )

        self.assertEqual("CENTER_SELECTION_REQUIRED", output["stage"])
        self.assertEqual("AUTO_SELECT_CENTER", output["options"][0]["id"])
        self.assertEqual(["192.0.2.10:22"], output["candidateNodes"])
        self.assertFalse(output["taskCreated"])

    def test_identity_plan_accepts_password_authenticated_credentials(self):
        credential_result = {
            "success": True,
            "status": "CREDENTIAL_VALID",
            "username": "tester",
            "credentialScope": "DEFAULT",
        }
        capability = {
            "osType": "LINUX",
            "canCreateAccount": True,
            "canInstallKey": True,
            "dedicatedAccountConflict": False,
        }
        with patch.object(ccrelay_ssh, "probe_connection", return_value=credential_result), \
                patch.object(ccrelay_identity, "probe_environment", return_value=capability):
            result = ccrelay_identity.plan(
                "default",
                "192.0.2.10:22",
                ["192.0.2.10:22"],
                True,
                "ccrelay",
                10,
            )

        self.assertEqual("READY_TO_APPLY", result["status"])
        self.assertTrue(result["nodes"][0]["bootstrapSuccess"])
        self.assertEqual("CREDENTIAL_VALID", result["nodes"][0]["bootstrapStatus"])

    def test_identity_rejects_unknown_directory_template_variable(self):
        with self.assertRaises(ccrelay_ssh.SshCredentialError):
            ccrelay_identity.normalize_remote_directory_template(
                "/home/${runtimeUser}/${unknown}/${host}-${relayPort}")

    def test_identity_local_status_masks_dedicated_passwords(self):
        with patch.dict(os.environ, {"CCRELAY_SSH_CONFIG": str(self.ssh_config_path)}):
            config = ccrelay_ssh.load_config()
            config["clusterIdentity"]["selectionRequired"] = False
            config["clusterIdentity"]["dedicatedAccount"]["passwordSecrets"] = {
                "192.0.2.10:22": ccrelay_ssh.protect_secret("dedicated-secret")
            }
            ccrelay_ssh.save_config(config)
            status = ccrelay_identity.local_status()
        shown = self.run_cli("ssh", "config", "show")
        stored = self.ssh_config_path.read_text(encoding="utf-8")
        self.assertEqual(1, status["passwordSecretCount"])
        if os.name == "nt":
            self.assertNotIn("dedicated-secret", stored)
        else:
            self.assertEqual(0o600, self.ssh_config_path.stat().st_mode & 0o777)
        self.assertNotIn("dedicated-secret", json.dumps(shown, ensure_ascii=False))

    def test_generated_dedicated_password_meets_common_complexity_policies(self):
        with patch.object(ccrelay_ssh.secrets, "token_urlsafe", return_value="lowercaseonly" * 5):
            password = ccrelay_ssh.random_secret()

        self.assertGreaterEqual(len(password), 24)
        self.assertRegex(password, r"[A-Z]")
        self.assertRegex(password, r"[a-z]")
        self.assertRegex(password, r"[0-9]")
        self.assertRegex(password, r"[!@#%+=_-]")
        self.assertNotRegex(password, r"[:'\"\\\s]")

    def test_dedicated_password_replaces_existing_secret_that_fails_complexity_policy(self):
        with patch.dict(os.environ, {"CCRELAY_SSH_CONFIG": str(self.ssh_config_path)}):
            config = ccrelay_ssh.load_config()
            config["clusterIdentity"]["dedicatedAccount"]["passwordSecrets"] = {
                "192.0.2.10:22": ccrelay_ssh.protect_secret("legacy-lowercase-password")
            }
            ccrelay_ssh.save_config(config)

            password = ccrelay_identity.dedicated_password(
                "default", "192.0.2.10:22", False)

        self.assertNotEqual("legacy-lowercase-password", password)
        self.assertTrue(ccrelay_ssh.secret_meets_complexity_policy(password))

    def seed_default_ssh_credential(self):
        with patch.dict(os.environ, {"CCRELAY_SSH_CONFIG": str(self.ssh_config_path)}):
            ccrelay_ssh.set_default_credential("tester", "secret-password", 22)

    def test_identity_status_only_becomes_active_after_full_mesh(self):
        self.assertEqual(
            "PARTIAL",
            ccrelay_identity.dedicated_account_status(
                {"effectiveCapability": "CENTER_ONLY", "nodes": [{"accountStatus": "ACTIVE"}]},
                True,
            ),
        )
        self.assertEqual(
            "ACTIVE",
            ccrelay_identity.dedicated_account_status(
                {"effectiveCapability": "FULL_MESH", "nodes": [{"accountStatus": "ACTIVE"}]},
                True,
            ),
        )
        self.assertEqual("DISABLED", ccrelay_identity.dedicated_account_status({}, False))

    def test_identity_rejects_invalid_dedicated_username(self):
        with self.assertRaises(ccrelay_ssh.SshCredentialError):
            ccrelay_identity.normalize_dedicated_username("bad user; rm -rf")

    def test_dedicated_provisioning_preserves_existing_authorized_keys(self):
        linux_script = ccrelay_identity.linux_provision_script(
            "ccrelay",
            "generated-password",
            "ssh-ed25519 AAAATEST cluster",
            "-----BEGIN OPENSSH PRIVATE KEY-----\ntest\n-----END OPENSSH PRIVATE KEY-----",
        )
        windows_script = ccrelay_identity.windows_provision_script(
            "ccrelay",
            "generated-password",
            "ssh-ed25519 AAAATEST cluster",
            "-----BEGIN OPENSSH PRIVATE KEY-----\ntest\n-----END OPENSSH PRIVATE KEY-----",
        )

        self.assertNotIn('cat > "$home/.ssh/authorized_keys"', linux_script)
        self.assertIn("grep -qxF", linux_script)
        self.assertIn("CCRELAY_ACCOUNT_CONFLICT", linux_script)
        self.assertIn("Add-Content", windows_script)
        self.assertIn("CCRELAY_ACCOUNT_CONFLICT", windows_script)

    @patch.object(ccrelay_ssh, "run_authenticated_command")
    def test_identity_probe_returns_absolute_privilege_tool_paths(self, run_command):
        run_command.return_value = {
            "success": True,
            "stdout": (
                "CCRELAY_CAP|os=LINUX|uid=1000|sudo=true|sudoPath=/usr/bin/sudo|"
                "bashPath=/bin/bash|shPath=/bin/sh|shellPath=/bin/bash|runuserPath=/usr/sbin/runuser|"
                "suPath=/bin/su|sshPath=/usr/bin/ssh|mkdirPath=/usr/bin/mkdir|getentPath=/usr/bin/getent|"
                "accountToolPath=/usr/sbin/useradd|chpasswdPath=/usr/sbin/chpasswd|"
                "dedicatedExists=false|dedicatedManaged=false\n"
            ),
            "summary": "",
        }

        result = ccrelay_identity.probe_environment(
            {"host": "192.0.2.10", "port": 22}, "tester", 10)

        self.assertTrue(result["canCreateAccount"])
        self.assertEqual("/usr/bin/sudo", result["sudoPath"])
        self.assertEqual("/bin/bash", result["shellPath"])
        self.assertEqual("/usr/bin/ssh", result["sshPath"])
        self.assertIn("command -v sudo", run_command.call_args.args[3])
        self.assertIn(
            '\"$sudo_path\" -n test -f \"$dedicated_home/.ssh/.ccrelay-managed\"',
            run_command.call_args.args[3],
        )

    def test_identity_plan_does_not_treat_inaccessible_managed_account_as_conflict(self):
        capability = {
            "osType": "LINUX",
            "canCreateAccount": False,
            "canInstallKey": False,
            "dedicatedExists": "true",
            "dedicatedInspection": "INACCESSIBLE",
            "dedicatedAccountManaged": False,
            "dedicatedAccountConflict": False,
        }
        with patch.object(ccrelay_ssh, "probe_connection", return_value={
                "success": True,
                "status": "READY",
                "username": "bootstrap",
                "credentialScope": "DEFAULT",
        }), patch.object(ccrelay_identity, "probe_environment", return_value=capability):
            result = ccrelay_identity.plan(
                "default", "192.0.2.10:22", ["192.0.2.10:22"], True, "ccrelay", 10)

        self.assertEqual("READY_TO_APPLY", result["status"])
        self.assertEqual("UNVERIFIED", result["nodes"][0]["accountStatus"])
        self.assertFalse(result["nodes"][0]["privilegeSummary"]["dedicatedAccountConflict"])

    def test_identity_plan_surfaces_ssh_argument_choice_for_old_openssh(self):
        with patch.object(ccrelay_ssh, "probe_connection", return_value={
                "success": False,
                "failureType": "SSH_ARGUMENTS_INCOMPATIBLE",
                "summary": "Bad configuration option: accept-new",
        }):
            result = ccrelay_identity.plan(
                "default", "192.0.2.10:22", ["192.0.2.10:22"], True, "ccrelay", 10)

        self.assertEqual("NEED_USER_INPUT", result["status"])
        self.assertEqual("USE_USER_PROVIDED_SSH_ARGUMENTS", result["interaction"]["options"][0]["id"])
        field_names = [field["name"] for field in result["interaction"]["fields"]]
        self.assertEqual(["sshArgumentsMode", "sshArguments"], field_names)

    @patch.object(ccrelay_cli, "request_json", side_effect=ccrelay_cli.urllib.error.URLError("connection refused"))
    def test_identity_persistence_is_deferred_until_center_starts(self, _request):
        args = argparse.Namespace(center="http://127.0.0.1:18191", cluster_id="default", operator_id="tester")
        result = ccrelay_cli.persist_identity_state(
            args,
            {
                "clusterId": "default",
                "accountMode": "DEDICATED_MANAGED",
                "dedicatedAccountCreationAllowed": True,
                "dedicatedUsername": "ccrelay",
                "effectiveCapability": "FULL_MESH",
                "nodes": [],
                "trustEdges": [],
            },
            "APPLY",
        )

        self.assertEqual("DEFERRED", result["status"])
        self.assertFalse(result["persisted"])
        self.assertTrue(result["requiredBeforeDeploy"])
        self.assertEqual("ssh identity verify", result["resumeCommand"])

    @patch.object(ccrelay_cli, "validate_identity_credentials", return_value=None)
    @patch.object(ccrelay_cli.ccrelay_ssh, "save_config")
    @patch.object(ccrelay_cli.ccrelay_ssh, "load_config")
    def test_identity_select_persists_dedicated_mode_before_apply(
            self, load_config, save_config, validate_identity_credentials):
        config = {
            "default": {"username": "bootstrap"},
            "nodes": {},
            "clusterIdentity": {"selectionRequired": True, "dedicatedAccount": {}},
            "runtime": {},
        }
        load_config.return_value = config
        args = argparse.Namespace(
            allow_create=True,
            cluster_id="default",
            center_node=None,
            nodes=["192.0.2.20:22"],
            connect_timeout=15,
            dedicated_username="ccrelay",
            remote_directory_template="/home/${runtimeUser}/${productName}/${host}-${relayPort}",
            confirm_details=False,
        )

        result = ccrelay_cli.ssh_identity_select_mode(args)

        self.assertEqual("NEED_USER_INPUT", result["status"])
        self.assertEqual("DEDICATED_PENDING", config["clusterIdentity"]["accountMode"])
        self.assertTrue(config["clusterIdentity"]["dedicatedAccountCreationAllowed"])
        self.assertEqual("ccrelay", config["clusterIdentity"]["dedicatedAccount"]["username"])
        self.assertEqual(2, save_config.call_count)
        save_config.assert_called_with(config)
        validate_identity_credentials.assert_called_once()

    @patch.object(ccrelay_ssh, "run_authenticated_command", return_value={"success": True})
    def test_dedicated_provisioning_uses_probed_absolute_sudo_and_bash(self, run_command):
        with tempfile.TemporaryDirectory() as temporary_directory:
            private_key = Path(temporary_directory) / "cluster_ed25519"
            private_key.write_text("PRIVATE\r\nKEY\r\n", encoding="utf-8")
            result = ccrelay_identity.provision_dedicated(
                {
                    "host": "192.0.2.10",
                    "port": 22,
                    "bootstrapUsername": "tester",
                    "osType": "LINUX",
                    "privilegeSummary": {
                        "uid": "1000",
                        "sudoPath": "/usr/bin/sudo",
                        "bashPath": "/bin/bash",
                        "shPath": "/bin/sh",
                    },
                },
                "ccrelay",
                "generated-password",
                "ssh-ed25519 AAAATEST cluster",
                private_key,
                10,
            )

        self.assertTrue(result["success"])
        self.assertEqual("/usr/bin/sudo -n /bin/bash -s", run_command.call_args.args[3])
        self.assertNotIn("\r", run_command.call_args.kwargs["stdin_text"])

    def test_identity_verify_uses_probed_absolute_paths(self):
        command = ccrelay_identity.center_verify_command(
            {"host": "192.0.2.11", "port": 22},
            "ccrelay",
            True,
            {
                "uid": "1000",
                "sudoPath": "/usr/bin/sudo",
                "shPath": "/bin/sh",
                "sshPath": "/usr/bin/ssh",
            },
            "tester",
        )

        self.assertIn("/usr/bin/sudo -n -u ccrelay /bin/sh -c", command)
        self.assertIn("/usr/bin/ssh", command)

    def test_identity_verify_uses_custom_ssh_arguments_for_remote_hops(self):
        command = ccrelay_identity.center_verify_command(
            {"host": "192.0.2.11", "port": 22},
            "ccrelay",
            True,
            {"uid": "0", "runuserPath": "/usr/sbin/runuser", "shPath": "/bin/sh", "sshPath": "/usr/bin/ssh"},
            "bootstrap",
            ["-o", "StrictHostKeyChecking=no", "-o", "KexAlgorithms=+diffie-hellman-group14-sha1"],
        )

        self.assertIn("StrictHostKeyChecking=no", command)
        self.assertIn("KexAlgorithms=+diffie-hellman-group14-sha1", command)
        self.assertNotIn("StrictHostKeyChecking=accept-new", command)

    @patch.object(ccrelay_identity, "ensure_cluster_key", return_value=Path("cluster-key"))
    @patch.object(ccrelay_ssh, "ssh_arguments_for", return_value=["-o", "StrictHostKeyChecking=no"])
    @patch.object(ccrelay_ssh, "run_key_command", return_value={"success": True, "latencyMs": 2})
    @patch.object(ccrelay_ssh, "run_authenticated_command", return_value={"success": False, "summary": "sudo denied"})
    def test_identity_verify_falls_back_to_direct_dedicated_login(
            self, run_authenticated, run_key, _ssh_arguments, _cluster_key):
        nodes = [
            {
                "host": "192.0.2.10", "port": 22, "nodeKey": "192.0.2.10:22",
                "bootstrapUsername": "bootstrap", "runtimeUsername": "ccrelay",
                "privilegeSummary": {"uid": "1000", "sshPath": "/usr/bin/ssh", "shPath": "/bin/sh"},
            },
            {
                "host": "192.0.2.11", "port": 22, "nodeKey": "192.0.2.11:22",
                "bootstrapUsername": "bootstrap", "runtimeUsername": "ccrelay",
                "privilegeSummary": {"uid": "1000", "sshPath": "/usr/bin/ssh", "shPath": "/bin/sh"},
            },
        ]

        result = ccrelay_identity.verify(
            "default", "192.0.2.10:22", nodes, True, "ccrelay", 10,
            "SHA256:test", "SHARED_KEYPAIR")

        self.assertEqual("FULL_MESH", result["effectiveCapability"])
        self.assertEqual("READY", result["centerToNodeStatus"])
        self.assertEqual("READY", result["nodeToNodeStatus"])
        self.assertGreaterEqual(run_authenticated.call_count, 3)
        self.assertGreaterEqual(run_key.call_count, 3)

    @patch.object(ccrelay_ssh, "run_authenticated_command", return_value={"success": True, "summary": ""})
    def test_identity_verify_does_not_ssh_from_center_to_itself(self, run_command):
        nodes = [
            {
                "host": "192.0.2.20",
                "port": 22,
                "nodeKey": "192.0.2.20:22",
                "bootstrapUsername": "tester",
                "runtimeUsername": "tester",
                "osType": "LINUX",
                "privilegeSummary": {"shPath": "/bin/sh", "sshPath": "/usr/bin/ssh"},
            },
            {
                "host": "192.0.2.21",
                "port": 22,
                "nodeKey": "192.0.2.21:22",
                "bootstrapUsername": "tester",
                "runtimeUsername": "tester",
                "osType": "LINUX",
                "privilegeSummary": {"shPath": "/bin/sh", "sshPath": "/usr/bin/ssh"},
            },
        ]

        result = ccrelay_identity.verify(
            "default", "192.0.2.20:22", nodes, False, "ccrelay", 10,
        )

        self.assertEqual("READY", result["centerToNodeStatus"])
        self.assertEqual("CENTER_NODE_LOCAL", result["nodes"][0]["centerProbe"]["status"])
        self.assertEqual(1, run_command.call_count)

    @patch.object(ccrelay_cli, "_discover_local_center_pids", return_value=[43210])
    @patch.object(ccrelay_cli, "_process_alive", side_effect=lambda pid: pid == 43210)
    def test_center_status_recovers_process_when_state_file_is_missing(self, _alive, _discover):
        with tempfile.TemporaryDirectory() as temporary_directory:
            skill_root = Path(temporary_directory) / "installed-skill"
            with patch.dict(os.environ, {"CCRELAY_SKILL_ROOT": str(skill_root)}):
                status = ccrelay_cli.center_status(argparse.Namespace())

        self.assertEqual("RUNNING", status["status"])
        self.assertTrue(status["managed"])
        self.assertEqual(43210, status["pid"])

    def test_center_deploy_is_blocked_before_task_creation_without_credentials(self):
        payload = json.dumps(
            {
                "deployMode": "CENTER_DEPLOY",
                "host": "192.0.2.10",
                "port": 22,
                "username": "tester",
            }
        )
        output = self.run_cli(
            "task",
            "create",
            "--session-id",
            "session-1",
            "--task-type",
            "DEPLOY_RELAY",
            "--target-node-id",
            "192.0.2.10:18091",
            "--payload-json",
            payload,
        )

        self.assertEqual("NEED_USER_INPUT", output["status"])
        self.assertFalse(output["taskCreated"])

    def test_remote_deploy_uses_automatic_center_selection_for_local_center(self):
        self.seed_default_ssh_credential()
        with patch.object(ccrelay_ssh, "test_connection", return_value={
            "success": True, "status": "READY", "readyForCenterDeploy": True,
            "username": "tester", "credentialScope": "DEFAULT",
        }):
            result = self.run_cli_result(
                "task", "create", "--session-id", "session-1", "--task-type", "DEPLOY_RELAY",
                "--target-node-id", "192.0.2.10:18091",
                "--payload-json", json.dumps({
                    "deployMode": "CENTER_DEPLOY",
                    "host": "192.0.2.10",
                    "port": 22,
                    "username": "tester",
                    "artifactPath": "C:/bundle",
                    "scriptPath": "C:/bundle/install-relay.sh",
                }),
                extra_env={"CCRELAY_TEST_SSH_SUCCESS": "1"},
            )

        self.assertEqual(0, result.returncode, result.stderr)
        output = json.loads(result.stdout)
        self.assertEqual("NEED_USER_INPUT", output["status"])
        self.assertEqual("NO_ELIGIBLE_CENTER_CANDIDATE", output["failureType"])
        self.assertFalse(output["taskCreated"])
        self.assertEqual("REMOTE_CENTER_BOOTSTRAP", output["blockedStage"])
        self.assertEqual([], output["interaction"]["fields"])
        option_ids = [item["id"] for item in output["interaction"]["options"]]
        self.assertIn("CONFIGURE_STEP_MANUALLY", option_ids)
        self.assertNotIn("PROVIDE_REGISTER_ENDPOINT", option_ids)
        self.assertEqual([], [record for record in RecordingHandler.records if record["path"] == "/api/skill/tasks/create"])

    def test_center_manual_fields_only_appear_after_manual_selection(self):
        automatic = ccrelay_center.plan([], selection="AUTO")
        manual = ccrelay_center.plan([], selection="MANUAL")

        self.assertEqual([], automatic["interaction"]["fields"])
        self.assertEqual("MANUAL_CENTER_CONFIGURATION_REQUIRED", manual["stage"])
        self.assertEqual(["scheme", "host", "port", "basePath"], [
            item["name"] for item in manual["interaction"]["fields"]
        ])

    @patch.object(ccrelay_center, "probe_candidate")
    def test_center_plan_selects_highest_resource_score_deterministically(self, probe):
        def candidate(node, _start, _end, _timeout, _config):
            resources = {
                "192.0.2.10": (4, 2048, 10240, 0.2, 18191, 18192),
                "192.0.2.11": (8, 8192, 51200, 0.5, 18192, 18193),
            }[node["host"]]
            return {
                **node,
                "probeSuccess": True,
                "directoryWritable": True,
                "cpuCores": resources[0],
                "memoryAvailableMb": resources[1],
                "diskAvailableMb": resources[2],
                "loadAverage": resources[3],
                "availablePort": resources[4],
                "relayPort": resources[5],
                "osType": "LINUX",
            }
        probe.side_effect = candidate

        planned = ccrelay_center.plan(["192.0.2.10:22", "192.0.2.11:22"])

        self.assertEqual("CENTER_PLAN_READY", planned["status"])
        self.assertEqual("192.0.2.11:22", planned["selected"]["nodeKey"])
        self.assertEqual("http://192.0.2.11:18192", planned["centerUrl"])
        self.assertEqual(18193, planned["selected"]["relayPort"])
        self.assertGreater(planned["candidates"][1]["score"], planned["candidates"][0]["score"])
        self.assertEqual([], planned["inspection"]["fields"])
        self.assertEqual(
            ["AUTO_EXECUTE_STEP", "CONFIGURE_STEP_MANUALLY", "AUTO_EXECUTE_REMAINING",
             "EXCLUDE_NODES_AND_RETRY", "CANCEL"],
            [item["id"] for item in planned["inspection"]["options"]],
        )

    @patch.object(ccrelay_center, "probe_candidate")
    def test_center_plan_strict_scope_excludes_persisted_managed_nodes(self, probe):
        probe.side_effect = lambda node, _start, _end, _timeout, _config: {
            **node,
            "probeSuccess": True,
            "directoryWritable": True,
            "cpuCores": 4,
            "memoryAvailableMb": 4096,
            "diskAvailableMb": 10240,
            "loadAverage": 0.1,
            "availablePort": 18191,
            "relayPort": 18192,
            "osType": "LINUX",
        }
        config = {
            "clusterIdentity": {
                "managedNodes": [{"host": "192.0.2.99", "port": 22}],
            },
        }
        with patch.object(ccrelay_center.ccrelay_ssh, "load_config", return_value=config):
            planned = ccrelay_center.plan(
                ["192.0.2.20:22"],
                strict_explicit_nodes=True,
            )

        self.assertEqual("192.0.2.20:22", planned["selected"]["nodeKey"])
        self.assertEqual(["192.0.2.20:22"], [item["nodeKey"] for item in planned["candidates"]])

    def test_center_endpoints_are_generated_from_selected_center(self):
        payload = {"commandArguments": ["--server.port=18091", "--wdsavs.ai.remote-cc.relay.center-register-endpoint=http://old"]}

        ccrelay_cli.apply_center_endpoints(payload, "http://192.0.2.20:18191")

        arguments = payload["commandArguments"]
        self.assertIn("--server.port=18091", arguments)
        self.assertIn("--wdsavs.ai.remote-cc.relay.center-register-endpoint=http://192.0.2.20:18191/api/skill/relay/register", arguments)
        self.assertEqual(1, len([item for item in arguments if "center-register-endpoint=" in item]))

    def test_center_bootstrap_defaults_to_fifteen_minute_budget(self):
        args = ccrelay_cli.build_parser().parse_args([
            "center", "bootstrap", "--node", "192.0.2.20:22=tester",
        ])

        self.assertEqual(900, args.bootstrap_timeout)

    def test_center_bootstrap_requires_model_config_before_remote_probe(self):
        args = argparse.Namespace(
            timeout=60,
            force_redeploy=False,
            center="http://127.0.0.1:18191",
            center_configured_by="DEFAULT",
        )
        missing = {
            "ready": False,
            "failureType": "MODEL_CONFIG_REQUIRED",
            "summary": "模型配置不存在",
            "path": "C:/skill/.local/cc-model-config.yml",
        }
        with patch.object(ccrelay_center, "load_state", return_value={}), \
                patch.object(ccrelay_center, "model_config_status", return_value=missing), \
                patch.object(ccrelay_center, "active_center_status") as active_status, \
                patch.object(ccrelay_center, "plan") as plan_center:
            result = ccrelay_cli.center_bootstrap(args)

        self.assertFalse(result["success"])
        self.assertEqual("NEED_USER_INPUT", result["status"])
        self.assertEqual("MODEL_CONFIG_REQUIRED", result["failureType"])
        self.assertEqual("MODEL_CONFIG_PREPARE", result["stage"])
        self.assertEqual("DISCOVER_LOCAL_CONFIG", result["interaction"]["options"][0]["id"])
        self.assertIn("1. 使用检测到的本机模型配置", result["verbatimResponse"])
        self.assertNotIn("DISCOVER_LOCAL_CONFIG", result["verbatimResponse"])
        self.assertNotIn("API key", result["verbatimResponse"])
        self.assertEqual("BOOTSTRAP_EXECUTION_MODE_REQUIRED", result["nextStage"])
        active_status.assert_not_called()
        plan_center.assert_not_called()

    def test_recovery_plan_only_matches_requested_attempt_node(self):
        attempt = {
            "centerUrl": "http://192.0.2.20:18191",
            "nodeKey": "192.0.2.20:22",
            "sshPort": 22,
            "centerPort": 18191,
            "relayPort": 18192,
            "remoteDirectory": "/home/ccrelay/runtime/192.0.2.20-18192",
            "selectedResources": {
                "host": "192.0.2.20",
                "nodeKey": "192.0.2.20:22",
                "osType": "LINUX",
            },
        }
        with patch.object(ccrelay_center, "load_bootstrap_attempt", return_value=attempt):
            matched = ccrelay_center.recovery_plan(["192.0.2.20:22=tester"])
            rejected = ccrelay_center.recovery_plan(["192.0.2.21:22=tester"])

        self.assertEqual("REMOTE_CENTER_RECOVERY", matched["stage"])
        self.assertEqual(18191, matched["selected"]["availablePort"])
        self.assertIsNone(rejected)

    def test_relay_artifacts_do_not_duplicate_extracted_claude_binary(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            bundle = Path(temporary_directory)
            for relative in [
                "install-relay.sh",
                "install-relay.ps1",
                "bundle-manifest.json",
                "bin/prepare_cc_config.py",
                "bin/ccrelay-cli",
                "bin/ccrelay-cli.cmd",
                "bin/start.sh",
                "tools/claude-code-linux-x64.tgz",
            ]:
                path = bundle / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("test", encoding="utf-8")
            (bundle / "config").mkdir()
            (bundle / "bin" / "claude").write_text("large", encoding="utf-8")
            sources = ccrelay_center.relay_artifact_sources(
                bundle,
                {"clusterIdentity": {"managedNodes": [{"osType": "LINUX"}]}},
                "LINUX",
            )

        source_names = {path.as_posix() for path in sources}
        self.assertTrue(any(name.endswith("tools") for name in source_names))
        self.assertTrue(any(name.endswith("bin/prepare_cc_config.py") for name in source_names))
        self.assertTrue(any(name.endswith("bin/ccrelay-cli") for name in source_names))
        self.assertTrue(any(name.endswith("bin/ccrelay-cli.cmd") for name in source_names))
        self.assertTrue(any(name.endswith("bin/start.sh") for name in source_names))
        self.assertFalse(any(name.endswith("bin") for name in source_names))

    def test_relay_bin_files_keep_bin_destination_without_copying_claude_binary(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            bundle = Path(temporary_directory)
            sources = []
            for relative in ["bin/prepare_cc_config.py", "bin/ccrelay-cli", "bin/start.sh"]:
                path = bundle / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("test", encoding="utf-8")
                sources.append(path)

            plan = ccrelay_center.relay_artifact_transfer_plan(
                sources, "/home/ccrelay/runtime", "LINUX"
            )

        destinations = {display: destination for _, destination, display in plan}
        self.assertEqual("/home/ccrelay/runtime/bin", destinations["bin/ccrelay-cli"])
        self.assertEqual("/home/ccrelay/runtime/bin", destinations["bin/start.sh"])

    def test_relay_installers_require_platform_collaboration_cli(self):
        linux_installer = (REPO_ROOT / "scripts" / "install-relay.sh").read_text(encoding="utf-8")
        windows_installer = (REPO_ROOT / "scripts" / "install-relay.ps1").read_text(encoding="utf-8")

        self.assertIn('collaboration_cli="$bundle_dir/bin/ccrelay-cli"', linux_installer)
        self.assertIn("Bundled Relay collaboration CLI not found", linux_installer)
        self.assertIn("$collaborationCli = Join-Path $bundle 'bin/ccrelay-cli.cmd'", windows_installer)
        self.assertIn("Bundled Relay collaboration CLI not found", windows_installer)

    def test_relay_installers_only_use_skill_host_model_config(self):
        linux_installer = (REPO_ROOT / "scripts" / "install-relay.sh").read_text(encoding="utf-8")
        windows_installer = (REPO_ROOT / "scripts" / "install-relay.ps1").read_text(encoding="utf-8")

        self.assertIn("Cluster model configuration was not delivered by the Skill host", linux_installer)
        self.assertIn("unset OPENAI_MODEL OPENAI_BASE_URL OPENAI_API_KEY", linux_installer)
        self.assertNotIn("prepare_cc_config.py --discover", linux_installer)
        self.assertIn("Cluster model configuration was not delivered by the Skill host", windows_installer)
        self.assertIn("Remove-Item -LiteralPath \"Env:$_\"", windows_installer)
        self.assertNotIn("prepare_cc_config.py --discover", windows_installer)

    def test_bash_wrapper_selects_windows_python_under_msys(self):
        runtime_wrapper = (
            REPO_ROOT / "codex-skill" / "ccrelay" / "scripts" / "python-runtime.sh"
        ).read_text(encoding="utf-8")

        self.assertIn("MINGW*|MSYS*|CYGWIN*", runtime_wrapper)
        self.assertIn("python-windows/python.exe", runtime_wrapper)

        cli_wrapper = (
            REPO_ROOT / "codex-skill" / "ccrelay" / "scripts" / "ccrelay-cli.sh"
        ).read_text(encoding="utf-8")
        self.assertIn("cygpath -w", cli_wrapper)
        self.assertIn("MSYS2_ARG_CONV_EXCL='*'", cli_wrapper)

        config_wrapper = (
            REPO_ROOT / "codex-skill" / "ccrelay" / "scripts" / "prepare-cc-config.sh"
        ).read_text(encoding="utf-8")
        self.assertIn("cygpath -w", config_wrapper)
        self.assertIn("MSYS2_ARG_CONV_EXCL='*'", config_wrapper)

    def test_center_remote_directory_renders_runtime_template_variables(self):
        rendered = ccrelay_center.render_remote_directory(
            {
                "host": "192.0.2.20",
                "username": "bootstrap",
                "osType": "LINUX",
            },
            18191,
            {"runtime": {"remoteDirectoryTemplate": "/home/${runtimeUser}/${productName}/${host}-${relayPort}"}},
            {"username": "ccrelay"},
        )

        self.assertEqual(
            "/home/ccrelay/ccrelay/192.0.2.20-18191",
            rendered,
        )

    @patch.object(ccrelay_ssh.subprocess, "run")
    @patch.object(ccrelay_ssh.shutil, "which", return_value="scp")
    def test_scp_remote_target_does_not_embed_shell_quotes(self, _which, run):
        run.return_value = subprocess.CompletedProcess([], 0, stdout="", stderr="")

        result = ccrelay_ssh._run_scp(
            "192.0.2.20",
            22,
            "ccrelay",
            [Path("C:/bundle/app.jar")],
            "/home/ccrelay/ccrelay/192.0.2.20-18191",
            30,
        )

        self.assertTrue(result["success"])
        self.assertEqual(
            "ccrelay@192.0.2.20:/home/ccrelay/ccrelay/192.0.2.20-18191",
            run.call_args.args[0][-1],
        )

    @patch.object(ccrelay_ssh, "resolve_credential", return_value=(None, None))
    @patch.object(ccrelay_ssh.os, "name", "nt")
    @patch.object(ccrelay_ssh.subprocess, "run")
    @patch.object(ccrelay_ssh.shutil, "which", return_value="scp")
    def test_scp_normalizes_windows_source_separators_for_remote_basenames(self, _which, run, _resolve_credential):
        run.return_value = subprocess.CompletedProcess([], 0, stdout="", stderr="")

        result = ccrelay_ssh._run_scp(
            "192.0.2.20",
            22,
            "ccrelay",
            [
                PureWindowsPath(r"C:\Users\tester\.claude\skills\ccrelay\assets\runtime-bundle\ccrelay\app.jar"),
                PureWindowsPath(r"C:\Users\tester\.claude\skills\ccrelay\assets\runtime-bundle\ccrelay\runtime.tar.gz"),
            ],
            "/home/ccrelay/ccrelay/192.0.2.20-18191",
            30,
        )

        command = run.call_args.args[0]
        self.assertTrue(result["success"])
        self.assertIn(
            "C:/Users/tester/.claude/skills/ccrelay/assets/runtime-bundle/ccrelay/app.jar",
            command,
        )
        self.assertIn(
            "C:/Users/tester/.claude/skills/ccrelay/assets/runtime-bundle/ccrelay/runtime.tar.gz",
            command,
        )
        self.assertNotIn(
            r"C:\Users\tester\.claude\skills\ccrelay\assets\runtime-bundle\ccrelay\app.jar",
            command,
        )

    @patch.object(ccrelay_center, "run_command", return_value={"success": True, "summary": ""})
    @patch.object(ccrelay_center, "runtime_access", return_value={"username": "ccrelay"})
    def test_center_reachability_checks_non_hosting_target_nodes(self, _access, run_command):
        result = ccrelay_center.verify_from_candidates(
            "http://192.0.2.20:18191",
            [
                {"nodeKey": "192.0.2.20:22", "probeSuccess": True, "eligible": True},
                {
                    "nodeKey": "192.0.2.21:22",
                    "host": "192.0.2.21",
                    "port": 22,
                    "osType": "LINUX",
                    "probeSuccess": True,
                    "eligible": False,
                },
            ],
            {"nodeKey": "192.0.2.20:22"},
            {},
            20,
        )

        self.assertEqual("READY", result["status"])
        self.assertEqual("192.0.2.21:22", result["verifiedNodes"][0]["nodeKey"])
        run_command.assert_called_once()

    def test_center_bundle_transfer_selects_only_target_os_runtime(self):
        bundle = Path("C:/bundle")
        with patch.object(Path, "is_file", return_value=True):
            linux_sources = ccrelay_center.center_bundle_sources(bundle, "LINUX")
            windows_sources = ccrelay_center.center_bundle_sources(bundle, "WINDOWS")

        self.assertEqual([bundle / "app.jar", bundle / "runtime.tar.gz"], linux_sources)
        self.assertEqual([bundle / "app.jar", bundle / "runtime-windows.zip"], windows_sources)

    def test_relay_artifact_sync_avoids_duplicate_center_runtime(self):
        bundle = Path("C:/bundle")
        linux_config = {"clusterIdentity": {"managedNodes": [{"osType": "LINUX"}]}}
        mixed_config = {
            "clusterIdentity": {
                "managedNodes": [{"osType": "LINUX"}, {"osType": "WINDOWS"}],
            },
        }
        with patch.object(Path, "exists", return_value=True):
            linux_sources = ccrelay_center.relay_artifact_sources(bundle, linux_config, "LINUX")
            mixed_sources = ccrelay_center.relay_artifact_sources(bundle, mixed_config, "LINUX")

        self.assertNotIn(bundle / "runtime.tar.gz", linux_sources)
        self.assertNotIn(bundle / "runtime-windows.zip", linux_sources)
        self.assertIn(bundle / "runtime-windows.zip", mixed_sources)

    def test_relay_artifacts_include_both_platform_installers(self):
        bundle = Path("C:/bundle")
        config = {"clusterIdentity": {"managedNodes": [{"osType": "LINUX"}]}}
        with patch.object(Path, "exists", return_value=True):
            sources = ccrelay_center.relay_artifact_sources(bundle, config, "LINUX")

        self.assertIn(bundle / "install-relay.sh", sources)
        self.assertIn(bundle / "install-relay.ps1", sources)

    @patch.object(ccrelay_center, "run_command", return_value={"success": True})
    @patch.object(ccrelay_center.ccrelay_ssh, "copy_to_remote")
    def test_relay_artifact_directories_are_expanded_to_file_transfers(self, copy_to_remote, run_command):
        copy_to_remote.return_value = {"success": True, "latencyMs": 10}
        with tempfile.TemporaryDirectory() as temporary_directory:
            bundle = Path(temporary_directory)
            config = bundle / "config"
            tools = bundle / "tools"
            config.mkdir()
            tools.mkdir()
            (config / "relay-system-prompt.txt").write_text("prompt", encoding="utf-8")
            (tools / "claude-code.tgz").write_text("archive", encoding="utf-8")

            result = ccrelay_center.copy_relay_artifact_sources(
                {"host": "192.0.2.20", "port": 22, "osType": "LINUX"},
                {"username": "ccrelay", "mode": "DEDICATED_KEY", "keyPath": Path("C:/keys/id_ed25519")},
                [config, tools],
                "/home/ccrelay/runtime",
                60,
            )

        self.assertTrue(result["success"])
        self.assertEqual(2, copy_to_remote.call_count)
        self.assertEqual("relay-system-prompt.txt", copy_to_remote.call_args_list[0].args[3][0].name)
        self.assertEqual("claude-code.tgz", copy_to_remote.call_args_list[1].args[3][0].name)
        self.assertEqual("/home/ccrelay/runtime/config", copy_to_remote.call_args_list[0].args[4])
        self.assertEqual("/home/ccrelay/runtime/tools", copy_to_remote.call_args_list[1].args[4])
        self.assertFalse(copy_to_remote.call_args_list[0].kwargs["allow_password_fallback"])
        run_command.assert_called_once()

    def test_center_relay_start_scripts_use_distinct_registration_and_pid_settings(self):
        linux = ccrelay_center.linux_relay_start_script(
            "/home/ccrelay/ccrelay/node-18192", "192.0.2.20", 18192, 18193, "secret",
        )
        windows = ccrelay_center.windows_relay_start_script(
            "C:/Users/ccrelay/ccrelay/node-18192", "192.0.2.20", 18192, 18193, "secret",
        )

        for script in (linux, windows):
            self.assertIn("18193", script)
            self.assertIn("CENTER_RELAY", script)
            self.assertIn("center-relay.pid", script)
            self.assertIn("center-relay.log", script)
            self.assertIn("WDSAVS_CC_RELAY_PRESERVE_ARCHIVES", script)
            self.assertIn("relay-hmac-secret", script)
            self.assertIn("http://127.0.0.1:18192/api/skill/relay/register", script)
            self.assertIn("http://192.0.2.20:18193/api/ai/remote-cc/chat", script)

    def test_center_start_scripts_reclaim_only_current_bundle_processes_before_port_check(self):
        linux = ccrelay_center.linux_start_script(
            "/home/ccrelay/ccrelay/node-18192", 18192, "secret",
        )
        windows = ccrelay_center.windows_start_script(
            "C:/Users/ccrelay/ccrelay/node-18192", 18192, "secret",
        )

        self.assertIn('index($0, " -jar " app)', linux)
        self.assertIn('app="$bundle/app.jar"', linux)
        self.assertIn("/proc/net/tcp", linux)
        self.assertIn("CCRELAY_CENTER_PORT_IN_USE|port=%s", linux)
        self.assertNotIn('old_pid=$(cat "$bundle/center.pid"', linux)

        self.assertIn("Get-CimInstance Win32_Process", windows)
        self.assertIn("CommandLine.IndexOf($app", windows)
        self.assertIn("TcpListener", windows)
        self.assertIn("CCRELAY_CENTER_PORT_IN_USE|port=18192", windows)
        self.assertNotIn("Get-Content $pidFile", windows)

    @patch.object(ccrelay_center, "run_command", return_value={"success": False, "summary": "relay exited"})
    def test_center_relay_start_failure_is_not_treated_as_ready(self, run_command):
        result = ccrelay_center.start_remote_relay(
            {"host": "192.0.2.20", "port": 22, "osType": "LINUX"},
            {"username": "ccrelay"}, "/home/ccrelay/runtime", 18192, 18193, "secret", 20,
        )

        self.assertFalse(result["success"])
        run_command.assert_called_once()

    def test_center_relay_registration_requires_available_status_role_and_heartbeat(self):
        class Response:
            def __init__(self, payload):
                self.payload = payload

            def __enter__(self):
                return self

            def __exit__(self, *_args):
                return False

            def read(self):
                return json.dumps(self.payload).encode("utf-8")

        payload = [
            {
                "nodeId": "192.0.2.20:18193",
                "host": "192.0.2.20",
                "port": 18193,
                "relayEndpoint": "http://192.0.2.20:18193/api/ai/remote-cc/chat",
                "status": "AVAILABLE",
                "lastHeartbeatTime": "1785239900000",
                "environmentSummary": {"nodeRole": "CENTER_RELAY", "aiReadiness": "READY"},
            },
        ]
        with patch.object(ccrelay_center.urllib.request, "urlopen", return_value=Response(payload)):
            result = ccrelay_center.wait_center_relay_registration(
                "http://192.0.2.20:18192", "192.0.2.20", 18193,
                "http://192.0.2.20:18193/api/ai/remote-cc/chat", 10,
            )

        self.assertTrue(result["success"])
        self.assertEqual("CENTER_RELAY_REGISTERED", result["status"])
        self.assertEqual("192.0.2.20:18193", result["node"]["nodeId"])

    def test_center_bootstrap_fails_when_sidecar_registration_does_not_complete(self):
        plan = {
            "status": "CENTER_PLAN_READY",
            "centerUrl": "http://192.0.2.20:18192",
            "selectionMode": "AUTO",
            "selectionReason": "test",
            "candidates": [],
            "selected": {
                "host": "192.0.2.20",
                "port": 22,
                "nodeKey": "192.0.2.20:22",
                "availablePort": 18192,
                "relayPort": 18193,
                "osType": "LINUX",
            },
        }
        config = {"runtime": {}, "clusterIdentity": {}}
        patches = [
            patch.object(ccrelay_center, "skill_root", return_value=Path("C:/skill")),
            patch.object(Path, "is_file", return_value=True),
            patch.object(ccrelay_center.ccrelay_ssh, "load_config", return_value=config),
            patch.object(ccrelay_center, "runtime_access", return_value={"username": "ccrelay"}),
            patch.object(ccrelay_center, "create_remote_directory", return_value={"success": True}),
            patch.object(ccrelay_center, "center_bundle_sources", return_value=[Path("C:/bundle/app.jar")]),
            patch.object(ccrelay_center.ccrelay_ssh, "copy_to_remote", return_value={"success": True}),
            patch.object(ccrelay_center, "start_remote_center", return_value={"success": True}),
            patch.object(ccrelay_center, "wait_remote_health", return_value={"success": True}),
            patch.object(ccrelay_center, "wait_health", return_value={"status": "UP"}),
            patch.object(ccrelay_center, "verify_from_candidates", return_value={"status": "NOT_APPLICABLE"}),
            patch.object(ccrelay_center, "sync_relay_artifacts", return_value={"success": True}),
            patch.object(ccrelay_center, "start_remote_relay", return_value={"success": True}),
            patch.object(ccrelay_center, "wait_remote_relay_health", return_value={"success": True}),
            patch.object(ccrelay_center, "wait_center_relay_registration", return_value={
                "success": False, "summary": "registration timeout",
            }),
            patch.object(ccrelay_center, "diagnose_remote_center", return_value={"success": True}),
            patch.object(ccrelay_center, "diagnose_remote_relay", return_value={"success": True}),
            patch.object(ccrelay_center, "save_bootstrap_attempt", return_value=Path("C:/attempt.json")),
            patch.object(ccrelay_center, "stop_remote_center", return_value={"success": True}),
            patch.object(ccrelay_center, "save_state", return_value=Path("C:/state.json")),
            patch.object(ccrelay_center.ccrelay_ssh, "save_config"),
        ]
        with ExitStack() as stack:
            for active_patch in patches:
                stack.enter_context(active_patch)
            result = ccrelay_center.bootstrap(plan, "secret", timeout_seconds=10)

        self.assertFalse(result["success"])
        self.assertEqual("CENTER_RELAY_REGISTRATION_FAILED", result["failureType"])
        self.assertNotEqual("REMOTE_CENTER_READY", result["status"])
        self.assertTrue(result["cleanup"]["success"])

    def test_center_bootstrap_includes_relay_diagnostic_before_health_failure_cleanup(self):
        plan = {
            "status": "CENTER_PLAN_READY",
            "centerUrl": "http://192.0.2.20:18192",
            "selectionMode": "AUTO",
            "selectionReason": "test",
            "candidates": [],
            "selected": {
                "host": "192.0.2.20",
                "port": 22,
                "nodeKey": "192.0.2.20:22",
                "availablePort": 18192,
                "relayPort": 18193,
                "osType": "LINUX",
            },
        }
        config = {"runtime": {}, "clusterIdentity": {}}
        diagnostic = {
            "success": True,
            "processAlive": True,
            "localHealth": False,
            "summary": "远端 Relay 进程仍在运行，但本机健康检查未通过",
            "logTail": "relay startup details",
        }
        with patch.object(ccrelay_center, "skill_root", return_value=Path("C:/skill")), \
                patch.object(Path, "is_file", return_value=True), \
                patch.object(ccrelay_center.ccrelay_ssh, "load_config", return_value=config), \
                patch.object(ccrelay_center, "runtime_access", return_value={"username": "ccrelay"}), \
                patch.object(ccrelay_center, "create_remote_directory", return_value={"success": True}), \
                patch.object(ccrelay_center, "center_bundle_sources", return_value=[Path("C:/bundle/app.jar")]), \
                patch.object(ccrelay_center.ccrelay_ssh, "copy_to_remote", return_value={"success": True}), \
                patch.object(ccrelay_center, "start_remote_center", return_value={"success": True}), \
                patch.object(ccrelay_center, "wait_remote_health", return_value={"success": True}), \
                patch.object(ccrelay_center, "wait_health", return_value={"status": "UP"}), \
                patch.object(ccrelay_center, "verify_from_candidates", return_value={"status": "NOT_APPLICABLE"}), \
                patch.object(ccrelay_center, "sync_relay_artifacts", return_value={"success": True}), \
                patch.object(ccrelay_center, "start_remote_relay", return_value={"success": True}), \
                patch.object(ccrelay_center, "wait_remote_relay_health", return_value={
                    "success": False, "summary": "health timeout",
                }), \
                patch.object(ccrelay_center, "diagnose_remote_relay", return_value=diagnostic), \
                patch.object(ccrelay_center, "save_bootstrap_attempt", return_value=Path("C:/attempt.json")), \
                patch.object(ccrelay_center, "stop_remote_center", return_value={"success": True}):
            result = ccrelay_center.bootstrap(plan, "secret", timeout_seconds=10)

        self.assertFalse(result["success"])
        self.assertEqual("CENTER_RELAY_LOCAL_HEALTH_FAILED", result["failureType"])
        self.assertEqual("relay startup details", result["diagnostic"]["logTail"])
        self.assertTrue(result["cleanup"]["success"])

    def test_center_logs_uses_last_failed_bootstrap_attempt(self):
        attempt = {
            "status": "CENTER_BOOTSTRAP_FAILED",
            "phase": "CENTER_RELAY_LOCAL_HEALTH",
            "failureType": "CENTER_RELAY_LOCAL_HEALTH_FAILED",
            "centerUrl": "http://192.0.2.20:18192",
            "nodeKey": "192.0.2.20:22",
            "sshPort": 22,
            "centerPort": 18192,
            "relayPort": 18193,
            "remoteDirectory": "/home/ccrelay/runtime/192.0.2.20-18193",
            "selectedResources": {
                "host": "192.0.2.20",
                "port": 22,
                "nodeKey": "192.0.2.20:22",
                "osType": "LINUX",
            },
        }
        with patch.object(ccrelay_center, "load_state", return_value={}), \
                patch.object(ccrelay_center, "load_bootstrap_attempt", return_value=attempt), \
                patch.object(ccrelay_center.ccrelay_ssh, "load_config", return_value={}), \
                patch.object(ccrelay_center, "runtime_access", return_value={"username": "ccrelay"}), \
                patch.object(ccrelay_center, "diagnose_remote_center", return_value={
                    "success": True, "processAlive": False, "logTail": "center stopped",
                }), \
                patch.object(ccrelay_center, "diagnose_remote_relay", return_value={
                    "success": True, "processAlive": False, "logTail": "relay stopped",
                }):
            result = ccrelay_center.diagnose_persisted_center(10, 80)

        self.assertTrue(result["success"])
        self.assertEqual("LAST_BOOTSTRAP_ATTEMPT", result["stateSource"])
        self.assertEqual("CENTER_RELAY_LOCAL_HEALTH", result["bootstrapPhase"])
        self.assertEqual("CENTER_RELAY_LOCAL_HEALTH_FAILED", result["bootstrapFailureType"])
        self.assertEqual("relay stopped", result["relay"]["logTail"])

    def test_center_logs_attempt_source_ignores_active_center_state(self):
        attempt = {
            "status": "CENTER_BOOTSTRAP_IN_PROGRESS",
            "phase": "RELAY_ARTIFACT_SYNC",
            "centerUrl": "http://192.0.2.20:18193",
            "nodeKey": "192.0.2.20:22",
            "sshPort": 22,
            "centerPort": 18193,
            "relayPort": 18194,
            "remoteDirectory": "/home/ccrelay/runtime/attempt",
            "selectedResources": {"host": "192.0.2.20", "nodeKey": "192.0.2.20:22"},
        }
        with patch.object(ccrelay_center, "load_state", return_value={
                    "centerUrl": "http://192.0.2.20:18191",
                    "remoteDirectory": "/home/ccrelay/runtime/active",
                }), \
                patch.object(ccrelay_center, "load_bootstrap_attempt", return_value=attempt), \
                patch.object(ccrelay_center.ccrelay_ssh, "load_config", return_value={}), \
                patch.object(ccrelay_center, "runtime_access", return_value={"username": "ccrelay"}), \
                patch.object(ccrelay_center, "diagnose_remote_center", return_value={"success": True}), \
                patch.object(ccrelay_center, "diagnose_remote_relay", return_value={"success": True}):
            result = ccrelay_center.diagnose_persisted_center(10, 80, "ATTEMPT")

        self.assertEqual("LAST_BOOTSTRAP_ATTEMPT", result["stateSource"])
        self.assertEqual("/home/ccrelay/runtime/attempt", result["remoteDirectory"])

    def test_active_center_status_reuses_healthy_registered_center(self):
        state = {
            "centerUrl": "http://192.0.2.20:18191",
            "nodeKey": "192.0.2.20:22",
            "sshPort": 22,
            "relayPort": 18192,
            "relayEndpoint": "http://192.0.2.20:18192/api/ai/remote-cc/chat",
            "remoteDirectory": "/home/ccrelay/runtime/active",
            "selectedResources": {"host": "192.0.2.20", "nodeKey": "192.0.2.20:22"},
        }
        with patch.object(ccrelay_center, "load_state", return_value=state), \
                patch.object(ccrelay_center.ccrelay_ssh, "load_config", return_value={}), \
                patch.object(ccrelay_center, "runtime_access", return_value={"username": "ccrelay"}), \
                patch.object(ccrelay_center, "wait_health", return_value={"status": "UP"}), \
                patch.object(ccrelay_center, "wait_remote_relay_health", return_value={
                    "success": True, "status": "UP",
                }), \
                patch.object(ccrelay_center, "wait_center_relay_registration", return_value={
                    "success": True, "node": {"nodeId": "192.0.2.20:18192"},
                }):
            result = ccrelay_center.active_center_status(10)

        self.assertTrue(result["success"])
        self.assertTrue(result["alreadyReady"])
        self.assertEqual("REMOTE_CENTER_READY", result["status"])

    def test_center_force_redeploy_reuses_current_node_and_preserves_remote_state(self):
        active = {
            "status": "REMOTE_CENTER_READY",
            "centerUrl": "http://192.0.2.20:18191",
            "relayPort": 18192,
            "selected": {
                "host": "192.0.2.20",
                "port": 22,
                "availablePort": 18191,
                "relayPort": 18192,
            },
        }
        planned = {
            "status": "CENTER_PLAN_READY",
            "centerUrl": "http://192.0.2.20:18191",
            "selected": active["selected"],
        }
        ready = {
            "success": True,
            "status": "REMOTE_CENTER_READY",
            "centerUrl": "http://192.0.2.20:18191",
            "selected": active["selected"],
        }
        args = argparse.Namespace(
            timeout=60,
            force_redeploy=True,
            connect_timeout=15,
            bootstrap_timeout=900,
            center="http://192.0.2.20:18191",
            center_configured_by="PERSISTED",
            cluster_id="default",
            operator_id="tester",
        )
        with patch.object(ccrelay_center, "load_state", return_value={}), \
                patch.object(ccrelay_center, "model_config_bootstrap_gate", return_value={"ready": True}), \
                patch.object(ccrelay_center, "active_center_status", return_value=active), \
                patch.object(ccrelay_center, "stop_persisted_center", return_value={
                    "success": True, "status": "REMOTE_CENTER_STOPPED",
                }) as stop_center, \
                patch.object(ccrelay_center, "plan", return_value=planned) as plan_center, \
                patch.object(ccrelay_center, "bootstrap", return_value=ready) as bootstrap_center, \
                patch.object(ccrelay_cli, "sync_identity_to_selected_center", return_value={"status": "READY"}):
            result = ccrelay_cli.center_bootstrap(args)

        self.assertTrue(result["success"])
        self.assertTrue(result["rollingRedeploy"])
        self.assertEqual("REMOTE_CENTER_STOPPED", result["previousRemoteCenter"]["status"])
        stop_center.assert_called_once()
        self.assertEqual(["192.0.2.20:22"], plan_center.call_args.args[0])
        self.assertTrue(plan_center.call_args.kwargs["strict_explicit_nodes"])
        bootstrap_center.assert_called_once()

    def test_reclaim_requires_health_registration_and_cross_node_reachability(self):
        plan = {
            "centerUrl": "http://192.0.2.20:18191",
            "candidates": [],
        }
        selected = {
            "host": "192.0.2.20",
            "port": 22,
            "nodeKey": "192.0.2.20:22",
            "osType": "LINUX",
        }
        with patch.object(ccrelay_center, "wait_remote_health", return_value={"success": True}), \
                patch.object(ccrelay_center, "wait_health", return_value={"status": "UP"}), \
                patch.object(ccrelay_center, "wait_remote_relay_health", return_value={
                    "success": True, "status": "UP",
                }), \
                patch.object(ccrelay_center, "wait_center_relay_registration", return_value={
                    "success": True, "node": {"nodeId": "192.0.2.20:18192"},
                }), \
                patch.object(ccrelay_center, "verify_from_candidates", return_value={"status": "READY"}):
            result = ccrelay_center.probe_reclaimable_center(
                plan, selected, {}, {"username": "ccrelay"}, "/home/ccrelay/runtime",
                18191, 18192, 10,
            )

        self.assertTrue(result["success"])
        self.assertEqual("REMOTE_CENTER_RECLAIMABLE", result["status"])

    @unittest.skipUnless(os.name == "nt", "Windows process output regression")
    def test_process_alive_handles_system_code_page_output(self):
        completed = subprocess.CompletedProcess(
            args=["tasklist"], returncode=0, stdout=None, stderr=None,
        )
        with patch.object(ccrelay_cli.subprocess, "run", return_value=completed) as run:
            self.assertFalse(ccrelay_cli._process_alive(1234))
        self.assertEqual("utf-8", run.call_args.kwargs["encoding"])
        self.assertEqual("replace", run.call_args.kwargs["errors"])

    def test_identity_success_requires_execution_mode_choice(self):
        interaction = ccrelay_cli.bootstrap_execution_mode_interaction({"effectiveCapability": "FULL_MESH"})

        self.assertEqual("BOOTSTRAP_EXECUTION_MODE_REQUIRED", interaction["stage"])
        self.assertEqual([], interaction["fields"])
        self.assertEqual(
            ["AUTO_EXECUTE_REMAINING", "INSPECT_STEP_BY_STEP", "CANCEL"],
            [item["id"] for item in interaction["options"]],
        )
        self.assertEqual(
            ["RESOURCE_DISCOVERY", "CENTER_SELECTION", "SSH_IDENTITY_INITIALIZATION"],
            [item["id"] for item in interaction["steps"][:3]],
        )
        self.assertIn("1. 自动完成剩余部署", interaction["verbatimResponse"])
        self.assertIn("2. 逐步检视", interaction["verbatimResponse"])
        self.assertNotIn("AUTO_EXECUTE_REMAINING", interaction["verbatimResponse"])

    def test_self_replicate_deploy_does_not_predict_missing_center_ssh_credentials(self):
        payload = json.dumps(
            {
                "deployMode": "SELF_REPLICATE",
                "enableCenterFallback": True,
                "host": "192.0.2.10",
                "port": 22,
                "username": "tester",
            }
        )
        output = self.run_cli(
            "task",
            "create",
            "--session-id",
            "session-1",
            "--task-type",
            "DEPLOY_RELAY",
            "--target-node-id",
            "192.0.2.10:18091",
            "--payload-json",
            payload,
        )

        self.assertTrue(output["accepted"])
        task_record = next(record for record in RecordingHandler.records if record["path"] == "/api/skill/tasks/create")
        task_payload = task_record["body"]["payload"]
        self.assertNotIn("sshCredentialPreflightRequiredOnFallback", task_payload)
        command_arguments = task_payload["commandArguments"]
        self.assertTrue(any("center-register-endpoint=" in item for item in command_arguments))
        self.assertTrue(any("center-heartbeat-endpoint=" in item for item in command_arguments))
        self.assertTrue(any("center-grant-validate-endpoint=" in item for item in command_arguments))
        self.assertNotIn("--wdsavs.ai.remote-cc.relay.host=192.0.2.10", command_arguments)
        self.assertIn(
            "--wdsavs.ai.remote-cc.relay.relay-endpoint=http://192.0.2.10:18091/api/ai/remote-cc/chat",
            command_arguments,
        )

    def test_deploy_task_without_session_opens_session_before_create(self):
        output = self.run_cli(
            "task",
            "create",
            "--task-type",
            "DEPLOY_RELAY",
            "--target-node-id",
            "192.0.2.10:18091",
            "--payload-json",
            json.dumps({
                "deployMode": "SELF_REPLICATE",
                "enableCenterFallback": True,
                "host": "192.0.2.10",
                "port": 22,
                "username": "tester",
            }),
        )

        post_paths = [record["path"] for record in RecordingHandler.records if record["method"] == "POST"]
        self.assertLess(post_paths.index("/api/skill/session/open"), post_paths.index("/api/skill/tasks/create"))
        task_record = next(record for record in RecordingHandler.records if record["path"] == "/api/skill/tasks/create")
        self.assertEqual("session-agent", task_record["body"]["sessionId"])
        self.assertTrue(output["accepted"])

    def test_deploy_task_reuses_provided_session_without_opening_another(self):
        self.run_cli(
            "task",
            "create",
            "--session-id",
            "session-existing",
            "--task-type",
            "DEPLOY_RELAY",
            "--target-node-id",
            "192.0.2.10:18091",
            "--payload-json",
            json.dumps({"deployMode": "SELF_REPLICATE"}),
        )

        self.assertFalse(any(record["path"] == "/api/skill/session/open" for record in RecordingHandler.records))
        task_record = next(record for record in RecordingHandler.records if record["path"] == "/api/skill/tasks/create")
        self.assertEqual("session-existing", task_record["body"]["sessionId"])

    def test_deploy_task_from_json_without_session_opens_session(self):
        output = self.run_cli(
            "task",
            "create",
            "--json",
            json.dumps({
                "taskType": "DEPLOY_RELAY",
                "targetNodeId": "192.0.2.10:18091",
            }),
            "--payload-json",
            json.dumps({
                "deployMode": "SELF_REPLICATE",
                "enableCenterFallback": True,
                "host": "192.0.2.10",
                "port": 22,
                "username": "tester",
            }),
        )

        task_record = next(record for record in RecordingHandler.records if record["path"] == "/api/skill/tasks/create")
        self.assertEqual("DEPLOY_RELAY", task_record["body"]["taskType"])
        self.assertEqual("session-agent", task_record["body"]["sessionId"])
        self.assertTrue(output["accepted"])

    def test_deploy_batch_expands_targets_opens_one_session_and_respects_concurrency(self):
        active = 0
        maximum = 0
        lock = threading.Lock()
        submitted = []
        opened_sessions = []

        def request(_args, method, path, body=None, **_kwargs):
            nonlocal active, maximum
            if path == "/api/skill/session/open":
                opened_sessions.append(body)
                return {"sessionId": "session-batch"}
            if path == "/api/skill/tasks/create":
                with lock:
                    active += 1
                    maximum = max(maximum, active)
                    submitted.append(body)
                time.sleep(0.03)
                with lock:
                    active -= 1
                return {"taskId": body["taskId"], "status": "ACCEPTED"}
            return {}

        args = argparse.Namespace(
            target_node_ids=["node-a:18091,node-b:18091", "node-b:18091,node-c:18091"],
            session_id=None, request_id=None, parent_task_id=None, task_type="DEPLOY_RELAY",
            source_node_id="node-source:18091", payload_json='{"deployMode":"SELF_REPLICATE"}',
            payload_file=None, timeout_ms=600000, concurrency=2, json=None, json_file=None,
            center=self.center, center_configured_by="TEST", timeout=5.0,
        )
        with patch.object(ccrelay_cli, "request_json", side_effect=request), \
                patch.object(ccrelay_cli, "enrich_deploy_payload"), \
                patch.object(ccrelay_cli, "apply_center_endpoints"):
            result = ccrelay_cli.task_create_batch(args)

        self.assertEqual("session-batch", result["sessionId"])
        self.assertEqual(3, result["targetCount"])
        self.assertEqual(3, result["createdCount"])
        self.assertEqual(2, maximum)
        self.assertEqual(["node-a:18091", "node-b:18091", "node-c:18091"],
                         [item["targetNodeId"] for item in result["results"]])
        self.assertEqual(1, len(opened_sessions))
        self.assertEqual(3, len({item["taskId"] for item in submitted}))

    def test_deploy_batch_isolates_child_submission_failure(self):
        submitted = []

        def request(_args, method, path, body=None, **_kwargs):
            if path == "/api/skill/session/open":
                return {"sessionId": "session-batch"}
            if path == "/api/skill/tasks/create":
                submitted.append(body["targetNodeId"])
                if body["targetNodeId"] == "node-b:18091":
                    raise CliError("simulated child failure")
                return {"taskId": body["taskId"], "status": "ACCEPTED"}
            return {}

        args = argparse.Namespace(
            target_node_ids=["node-a:18091,node-b:18091,node-c:18091"], session_id=None,
            request_id=None, parent_task_id=None, task_type="DEPLOY_RELAY",
            source_node_id=None, payload_json='{"deployMode":"SELF_REPLICATE"}', payload_file=None,
            timeout_ms=None, concurrency=2, json=None, json_file=None, center=self.center,
            center_configured_by="TEST", timeout=5.0,
        )
        with patch.object(ccrelay_cli, "request_json", side_effect=request), \
                patch.object(ccrelay_cli, "enrich_deploy_payload"), \
                patch.object(ccrelay_cli, "apply_center_endpoints"):
            result = ccrelay_cli.task_create_batch(args)

        self.assertEqual(2, result["createdCount"])
        statuses = {item["targetNodeId"]: item["status"] for item in result["results"]}
        self.assertEqual("CREATED", statuses["node-a:18091"])
        self.assertEqual("CREATE_FAILED", statuses["node-b:18091"])
        self.assertEqual("CREATED", statuses["node-c:18091"])
        self.assertEqual(3, len(submitted))

    def test_deploy_batch_rejects_invalid_concurrency(self):
        with self.assertRaises(Exception):
            ccrelay_cli.parse_ssh_concurrency("0")
        with self.assertRaises(Exception):
            ccrelay_cli.parse_ssh_concurrency("33")

    def test_deploy_batch_parser_accepts_comma_separated_targets(self):
        with patch.dict(os.environ, {"CCRELAY_SKILL_ROOT": self.temporary_directory.name}):
            args = ccrelay_cli.build_parser().parse_args([
                "task", "create-batch", "--target-node-ids", "node-a:18091,node-b:18091",
                "--concurrency", "2",
            ])
        self.assertEqual(["node-a:18091,node-b:18091"], args.target_node_ids)
        self.assertEqual(2, args.concurrency)
        self.assertEqual("DEPLOY_RELAY", args.task_type)

    def test_deploy_payload_infers_runtime_identity_from_target_node(self):
        args = argparse.Namespace(target_node_id="192.0.2.10:18091")
        payload = {"deployMode": "SELF_REPLICATE"}
        config = {
            "clusterIdentity": {
                "accountMode": "DEDICATED_MANAGED",
                "dedicatedAccount": {"username": "local-ccrelay"},
                "managedNodes": [
                    {"host": "192.0.2.10", "port": 22, "runtimeUsername": "local-ccrelay"},
                ],
            },
        }
        with patch.object(ccrelay_ssh, "load_config", return_value=config), \
                patch.object(ccrelay_cli, "request_json", return_value={
                    "configured": True,
                    "dedicatedUsername": "center-ccrelay",
                    "nodes": [
                        {"nodeKey": "192.0.2.10:22", "runtimeUsername": "center-ccrelay"},
                    ],
                }):
            ccrelay_cli.enrich_deploy_payload(args, payload)

        self.assertEqual(
            {"deployMode": "SELF_REPLICATE", "host": "192.0.2.10", "relayPort": 18091,
             "replaceExistingRelay": True, "port": 22, "username": "center-ccrelay"},
            payload,
        )

    def test_deploy_payload_resolves_registered_machine_name_to_ssh_host(self):
        args = argparse.Namespace(target_node_id="VM-0-17-ubuntu:18192", center="http://center:18191")
        payload = {"deployMode": "SELF_REPLICATE"}
        config = {
            "clusterIdentity": {
                "accountMode": "DEDICATED_MANAGED",
                "dedicatedAccount": {"username": "local-ccrelay"},
                "managedNodes": [],
            },
        }
        responses = iter([
            {
                "nodeId": "VM-0-17-ubuntu:18192",
                "host": "VM-0-17-ubuntu",
                "port": 18192,
                "relayEndpoint": "http://111.229.32.85:18192/api/ai/remote-cc/chat",
            },
            {
                "configured": True,
                "dedicatedUsername": "center-ccrelay",
                "nodes": [{"nodeKey": "111.229.32.85:22", "runtimeUsername": "center-ccrelay"}],
            },
        ])
        with patch.object(ccrelay_ssh, "load_config", return_value=config), \
                patch.object(ccrelay_cli, "request_json", side_effect=lambda *args, **kwargs: next(responses)):
            ccrelay_cli.enrich_deploy_payload(args, payload)

        self.assertEqual("111.229.32.85", payload["host"])
        self.assertEqual(18192, payload["relayPort"])
        self.assertEqual("center-ccrelay", payload["username"])

    def test_task_create_rejects_missing_session_before_http_request(self):
        result = self.run_cli_result("task", "create", "--task-type", "GENERIC")

        self.assertEqual(2, result.returncode)
        self.assertIn("sessionId is required", result.stderr)
        self.assertEqual([], [record for record in RecordingHandler.records if record["path"] == "/api/skill/tasks/create"])

    def test_access_validate_signs_hmac_payload(self):
        self.run_cli(
            "access",
            "validate",
            "--grant-id",
            "grant-1",
            "--session-id",
            "session-1",
            "--source-node-id",
            "node-a:18091",
            "--target-node-id",
            "node-b:18091",
            "--signed-token",
            "signed-token",
            "--capabilities",
            "A2A_MESSAGE_SEND",
            "--expires-at",
            "1785239900000",
        )

        body = RecordingHandler.records[0]["body"]
        payload = "|".join(
            [
                "grant-1",
                "session-1",
                "node-a:18091",
                "node-b:18091",
                "A2A_MESSAGE_SEND",
                "1785239900000",
                "signed-token",
                body["requestTimestamp"],
                body["requestNonce"],
            ]
        )
        expected = base64.urlsafe_b64encode(
            hmac.new(SECRET.encode("utf-8"), payload.encode("utf-8"), hashlib.sha256).digest()
        ).decode("ascii").rstrip("=")

        self.assertEqual("/api/skill/relay/access/validate", RecordingHandler.records[0]["path"])
        self.assertEqual(expected, body["requestSignature"])

    def test_a2a_message_send_builds_json_rpc_body(self):
        self.run_cli(
            "a2a",
            "message-send",
            "--id",
            "rpc-1",
            "--session-id",
            "session-1",
            "--grant-id",
            "grant-1",
            "--signed-token",
            "token-1",
            "--source-node-id",
            "node-a:18091",
            "--target-node-id",
            "node-b:18091",
            "--target-relay-endpoint",
            "http://node-b:18091/api/ai/remote-cc/chat",
            "--center-grant-validate-endpoint",
            self.center + "/api/skill/relay/access/validate",
            "--prompt",
            "hello",
        )

        body = RecordingHandler.records[0]["body"]
        self.assertEqual("/api/skill/a2a/message/send", RecordingHandler.records[0]["path"])
        self.assertEqual("2.0", body["jsonrpc"])
        self.assertEqual("rpc-1", body["id"])
        self.assertEqual("message/send", body["method"])
        self.assertEqual("hello", body["params"]["messages"][0]["content"])
        self.assertEqual("grant-1", body["params"]["grantId"])

    def test_model_config_file_does_not_send_api_key(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            config_path = Path(temp_dir) / "cc-model-config.yml"
            config_path.write_text(
                "\n".join(
                    [
                        "provider: deepseek-anthropic-compatible",
                        "model: deepseek-v4-pro",
                        "baseUrl: https://api.deepseek.com/anthropic",
                        "apiKey: should-not-be-sent",
                        "relayCommand: claude",
                    ]
                ),
                encoding="utf-8",
            )

            self.run_cli(
                "a2a",
                "message-send",
                "--id",
                "rpc-2",
                "--prompt",
                "hello",
                "--model-config-file",
                str(config_path),
            )

        model_config = RecordingHandler.records[0]["body"]["params"]["modelConfig"]
        self.assertEqual("deepseek-v4-pro", model_config["model"])
        self.assertEqual("https://api.deepseek.com/anthropic", model_config["endpoint"])
        self.assertEqual("claude", model_config["claudeCode"]["command"])
        self.assertNotIn("apiKey", model_config)

    def test_prepare_model_config_does_not_write_when_canary_fails(self):
        arguments = [
            "prepare_cc_config.py",
            "--write",
            "C:/skill/.local/cc-model-config.yml",
            "--test",
            "--model",
            "test-model",
            "--base-url",
            "https://example.invalid/anthropic",
            "--api-key",
            "test-key",
        ]
        output = io.StringIO()
        with patch.object(sys, "argv", arguments), \
                patch.object(prepare_cc_config, "test_anthropic_compatible", return_value=(False, "HTTP 402")), \
                patch.object(prepare_cc_config, "write_config") as write_config, \
                redirect_stdout(output):
            exit_code = prepare_cc_config.main()

        self.assertEqual(2, exit_code)
        write_config.assert_not_called()
        response = json.loads(output.getvalue())
        self.assertEqual("NEED_USER_INPUT", response["status"])
        self.assertEqual("MODEL_CONFIG_TEST_UNAVAILABLE", response["stage"])
        self.assertEqual("INSUFFICIENT_BALANCE", response["failureType"])
        self.assertNotIn("apiKey", response["currentConfig"])
        self.assertEqual(
            ["RETRY_CURRENT_CONFIG", "REPLACE_MODEL_CONFIG", "CANCEL"],
            [item["id"] for item in response["interaction"]["options"]],
        )
        self.assertEqual(
            ["LAUNCH_SECURE_TERMINAL", "SECURE_TERMINAL_COMMAND", "MANUAL_VISIBLE_INPUT"],
            [item["id"] for item in response["interaction"]["apiKeyInput"]["options"]],
        )

    def test_discover_model_config_reads_claude_code_settings(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            home = Path(temporary_directory)
            settings_path = home / ".claude" / "settings.json"
            settings_path.parent.mkdir(parents=True)
            settings_path.write_text(
                json.dumps({
                    "env": {
                        "ANTHROPIC_AUTH_TOKEN": "settings-token",
                        "ANTHROPIC_BASE_URL": "https://model.example.test",
                        "ANTHROPIC_DEFAULT_SONNET_MODEL": "provider-model",
                    }
                }),
                encoding="utf-8",
            )
            with patch.object(prepare_cc_config.Path, "home", return_value=home), \
                    patch.dict(os.environ, {}, clear=True):
                candidates = prepare_cc_config.discover_local_config()

        self.assertEqual(1, len(candidates))
        self.assertEqual(f"claude-settings:{settings_path}", candidates[0]["source"])
        self.assertEqual("settings-token", candidates[0]["apiKey"])
        self.assertEqual("https://model.example.test", candidates[0]["baseUrl"])
        self.assertEqual("provider-model", candidates[0]["model"])

    def test_discover_model_config_does_not_invent_missing_model(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            home = Path(temporary_directory)
            settings_path = home / ".claude" / "settings.json"
            settings_path.parent.mkdir(parents=True)
            settings_path.write_text(
                json.dumps({
                    "env": {
                        "ANTHROPIC_AUTH_TOKEN": "settings-token",
                        "ANTHROPIC_BASE_URL": "https://model.example.test",
                    }
                }),
                encoding="utf-8",
            )
            with patch.object(prepare_cc_config.Path, "home", return_value=home), \
                    patch.dict(os.environ, {}, clear=True):
                candidates = prepare_cc_config.discover_local_config()

        self.assertEqual(1, len(candidates))
        self.assertEqual("", candidates[0]["model"])
        self.assertNotEqual(prepare_cc_config.DEFAULT_MODEL, candidates[0]["model"])

    def test_discover_command_requires_source_choice_before_missing_fields(self):
        candidate = {
            "source": "claude-settings:C:/Users/test/.claude/settings.json",
            "provider": "deepseek-anthropic-compatible",
            "model": "",
            "baseUrl": "https://model.example.test",
            "apiKey": "settings-token",
            "apiPath": "/v1/messages",
        }
        output = io.StringIO()
        with patch.object(prepare_cc_config, "discover_local_config", return_value=[candidate]), \
                patch.object(sys, "argv", ["prepare_cc_config.py", "--discover"]), \
                redirect_stdout(output):
            exit_code = prepare_cc_config.main()

        response = json.loads(output.getvalue())
        self.assertEqual(0, exit_code)
        self.assertEqual("NEED_USER_INPUT", response["status"])
        self.assertEqual("MODEL_CONFIG_SOURCE_REQUIRED", response["stage"])
        self.assertEqual("RETURN_VERBATIM_RESPONSE_AND_STOP", response["agentAction"])
        self.assertEqual(
            ["USE_DETECTED_LOCAL_CONFIG", "CONFIGURE_NEW_MODEL", "CANCEL"],
            [item["id"] for item in response["interaction"]["options"]],
        )
        self.assertIn("1. 使用检测到的本机模型配置", response["verbatimResponse"])
        self.assertIn("2. 填写新的模型配置", response["verbatimResponse"])
        self.assertNotIn("请填写", response["verbatimResponse"])
        self.assertNotIn("settings-token", response["verbatimResponse"])

    def test_discover_command_without_candidates_only_offers_new_configuration(self):
        output = io.StringIO()
        with patch.object(prepare_cc_config, "discover_local_config", return_value=[]), \
                patch.object(sys, "argv", ["prepare_cc_config.py", "--discover"]), \
                redirect_stdout(output):
            exit_code = prepare_cc_config.main()

        response = json.loads(output.getvalue())
        self.assertEqual(0, exit_code)
        self.assertEqual(
            ["CONFIGURE_NEW_MODEL", "CANCEL"],
            [item["id"] for item in response["interaction"]["options"]],
        )
        self.assertIn("未检测到可复用的本机模型配置", response["verbatimResponse"])

    def test_discover_model_config_supports_anthropic_auth_token_environment(self):
        environment = {
            "ANTHROPIC_AUTH_TOKEN": "environment-token",
            "ANTHROPIC_BASE_URL": "https://environment.example.test",
        }
        with tempfile.TemporaryDirectory() as temporary_directory, \
                patch.object(prepare_cc_config.Path, "home", return_value=Path(temporary_directory)), \
                patch.dict(os.environ, environment, clear=True):
            candidates = prepare_cc_config.discover_local_config()

        self.assertEqual("environment-token", candidates[0]["apiKey"])
        self.assertEqual("https://environment.example.test", candidates[0]["baseUrl"])

    def test_prepare_model_config_returns_three_api_key_input_modes(self):
        output = io.StringIO()
        arguments = [
            "prepare_cc_config.py",
            "--input-options",
            "--test",
            "--write",
            "C:/skill/.local/cc-model-config.yml",
            "--model",
            "test-model",
            "--base-url",
            "https://example.invalid/anthropic",
        ]
        with patch.object(sys, "argv", arguments), redirect_stdout(output):
            exit_code = prepare_cc_config.main()

        self.assertEqual(0, exit_code)
        response = json.loads(output.getvalue())
        self.assertEqual(
            ["LAUNCH_SECURE_TERMINAL", "SECURE_TERMINAL_COMMAND", "MANUAL_VISIBLE_INPUT"],
            [item["id"] for item in response["options"]],
        )
        self.assertIn("1. 安全终端弹窗", response["verbatimResponse"])
        self.assertNotIn("LAUNCH_SECURE_TERMINAL", response["verbatimResponse"])
        self.assertNotIn("prepare-cc-config", response["verbatimResponse"])
        self.assertTrue(response["userChoiceRequired"])
        self.assertTrue(all(not item["recommended"] for item in response["options"]))
        self.assertIn("高风险", response["options"][0]["applicability"])
        self.assertIn("无桌面 GUI", response["options"][1]["applicability"])
        self.assertIn("低风险", response["options"][2]["applicability"])
        self.assertIn("prepare-cc-config", response["options"][1]["command"])
        self.assertTrue(response["options"][2]["securityWarning"])
        self.assertEqual(
            ["apiKey"],
            [field["name"] for field in response["options"][2]["fields"]],
        )

    def test_prepare_model_config_powershell_wrapper_forces_utf8_output(self):
        wrapper = (
            REPO_ROOT / "codex-skill" / "ccrelay" / "scripts" / "prepare-cc-config.ps1"
        ).read_text(encoding="utf-8")

        self.assertIn("$env:PYTHONIOENCODING = 'utf-8'", wrapper)

    def test_prepare_model_config_windows_command_protects_api_path_from_msys_conversion(self):
        with patch.object(prepare_cc_config.os, "name", "nt"):
            command = prepare_cc_config.secure_terminal_command([
                "--prompt-api-key",
                "--api-path",
                "/v1/messages",
            ])

        self.assertIn(" -Command ", command)
        self.assertNotIn(" -File ", command)
        self.assertIn("'--api-path' '/v1/messages'", command)

    def test_prepare_model_config_can_launch_secure_terminal(self):
        capabilities = {
            "os": "WINDOWS",
            "ttyAvailable": False,
            "guiTerminalLauncherDetected": True,
            "secureTerminalLauncher": "powershell.exe",
        }
        with patch.object(prepare_cc_config, "input_capabilities", return_value=capabilities), \
                patch.object(prepare_cc_config.subprocess, "Popen") as process:
            response = prepare_cc_config.launch_secure_terminal(["--prompt-api-key", "--test"])

        self.assertEqual("SECURE_TERMINAL_LAUNCHED", response["status"])
        self.assertIn("--prompt-api-key", response["command"])
        process.assert_called_once()

    def test_prepare_model_config_accepts_api_key_from_environment(self):
        output = io.StringIO()
        arguments = [
            "prepare_cc_config.py",
            "--test",
            "--write",
            "C:/skill/.local/cc-model-config.yml",
            "--api-key-env",
            "CCRELAY_TEST_API_KEY",
        ]
        with patch.object(sys, "argv", arguments), \
                patch.dict(os.environ, {"CCRELAY_TEST_API_KEY": "test-key"}), \
                patch.object(prepare_cc_config, "test_anthropic_compatible", return_value=(True, "ok")) as canary, \
                patch.object(prepare_cc_config, "write_config") as write_config, \
                redirect_stdout(output):
            exit_code = prepare_cc_config.main()

        self.assertEqual(0, exit_code)
        self.assertEqual("test-key", canary.call_args.args[0]["apiKey"])
        self.assertEqual("test-key", write_config.call_args.args[1]["apiKey"])
        self.assertNotIn("test-key", output.getvalue())

    def test_a2a_message_send_autocompletes_context(self):
        self.run_cli(
            "a2a",
            "message-send",
            "--id",
            "rpc-auto",
            "--grant-id",
            "grant-auto",
            "--target-node-id",
            "node-b:18091",
            "--prompt",
            "hello",
        )

        self.assertEqual("GET", RecordingHandler.records[0]["method"])
        self.assertEqual("/api/skill/relay/access/grant-auto", RecordingHandler.records[0]["path"])
        self.assertEqual("GET", RecordingHandler.records[1]["method"])
        self.assertEqual("/api/skill/relay/nodes/node-b%3A18091", RecordingHandler.records[1]["path"])
        body = RecordingHandler.records[2]["body"]
        self.assertEqual("/api/skill/a2a/message/send", RecordingHandler.records[2]["path"])
        self.assertEqual("session-auto", body["params"]["sessionId"])
        self.assertIn(".", body["params"]["signedToken"])
        self.assertEqual("http://node-b:18091/relay", body["params"]["targetRelayEndpoint"])
        self.assertEqual(self.center + "/api/skill/relay/access/validate", body["params"]["centerGrantValidateEndpoint"])

    def test_task_observe_uses_windowed_query(self):
        output = self.run_cli(
            "task",
            "observe",
            "task-1",
            "--since-sequence-no",
            "7",
            "--since-created-time-ms",
            "1785230000000",
            "--last-ms",
            "9000",
            "--limit",
            "9",
            "--tail-lines",
            "4",
            "--max-bytes",
            "1024",
            "--per-event-max-bytes",
            "256",
            "--include",
            "task,events,control",
            "--event-types",
            "STEP_STARTED,STEP_FINISHED",
        )

        parsed = urlparse(RecordingHandler.records[0]["path"])
        query = parse_qs(parsed.query)
        self.assertEqual("/api/skill/observations/tasks/task-1", parsed.path)
        self.assertEqual(["7"], query["sinceSequenceNo"])
        self.assertEqual(["9"], query["limit"])
        self.assertEqual(["task", "events", "control"], query["include"])
        self.assertEqual("RUNNING", output["status"])
        self.assertEqual(1, len(output["events"]))

    def test_agent_observe_supports_batch_window(self):
        output = self.run_cli(
            "agent",
            "observe",
            "--task-ids",
            "task-a,task-b",
            "--parent-task-id",
            "parent-1",
            "--limit",
            "20",
        )

        parsed = urlparse(RecordingHandler.records[0]["path"])
        query = parse_qs(parsed.query)
        self.assertEqual("/api/skill/observations/tasks", parsed.path)
        self.assertEqual(["task-a", "task-b"], query["taskIds"])
        self.assertEqual(["parent-1"], query["parentTaskId"])
        self.assertEqual(2, output["summary"]["total"])
        self.assertEqual("parent-1", output["parentTaskId"])

    def test_deployment_observation_partial_failure_requires_user_decision(self):
        observation = {
            "parentTaskId": "parent-1",
            "observations": [
                {"taskId": "task-a", "targetNodeId": "node-a:18091", "status": "SUCCESS",
                 "task": {"taskType": "DEPLOY_RELAY"}},
                {"taskId": "task-b", "targetNodeId": "node-b:18091", "status": "FAILED",
                 "currentStage": "ARTIFACT_TRANSFER",
                 "task": {"taskType": "DEPLOY_RELAY", "errorCode": "DISK_SPACE_INSUFFICIENT",
                          "errorMessage": "no space left"}},
            ],
            "summary": {"total": 2, "success": 1, "failed": 1},
        }

        decision = ccrelay_cli.deployment_observation_decision(observation)

        self.assertEqual("PARTIAL_FAILURE_REQUIRES_DECISION", decision["status"])
        self.assertEqual("node-b:18091", decision["failedNodes"][0])
        self.assertEqual("ARTIFACT_TRANSFER", decision["failureReport"][0]["failureStage"])
        self.assertIn("磁盘", decision["failureReport"][0]["governanceHint"])

    def test_deployment_observation_waits_for_all_nodes_to_finish(self):
        observation = {
            "observations": [
                {"taskId": "task-a", "targetNodeId": "node-a:18091", "status": "RUNNING",
                 "task": {"taskType": "DEPLOY_RELAY"}},
                {"taskId": "task-b", "targetNodeId": "node-b:18091", "status": "FAILED",
                 "task": {"taskType": "DEPLOY_RELAY"}},
            ],
        }

        self.assertIs(observation, ccrelay_cli.deployment_observation_decision(observation))

    def test_config_commands_cover_plain_and_secret_paths(self):
        plain = self.run_cli(
            "config",
            "set",
            "wdsavs.ai.observation.default-limit",
            "--value",
            "60",
            "--operator-id",
            "liuqi",
            "--comment",
            "raise observation window",
        )
        self.assertEqual("/api/skill/config", RecordingHandler.records[-1]["path"])
        self.assertEqual("wdsavs.ai.observation.default-limit", RecordingHandler.records[-1]["body"]["key"])
        self.assertEqual("60", plain["value"])

        secret = self.run_cli(
            "config",
            "secret",
            "set",
            "wdsavs.ai.relay.hmac-secret",
            "--value",
            "super-secret",
            "--operator-id",
            "liuqi",
        )
        self.assertEqual("/api/skill/config/secret", RecordingHandler.records[-1]["path"])
        self.assertTrue(RecordingHandler.records[-1]["body"]["value"])
        self.assertEqual("********", secret["maskedValue"])

        masked = self.run_cli("config", "secret", "get", "wdsavs.ai.relay.hmac-secret")
        self.assertEqual("/api/skill/config/wdsavs.ai.relay.hmac-secret", RecordingHandler.records[-1]["path"])
        self.assertIsNone(masked["value"])
        self.assertEqual("********", masked["maskedValue"])

        listed = self.run_cli("config", "list", "--prefix", "wdsavs.ai.observation")
        self.assertEqual("/api/skill/config?prefix=wdsavs.ai.observation", RecordingHandler.records[-1]["path"])
        self.assertEqual("wdsavs.ai.observation.default-limit", listed[0]["key"])

        reload_response = self.run_cli("config", "reload", "--operator-id", "liuqi")
        self.assertEqual("/api/skill/config/reload?operatorId=liuqi", RecordingHandler.records[-1]["path"])
        self.assertTrue(reload_response["reloaded"])

        history = self.run_cli("config", "history", "wdsavs.ai.observation.default-limit", "--limit", "5")
        self.assertEqual("/api/skill/config/history/wdsavs.ai.observation.default-limit?limit=5", RecordingHandler.records[-1]["path"])
        self.assertEqual("UPDATED", history[0]["action"])

    def test_agent_run_orchestrates_session_grant_message_and_close(self):
        output = self.run_cli(
            "agent",
            "run",
            "--target-node-id",
            "node-b:18091",
            "--prompt",
            "inspect remote issue",
        )

        paths = [record["path"] for record in RecordingHandler.records]
        self.assertEqual("/api/skill/relay/heartbeat/scan", paths[0])
        self.assertIn("/api/skill/session/open", paths)
        self.assertIn("/api/skill/relay/access/request", paths)
        self.assertIn("/api/skill/a2a/message/send", paths)
        self.assertIn("/api/skill/session/session-agent/close", paths)
        self.assertEqual("SUCCESS", output["response"]["result"]["status"])
        self.assertTrue(output["sessionClosed"])

        message_body = next(record["body"] for record in RecordingHandler.records if record["path"] == "/api/skill/a2a/message/send")
        self.assertEqual("ReAct", message_body["params"]["executionMode"])
        self.assertTrue(message_body["params"]["react"]["enabled"])
        self.assertEqual(12, message_body["params"]["react"]["maxSteps"])
        self.assertEqual("FULL", message_body["params"]["react"]["auditLevel"])

    def test_agent_run_sends_react_controls(self):
        self.run_cli(
            "agent",
            "run",
            "--target-node-id",
            "node-b:18091",
            "--prompt",
            "inspect remote issue",
            "--max-steps",
            "5",
            "--command-whitelist",
            "ls,grep,tail",
            "--step-timeout-ms",
            "3000",
            "--task-timeout-ms",
            "9000",
            "--audit-level",
            "SUMMARY",
            "--allow-ai",
            "false",
        )

        body = next(record["body"] for record in RecordingHandler.records if record["path"] == "/api/skill/a2a/message/send")
        self.assertEqual("ReAct", body["params"]["executionMode"])
        self.assertEqual(
            {
                "enabled": True,
                "mode": "ReAct",
                "maxSteps": 5,
                "commandWhitelist": ["ls", "grep", "tail"],
                "stepTimeoutMs": 3000,
                "taskTimeoutMs": 9000,
                "auditLevel": "SUMMARY",
                "allowAi": False,
            },
            body["params"]["react"],
        )

    def test_agent_fanout_creates_async_tasks_before_status_fallback(self):
        actions = json.dumps(
            {
                "actions": [
                    {"type": "RUN_COMMAND", "command": "hostname", "args": []},
                    {"type": "FINISH", "answer": "done"},
                ]
            }
        )
        output = self.run_cli(
            "agent",
            "fanout",
            "--target-node-ids",
            "node-b:18091,node-c:18091",
            "--prompt",
            "collect evidence",
            "--params-json",
            actions,
            "--collaboration-mode",
            "DISCUSSION",
        )

        self.assertEqual("ASYNC_TASKS_WITH_STATUS_FALLBACK", output["fanoutMode"])
        self.assertFalse(output["sessionClosed"])
        self.assertEqual("DISCUSSION", output["collaboration"]["collaborationMode"])
        self.assertEqual("node-b:18091", output["collaboration"]["coordinatorNodeId"])
        create_indexes = [
            index for index, record in enumerate(RecordingHandler.records) if record["path"] == "/api/skill/a2a/tasks/create"
        ]
        get_indexes = [
            index for index, record in enumerate(RecordingHandler.records) if record["path"].startswith("/api/skill/a2a/tasks/task-")
        ]
        close_paths = [record["path"] for record in RecordingHandler.records if record["path"].endswith("/close")]

        self.assertEqual(2, len(create_indexes))
        self.assertEqual(2, len(get_indexes))
        self.assertLess(max(create_indexes), min(get_indexes))
        self.assertEqual([], close_paths)
        self.assertEqual(["node-b:18091", "node-c:18091"], [item["targetNodeId"] for item in output["results"]])
        collaboration_calls = [record for record in RecordingHandler.records
                               if record["path"].endswith("/collaboration/initialize")]
        self.assertEqual(1, len(collaboration_calls))
        self.assertEqual(["node-b:18091", "node-c:18091"],
                         collaboration_calls[0]["body"]["participantNodeIds"])
        create_bodies = [RecordingHandler.records[index]["body"] for index in create_indexes]
        for body in create_bodies:
            self.assertEqual("collect evidence", body["params"]["messages"][0]["content"])
            self.assertEqual("hostname", body["params"]["actions"][0]["command"])

    def test_session_commands_return_collaboration_state_and_context_delta(self):
        collaboration = self.run_cli("session", "get", "session-agent")
        self.assertEqual("DISCUSSION", collaboration["collaborationMode"])
        self.assertEqual("node-b:18091", collaboration["coordinatorNodeId"])

        messages = self.run_cli(
            "session",
            "messages",
            "session-agent",
            "--after-cursor",
            "7",
            "--limit",
            "25",
        )
        self.assertEqual([], messages["events"])
        self.assertTrue(RecordingHandler.records[-1]["path"].endswith(
            "/context/delta?afterCursor=7&limit=25"
        ))

    def test_agent_control_commands_build_expected_requests(self):
        self.run_cli(
            "agent",
            "inject",
            "task-1",
            "--session-id",
            "session-1",
            "--target-node-id",
            "node-b:18091",
            "--prompt",
            "stop scanning logs and inspect config",
        )
        inject_body = RecordingHandler.records[-1]["body"]
        self.assertEqual("/api/skill/a2a/message/send", RecordingHandler.records[-1]["path"])
        self.assertEqual("INTERRUPT", inject_body["params"]["control"]["type"])
        self.assertEqual("INTERRUPT", inject_body["params"]["metadata"]["control"]["type"])
        self.assertEqual("task-1", inject_body["params"]["control"]["taskId"])

        self.run_cli(
            "agent",
            "adjust",
            "task-1",
            "--session-id",
            "session-1",
            "--target-node-id",
            "node-b:18091",
            "--max-steps",
            "20",
        )
        adjust_body = RecordingHandler.records[-1]["body"]
        self.assertEqual("ADJUST", adjust_body["params"]["control"]["type"])
        self.assertEqual("ADJUST", adjust_body["params"]["metadata"]["control"]["type"])
        self.assertEqual(20, adjust_body["params"]["control"]["react"]["maxSteps"])

        self.run_cli(
            "agent",
            "stop",
            "task-1",
            "--session-id",
            "session-1",
            "--target-node-id",
            "node-b:18091",
        )
        self.assertTrue(RecordingHandler.records[-1]["path"].startswith("/api/skill/a2a/tasks/task-1/cancel"))

    def test_remote_self_replicate_sends_relay_port_and_timeout(self):
        self.run_cli(
            "remote",
            "--relay",
            self.center,
            "self-replicate",
            "--host",
            "10.0.0.8",
            "--username",
            "tester",
            "--relay-port",
            "18093",
            "--script-path",
            "/home/tester/install-relay.sh",
            "--artifact-path",
            "/home/tester/ccrelay",
            "--timeout-ms",
            "120000",
            "--command-arguments=--foo=bar",
        )

        self.assertEqual("/internal/deploy/self-replicate", RecordingHandler.records[-1]["path"])
        body = RecordingHandler.records[-1]["body"]
        self.assertEqual(18093, body["relayPort"])
        self.assertEqual(120000, body["timeoutMs"])
        self.assertEqual(["--foo=bar"], body["commandArguments"])

    def test_install_relay_script_does_not_kill_arbitrary_port_processes(self):
        text = INSTALL_RELAY.read_text(encoding="utf-8")

        self.assertNotIn("fuser -k", text)
        self.assertNotIn("/usr/sbin/fuser -k", text)
        self.assertNotIn('lsof -ti "tcp:$relay_port"', text)
        self.assertIn("/RemoteCcRelayServer/", text)
        self.assertIn("relay_startup_timeout_seconds", text)
        self.assertIn('preserve_archives="${WDSAVS_CC_RELAY_PRESERVE_ARCHIVES:-false}"', text)
        self.assertIn('if [ "$preserve_archives" != "true" ]', text)
        self.assertIn("WDSAVS_AI_RELAY_HMAC_SECRET_FILE", text)
        self.assertIn('export WDSAVS_AI_HMAC_SECRET="$relay_hmac_secret"', text)
        self.assertIn("Relay process exited during startup", text)
        self.assertIn('PATH="$bundle_dir/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin${PATH:+:$PATH}"', text)
        self.assertIn('if ! extract_claude_code_from_archive; then', text)
        self.assertNotIn('extract_claude_code_from_archive || true', text)
        self.assertIn('if ! "$bundled_claude" --version', text)
        self.assertIn("RelayHealthProbe", text)
        self.assertIn("Relay health check failed during startup", text)
        self.assertIn("'/RemoteCcRelayServer/ && ($0 ~", text)
        self.assertNotIn("') || true)\"", text)

    def test_windows_install_relay_merges_system_path_and_waits_for_health(self):
        text = INSTALL_RELAY_WINDOWS.read_text(encoding="utf-8")

        self.assertIn("GetEnvironmentVariable('Path', 'Machine')", text)
        self.assertIn("GetEnvironmentVariable('Path', 'User')", text)
        self.assertIn("RelayHealthProbe", text)
        self.assertIn("Relay health check failed during startup", text)

    def test_runtime_bundle_build_does_not_copy_existing_model_config(self):
        text = BUILD_RUNTIME_BUNDLE.read_text(encoding="utf-8")

        self.assertNotIn("$existingCcConfigBytes", text)
        self.assertNotIn("[IO.File]::ReadAllBytes($ccConfigPath)", text)
        self.assertIn("modelConfigRequired = $true", text)
        self.assertIn("modelConfigSource", text)


if __name__ == "__main__":
    unittest.main()
