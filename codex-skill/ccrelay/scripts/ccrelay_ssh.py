#!/usr/bin/env python3
"""Local SSH credential and preflight support for ccrelay-cli."""

from __future__ import annotations

import base64
import ctypes
import getpass
import hashlib
import json
import os
import shutil
import secrets
import shlex
import stat
import subprocess
import sys
import tempfile
import time
from ctypes import wintypes
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple


CONFIG_VERSION = 2
DEFAULT_SSH_PORT = 22
DEFAULT_TIMEOUT_SECONDS = 15
SSH_ARGUMENT_MODE_DEFAULT = "DEFAULT"
SSH_ARGUMENT_MODE_USER_PROVIDED = "USER_PROVIDED"
DEFAULT_KEY_NAME = "id_ed25519_ccrelay"
REQUIRED_TOOLS = ("ssh", "scp", "ssh-keygen")
REMOTE_POSIX_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"


class SshCredentialError(Exception):
    pass


class SshCredentialInputRequired(SshCredentialError):
    """Raised when a secret cannot be collected safely in the current process."""

    def __init__(self, interaction: Dict[str, Any]) -> None:
        super().__init__(interaction.get("prompt") or "需要用户输入 SSH 凭据")
        self.interaction = interaction


def skill_root() -> Path:
    return Path(__file__).resolve().parents[1]


def config_path() -> Path:
    configured = os.getenv("CCRELAY_SSH_CONFIG")
    if configured:
        return Path(configured).expanduser().resolve()
    return skill_root() / ".local" / "ssh-credentials.json"


def default_config() -> Dict[str, Any]:
    return {
        "version": CONFIG_VERSION,
        "default": None,
        "nodes": {},
        "bootstrapCredentials": {"default": None, "nodes": {}},
        "clusterIdentity": {
            "clusterId": "default",
            "targetNodes": [],
            "managedNodes": [],
            "failedNodes": [],
            "excludedNodes": [],
            "selectionRequired": True,
            "accountMode": "EXISTING_ACCOUNT",
            "dedicatedAccountCreationAllowed": False,
            "dedicatedAccount": {
                "username": "ccrelay",
                "status": "DISABLED",
                "detailsConfirmed": False,
                "passwordSecrets": {},
            },
        },
        "runtime": {
            "remoteDirectoryTemplate": "/home/${runtimeUser}/${productName}/${host}-${relayPort}",
        },
    }


def load_config() -> Dict[str, Any]:
    path = config_path()
    if not path.is_file():
        return default_config()
    try:
        loaded = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise SshCredentialError(f"无法读取 SSH 凭据配置 {path}: {exc}") from exc
    if not isinstance(loaded, dict):
        raise SshCredentialError(f"SSH 凭据配置格式无效: {path}")
    config = default_config()
    loaded_identity = loaded.get("clusterIdentity") if isinstance(loaded.get("clusterIdentity"), dict) else {}
    loaded_runtime = loaded.get("runtime") if isinstance(loaded.get("runtime"), dict) else {}
    config.update({key: value for key, value in loaded.items() if key not in {"clusterIdentity", "runtime"}})
    bootstrap = loaded.get("bootstrapCredentials") if isinstance(loaded.get("bootstrapCredentials"), dict) else {}
    default_credential = bootstrap.get("default") if "default" in bootstrap else loaded.get("default")
    node_credentials = bootstrap.get("nodes") if isinstance(bootstrap.get("nodes"), dict) else loaded.get("nodes")
    config["default"] = default_credential
    config["nodes"] = node_credentials if isinstance(node_credentials, dict) else {}
    config["bootstrapCredentials"] = {"default": config["default"], "nodes": config["nodes"]}
    has_identity = isinstance(loaded.get("clusterIdentity"), dict)
    identity = loaded_identity
    config["clusterIdentity"].update(identity)
    if not identity.get("targetNodes") and identity.get("managedNodes"):
        config["clusterIdentity"]["targetNodes"] = [
            {
                "host": item.get("host"),
                "port": int(item.get("port") or 22),
                "nodeKey": item.get("nodeKey") or node_key(item.get("host"), item.get("port") or 22),
                "username": item.get("bootstrapUsername") or item.get("username"),
            }
            for item in identity.get("managedNodes") or []
            if isinstance(item, dict) and item.get("host")
        ]
    if not has_identity:
        config["clusterIdentity"]["selectionRequired"] = True
    dedicated = identity.get("dedicatedAccount") if isinstance(identity.get("dedicatedAccount"), dict) else {}
    config["clusterIdentity"]["dedicatedAccount"].update(dedicated)
    if "detailsConfirmed" not in dedicated:
        config["clusterIdentity"]["dedicatedAccount"]["detailsConfirmed"] = (
            dedicated.get("status") in {"ACTIVE", "PARTIAL"}
        )
    if not isinstance(config["clusterIdentity"]["dedicatedAccount"].get("passwordSecrets"), dict):
        config["clusterIdentity"]["dedicatedAccount"]["passwordSecrets"] = {}
    config["runtime"].update(loaded_runtime)
    return config


def save_config(config: Dict[str, Any]) -> Path:
    path = config_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = dict(config)
    payload["version"] = CONFIG_VERSION
    payload["bootstrapCredentials"] = {"default": payload.get("default"), "nodes": payload.get("nodes") or {}}
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    _restrict_file(temporary)
    temporary.replace(path)
    _restrict_file(path)
    return path


def set_default_credential(
    username: str,
    password: str,
    port: int = DEFAULT_SSH_PORT,
    enable_passwordless: bool = True,
    allow_cluster_mutual: bool = True,
    remote_directory: Optional[str] = None,
    ssh_arguments_mode: str = SSH_ARGUMENT_MODE_DEFAULT,
    ssh_arguments: Optional[Iterable[str]] = None,
) -> Dict[str, Any]:
    config = load_config()
    config["default"] = _credential_record(
        username,
        password,
        port,
        enable_passwordless,
        allow_cluster_mutual,
        remote_directory,
        ssh_arguments_mode,
        ssh_arguments,
    )
    path = save_config(config)
    return credential_view(config["default"], "DEFAULT", path)


def set_passwordless_default(
    username: str,
    port: int = DEFAULT_SSH_PORT,
    private_key_path: Optional[str] = None,
    allow_cluster_mutual: bool = True,
    remote_directory: Optional[str] = None,
    ssh_arguments_mode: str = SSH_ARGUMENT_MODE_DEFAULT,
    ssh_arguments: Optional[Iterable[str]] = None,
) -> Dict[str, Any]:
    config = load_config()
    config["default"] = _passwordless_credential_record(
        username,
        port,
        private_key_path,
        allow_cluster_mutual,
        remote_directory,
        ssh_arguments_mode,
        ssh_arguments,
    )
    path = save_config(config)
    return credential_view(config["default"], "DEFAULT", path)


def set_node_credential(
    host: str,
    username: str,
    password: str,
    port: int = DEFAULT_SSH_PORT,
    enable_passwordless: bool = True,
    allow_cluster_mutual: bool = True,
    remote_directory: Optional[str] = None,
    ssh_arguments_mode: str = SSH_ARGUMENT_MODE_DEFAULT,
    ssh_arguments: Optional[Iterable[str]] = None,
) -> Dict[str, Any]:
    normalized_host = require_text(host, "host")
    config = load_config()
    key = node_key(normalized_host, port)
    config["nodes"][key] = _credential_record(
        username,
        password,
        port,
        enable_passwordless,
        allow_cluster_mutual,
        remote_directory,
        ssh_arguments_mode,
        ssh_arguments,
    )
    path = save_config(config)
    return credential_view(config["nodes"][key], "NODE", path, key)


def remove_default_credential() -> Dict[str, Any]:
    config = load_config()
    existed = bool(config.get("default"))
    config["default"] = None
    path = save_config(config)
    return {"removed": existed, "scope": "DEFAULT", "configPath": str(path)}


def remove_node_credential(host: str, port: int = DEFAULT_SSH_PORT) -> Dict[str, Any]:
    config = load_config()
    key = node_key(host, port)
    existed = config["nodes"].pop(key, None) is not None
    path = save_config(config)
    return {"removed": existed, "scope": "NODE", "node": key, "configPath": str(path)}


def masked_config_view() -> Dict[str, Any]:
    config = load_config()
    path = config_path()
    default = config.get("default")
    nodes = config.get("nodes") or {}
    identity = config.get("clusterIdentity") or {}
    dedicated = identity.get("dedicatedAccount") or {}
    return {
        "configPath": str(path),
        "exists": path.is_file(),
        "storageProtection": storage_protection(),
        "default": credential_view(default, "DEFAULT", path) if default else None,
        "nodes": {key: credential_view(value, "NODE", path, key) for key, value in nodes.items()},
        "clusterIdentity": {
            "clusterId": identity.get("clusterId", "default"),
            "targetNodeCount": len(identity.get("targetNodes") or []),
            "managedNodeCount": len(identity.get("managedNodes") or []),
            "failedNodeCount": len(identity.get("failedNodes") or []),
            "excludedNodeCount": len(identity.get("excludedNodes") or []),
            "selectionRequired": bool(identity.get("selectionRequired", False)),
            "accountMode": identity.get("accountMode", "EXISTING_ACCOUNT"),
            "dedicatedAccountCreationAllowed": bool(identity.get("dedicatedAccountCreationAllowed", False)),
            "dedicatedAccount": {
                "username": dedicated.get("username", "ccrelay"),
                "status": dedicated.get("status", "DISABLED"),
                "detailsConfirmed": bool(dedicated.get("detailsConfirmed", False)),
                "passwordConfiguredNodes": sorted((dedicated.get("passwordSecrets") or {}).keys()),
                "clusterKey": {
                    "mode": (dedicated.get("clusterKey") or {}).get("mode"),
                    "fingerprint": (dedicated.get("clusterKey") or {}).get("fingerprint"),
                    "privateKeyConfigured": bool((dedicated.get("clusterKey") or {}).get("privateKeyPath")),
                },
            },
        },
        "runtime": config.get("runtime") or {},
    }


def resolve_credential(host: str, port: int = DEFAULT_SSH_PORT) -> Tuple[Optional[Dict[str, Any]], Optional[str]]:
    config = load_config()
    key = node_key(host, port)
    if key in config["nodes"]:
        return _credential_with_password(config["nodes"][key]), "NODE"
    if config.get("default"):
        return _credential_with_password(config["default"]), "DEFAULT"
    return None, None


def read_password_input(
    password_file: Optional[str],
    password_env: Optional[str],
    prompt: str,
    prompt_password: bool = False,
) -> str:
    if password_file:
        try:
            value = Path(password_file).read_text(encoding="utf-8").rstrip("\r\n")
        except OSError as exc:
            raise SshCredentialError(f"无法读取密码文件: {exc}") from exc
        return require_text(value, "password")
    if password_env:
        return require_text(os.getenv(password_env), f"environment variable {password_env}")
    if not prompt_password:
        interactive_tty = bool(sys.stdin.isatty())
        raise SshCredentialInputRequired({
            "prompt": "当前命令未明确进入安全密码输入模式。请选择安全终端输入，或明确承担明文输入风险。",
            "reason": "EXPLICIT_SECURE_INPUT_REQUIRED" if interactive_tty else "NO_INTERACTIVE_TTY",
            "inputCapabilities": input_capabilities(),
            "fields": [field("password", "SSH 密码", True, None, "仅在安全终端中输入时不回显", secret=True)],
        })
    if not sys.stdin.isatty():
        raise SshCredentialInputRequired({
            "prompt": "当前进程没有可用于隐藏输入的交互终端。请选择安全终端输入，或明确承担明文输入风险。",
            "reason": "NO_INTERACTIVE_TTY",
            "inputCapabilities": input_capabilities(),
            "fields": [field("password", "SSH 密码", True, None, "仅在安全终端中输入时不回显", secret=True)],
        })
    return require_text(getpass.getpass(prompt), "password")


def input_capabilities() -> Dict[str, Any]:
    platform_name = "WINDOWS" if os.name == "nt" else "LINUX"
    gui_launcher = detect_gui_terminal_launcher()
    return {
        "platform": platform_name,
        "currentProcessTty": bool(sys.stdin.isatty()),
        "secureHiddenInput": bool(sys.stdin.isatty()),
        "guiTerminalLauncherDetected": gui_launcher is not None,
        "guiTerminalLauncher": gui_launcher,
        "manualVisibleFallbackAvailable": True,
        "passwordFileAvailable": True,
        "passwordEnvironmentAvailable": True,
    }


def detect_gui_terminal_launcher() -> Optional[str]:
    if os.name == "nt":
        return "powershell.exe" if windows_interactive_desktop_available() else None
    if not (os.getenv("DISPLAY") or os.getenv("WAYLAND_DISPLAY")):
        return None
    for candidate in ("x-terminal-emulator", "gnome-terminal", "konsole", "xterm"):
        if shutil.which(candidate):
            return candidate
    return None


def windows_interactive_desktop_available() -> bool:
    if os.name != "nt":
        return False
    try:
        desktop = ctypes.windll.user32.OpenInputDesktop(0, False, 0x0100)
        if not desktop:
            return False
        ctypes.windll.user32.CloseDesktop(desktop)
        return True
    except Exception:
        return False


def secure_terminal_command(command_args: List[str]) -> str:
    script_dir = skill_root() / "scripts"
    if os.name == "nt":
        wrapper = script_dir / "ccrelay-cli.ps1"
        rendered = " ".join(_quote_windows(argument) for argument in command_args)
        return f'powershell -NoProfile -ExecutionPolicy Bypass -File {_quote_windows(str(wrapper))} {rendered}'
    wrapper = script_dir / "ccrelay-cli.sh"
    rendered = " ".join(shlex.quote(argument) for argument in command_args)
    return f"bash {shlex.quote(str(wrapper))} {rendered}"


def launch_secure_terminal(command_args: List[str]) -> Dict[str, Any]:
    capabilities = input_capabilities()
    if not capabilities["guiTerminalLauncherDetected"]:
        return {
            "status": "NEED_USER_INPUT",
            "failureType": "SSH_SECURE_TERMINAL_UNAVAILABLE",
            "reason": "GUI_TERMINAL_NOT_DETECTED",
            "inputCapabilities": capabilities,
            "command": secure_terminal_command(command_args + ["--prompt-password"]),
        }
    arguments = command_args + ["--prompt-password"]
    if os.name == "nt":
        command = secure_terminal_command(arguments)
        subprocess.Popen(
            [
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-NoExit",
                "-Command",
                command,
            ],
            creationflags=getattr(subprocess, "CREATE_NEW_CONSOLE", 0),
        )
    else:
        command = secure_terminal_command(arguments)
        launcher = capabilities["guiTerminalLauncher"]
        if launcher == "gnome-terminal":
            launch_arguments = [launcher, "--", "bash", "-lc", command]
        else:
            launch_arguments = [launcher, "-e", "bash", "-lc", command]
        subprocess.Popen(launch_arguments)
    return {
        "status": "SECURE_TERMINAL_LAUNCHED",
        "inputCapabilities": capabilities,
        "command": secure_terminal_command(arguments),
        "resume": "安全终端完成后回到当前会话，重新执行原操作；不要回传 SSH 密码。",
    }


def _quote_windows(value: str) -> str:
    return subprocess.list2cmdline([str(value)])


def credential_input_interaction(command_args: List[str], scope: str, host: Optional[str] = None, port: Optional[int] = None) -> Dict[str, Any]:
    capabilities = input_capabilities()
    target = f"{host}:{port}" if host and port else "通用配置"
    return {
        "status": "NEED_USER_INPUT",
        "failureType": "SSH_PASSWORD_INPUT_REQUIRED",
        "reason": "NO_INTERACTIVE_TTY",
        "prompt": f"{target} 需要 SSH 密码。请选择输入方式；推荐标记只说明适用场景，不会替你选择：",
        "inputCapabilities": capabilities,
        "options": [
            {
                "id": "LAUNCH_SECURE_TERMINAL",
                "label": "弹出安全终端隐藏输入（高风险 + 有桌面 GUI）",
                "recommended": False,
                "applicability": "高风险环境且有桌面 GUI；用户确认后才拉起终端。",
                "available": capabilities["guiTerminalLauncherDetected"],
                "action": "LAUNCH_COMMAND_IN_TERMINAL",
                "command": secure_terminal_command(command_args + ["--launch-secure-terminal"]),
                "security": "密码只在终端隐藏输入，输入完成后由本地配置加密保存。",
                "resume": "命令成功后回到当前会话，重新执行原操作；不要在对话中回传密码。",
            },
            {
                "id": "SECURE_TERMINAL_COMMAND",
                "label": "安全输入命令（高风险 + 无桌面 GUI）",
                "recommended": False,
                "applicability": "高风险环境且无桌面 GUI、只有终端；用户确认后自行执行返回命令。",
                "available": True,
                "command": secure_terminal_command(command_args + ["--prompt-password"]),
                "security": "密码只在用户终端隐藏输入，不进入当前会话。",
                "resume": "用户执行成功后回到当前会话，重新执行原操作；不要在对话中回传密码。",
            },
            {
                "id": "MANUAL_VISIBLE_INPUT",
                "label": "明文输入（低风险 + 无桌面 GUI）",
                "recommended": False,
                "applicability": "仅适用于低风险或用户明确接受明文风险的环境。",
                "securityWarning": "密码可能出现在对话记录、模型上下文或审计系统中；仅适用于用户确认可接受该风险的内部环境。",
                "fields": [
                    field("password", "SSH 密码", True, None, "明文输入风险由用户承担", secret=True),
                ],
                "resume": "上层 Skill 必须使用进程级安全传递（password-env 或临时 password-file），禁止把密码拼接到命令行参数或日志。",
            },
            {
                "id": "CANCEL",
                "label": "取消本次操作",
                "recommended": False,
            },
        ],
        "userChoiceRequired": True,
        "fields": [],
        "agentAction": "RETURN_VERBATIM_RESPONSE_AND_STOP",
        "mustStopCurrentTurn": True,
        "verbatimResponse": (
            f"请选择 {target} 的 SSH 密码输入方式：\n\n"
            "1. 安全终端弹窗（高风险环境 + 有桌面 GUI）\n"
            "2. 安全输入命令（高风险环境 + 仅终端）\n"
            "3. 明文输入（低风险环境 + 接受密码进入对话记录）\n"
            "4. 取消\n\n"
            "请回复选项序号。"
        ),
        "scope": scope,
        "resume": "必须等待用户明确选择 LAUNCH_SECURE_TERMINAL、SECURE_TERMINAL_COMMAND、MANUAL_VISIBLE_INPUT 或 CANCEL；未选择前不得执行输入命令或写入凭据。",
    }


def check_local_tools() -> Dict[str, Any]:
    tools = {name: shutil.which(name) for name in REQUIRED_TOOLS}
    missing = [name for name, path in tools.items() if not path]
    return {
        "status": "READY" if not missing else "FAILED",
        "tools": tools,
        "missingTools": missing,
        "platform": "WINDOWS" if os.name == "nt" else "LINUX",
    }


def _with_posix_path(command: str) -> str:
    """Make non-interactive POSIX SSH commands independent of login PATH."""
    return f"export PATH=\"{REMOTE_POSIX_PATH}:$PATH\"; {command}"


def test_connection(
    host: str,
    port: int = DEFAULT_SSH_PORT,
    username: Optional[str] = None,
    timeout_seconds: int = DEFAULT_TIMEOUT_SECONDS,
    bootstrap_key: bool = False,
    password_override: Optional[str] = None,
) -> Dict[str, Any]:
    tools = check_local_tools()
    if tools["missingTools"]:
        return failure_result(host, port, "LOCAL_TOOL_MISSING", "本机缺少 SSH 工具", tools=tools)
    credential, scope = resolve_credential(host, port)
    selected_username = username or (credential or {}).get("username")
    if not selected_username:
        return missing_credential_result(host, port)

    key_path = credential_key_path(credential)
    batch = _run_ssh(host, port, selected_username, timeout_seconds,
                     password=password_override, key_path=None if password_override else key_path)
    if batch["success"]:
        auth_mode = "PASSWORD" if password_override is not None else "PUBLIC_KEY"
        result = success_result(host, port, selected_username, auth_mode, scope, batch)
        if password_override is not None:
            result["status"] = "CREDENTIAL_VALID"
            result["readyForCenterDeploy"] = False
        return result

    if password_override is not None or not credential:
        return missing_credential_result(host, port, batch)

    password = credential.get("password")
    if not password:
        result = failure_result(
            host,
            port,
            classify_ssh_failure(batch),
            batch.get("summary") or "已有免密 SSH 账号验证失败",
            username=selected_username,
            credentialScope=scope,
            attempts=[masked_attempt(batch)],
        )
        result["interaction"] = failed_credential_interaction(
            host, port, result["failureType"], result["summary"], scope)
        return result
    password_result = _run_ssh(host, port, selected_username, timeout_seconds, password=password)
    if not password_result["success"]:
        result = failure_result(
            host,
            port,
            classify_ssh_failure(password_result),
            password_result.get("summary") or "SSH 认证失败",
            username=selected_username,
            credentialScope=scope,
            attempts=[masked_attempt(batch), masked_attempt(password_result)],
        )
        result["interaction"] = failed_credential_interaction(host, port, result["failureType"], result["summary"], scope)
        return result

    if not bootstrap_key:
        result = success_result(host, port, selected_username, "PASSWORD", scope, password_result)
        result["status"] = "CREDENTIAL_VALID"
        result["readyForCenterDeploy"] = False
        return result

    if not credential.get("enablePasswordless", True):
        return {
            **success_result(host, port, selected_username, "PASSWORD", scope, password_result),
            "status": "PASSWORD_AUTHENTICATED_KEY_REQUIRED",
            "readyForCenterDeploy": False,
            "interaction": passwordless_required_interaction(host, port),
        }

    bootstrap = bootstrap_public_key(host, port, selected_username, password, timeout_seconds)
    if not bootstrap["success"]:
        bootstrap["credentialScope"] = scope
        return bootstrap
    verified = _run_ssh(host, port, selected_username, timeout_seconds, key_path=configured_key_path())
    if not verified["success"]:
        return failure_result(
            host,
            port,
            "KEY_VERIFICATION_FAILED",
            verified.get("summary") or "免密配置后验证失败",
            username=selected_username,
            credentialScope=scope,
            attempts=[masked_attempt(verified)],
        )
    result = success_result(host, port, selected_username, "PUBLIC_KEY_BOOTSTRAPPED", scope, verified)
    result["bootstrap"] = bootstrap
    return result


def preflight(host: str, port: int = DEFAULT_SSH_PORT, username: Optional[str] = None, timeout_seconds: int = DEFAULT_TIMEOUT_SECONDS) -> Dict[str, Any]:
    tools = check_local_tools()
    if tools["missingTools"]:
        result = failure_result(host, port, "LOCAL_TOOL_MISSING", "本机缺少部署所需 SSH 工具", tools=tools)
        result["interaction"] = local_tool_interaction(tools["missingTools"])
        return result
    credential, scope = resolve_credential(host, port)
    if not username and credential:
        username = credential.get("username")
    if not username:
        return missing_credential_result(host, port)
    result = test_connection(host, port, username, timeout_seconds, bootstrap_key=True)
    result["preflight"] = True
    result["readyForCenterDeploy"] = result.get("status") == "READY"
    result["credentialScope"] = result.get("credentialScope") or scope
    return result


def bootstrap_public_key_value(
    host: str,
    port: int,
    username: str,
    public_key: str,
    timeout_seconds: int = DEFAULT_TIMEOUT_SECONDS,
    password_override: Optional[str] = None,
) -> Dict[str, Any]:
    credential, scope = resolve_credential(host, port)
    selected_username = username or (credential or {}).get("username")
    if not selected_username:
        return missing_credential_result(host, port)
    escaped_key = _shell_single_quote(require_text(public_key, "publicKey"))
    command = _with_posix_path(
        "umask 077; mkdir -p \"$HOME/.ssh\" && touch \"$HOME/.ssh/authorized_keys\" "
        "&& chmod 700 \"$HOME/.ssh\" && chmod 600 \"$HOME/.ssh/authorized_keys\" "
        f"&& (grep -qxF {escaped_key} \"$HOME/.ssh/authorized_keys\" || printf '%s\\n' {escaped_key} >> \"$HOME/.ssh/authorized_keys\")"
    )
    executed = _run_ssh(
        host, port, selected_username, timeout_seconds,
        password=password_override,
        key_path=None if password_override else credential_key_path(credential),
        remote_command=command)
    if (not executed["success"] and password_override is None and credential
            and credential.get("password")):
        executed = _run_ssh(
            host,
            port,
            selected_username,
            timeout_seconds,
            password=credential.get("password"),
            remote_command=command,
        )
    if not executed["success"]:
        if not credential and classify_ssh_failure(executed) == "AUTHENTICATION_FAILED":
            return missing_credential_result(host, port, executed)
        failure_type = classify_ssh_failure(executed)
        result = failure_result(host, port, failure_type, executed.get("summary") or "无法写入中心公钥", username=selected_username)
        result["credentialScope"] = scope
        result["interaction"] = failed_credential_interaction(host, port, failure_type, result["summary"], scope)
        return result
    return {
        "success": True,
        "status": "CENTER_KEY_BOOTSTRAPPED",
        "host": host,
        "port": port,
        "username": selected_username,
        "credentialScope": scope,
    }


def bootstrap_public_key(host: str, port: int, username: str, password: str, timeout_seconds: int) -> Dict[str, Any]:
    key_path = ensure_local_key()
    public_key = key_path.with_suffix(".pub").read_text(encoding="utf-8").strip()
    escaped_key = _shell_single_quote(public_key)
    command = _with_posix_path(
        "umask 077; mkdir -p \"$HOME/.ssh\" && touch \"$HOME/.ssh/authorized_keys\" "
        "&& chmod 700 \"$HOME/.ssh\" && chmod 600 \"$HOME/.ssh/authorized_keys\" "
        f"&& (grep -qxF {escaped_key} \"$HOME/.ssh/authorized_keys\" || printf '%s\\n' {escaped_key} >> \"$HOME/.ssh/authorized_keys\")"
    )
    executed = _run_ssh(host, port, username, timeout_seconds, password=password, remote_command=command)
    if not executed["success"]:
        failure_type = classify_ssh_failure(executed)
        if failure_type == "AUTHENTICATION_FAILED":
            failure_type = "AUTHENTICATION_FAILED"
        elif failure_type == "UNKNOWN_SSH_FAILURE":
            failure_type = "PERMISSION_DENIED"
        result = failure_result(host, port, failure_type, executed.get("summary") or "无法写入 authorized_keys", username=username)
        result["interaction"] = failed_credential_interaction(host, port, failure_type, result["summary"], None)
        return result
    return {
        "success": True,
        "status": "KEY_BOOTSTRAPPED",
        "host": host,
        "port": port,
        "username": username,
        "publicKeyPath": str(key_path.with_suffix(".pub")),
        "privateKeyPath": str(key_path),
    }


def ensure_local_key() -> Path:
    tools = check_local_tools()
    if "ssh-keygen" in tools["missingTools"]:
        raise SshCredentialError("本机缺少 ssh-keygen")
    key_path = configured_key_path()
    if key_path.is_file() and key_path.with_suffix(".pub").is_file():
        _restrict_file(key_path)
        return key_path
    key_path.parent.mkdir(parents=True, exist_ok=True)
    command = [tools["tools"]["ssh-keygen"], "-t", "ed25519", "-N", "", "-f", str(key_path), "-C", "ccrelay-cluster"]
    completed = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace", check=False)
    if completed.returncode != 0:
        raise SshCredentialError(f"生成 SSH 密钥失败: {_summary(completed.stderr or completed.stdout)}")
    _restrict_file(key_path)
    return key_path


def configured_key_path() -> Path:
    configured = os.getenv("CCRELAY_SSH_KEY")
    if configured:
        return Path(configured).expanduser().resolve()
    return config_path().parent / "keys" / DEFAULT_KEY_NAME


def credential_key_path(credential: Optional[Dict[str, Any]]) -> Path:
    configured = (credential or {}).get("privateKeyPath")
    if configured:
        return Path(str(configured)).expanduser().resolve()
    return configured_key_path()


def missing_credential_result(host: str, port: int, prior_attempt: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    config = load_config()
    has_default = bool(config.get("default"))
    code = "SSH_NODE_CREDENTIAL_REQUIRED" if has_default else "SSH_DEFAULT_CREDENTIAL_MISSING"
    summary = "通用 SSH 凭据无法用于当前节点，需要节点级凭据" if has_default else "当前没有检测到通用 SSH 配置"
    result = failure_result(host, port, code, summary)
    result["status"] = "NEED_USER_INPUT"
    result["taskCreated"] = False
    result["interaction"] = (
        failed_credential_interaction(host, port, "AUTHENTICATION_FAILED", _summary((prior_attempt or {}).get("summary")), "DEFAULT")
        if has_default
        else missing_default_interaction(host, port)
    )
    return result


def missing_default_interaction(host: str, port: int) -> Dict[str, Any]:
    return {
        "prompt": "当前没有检测到通用 SSH 访问配置。可以配置账号密码，也可以使用已有免密通用账号。请选择下一步：",
        "options": [
            option("CREATE_DEFAULT", "创建通用 SSH 配置（推荐）", True),
            option("USE_EXISTING_PASSWORDLESS_ACCOUNT", "使用已有的免密通用账号"),
            option("CREATE_NODE", "仅为当前节点创建单独配置"),
            option("SKIP_SSH", "跳过 SSH 配置，仅检查已注册 relay"),
            option("CANCEL", "取消本次部署"),
        ],
        "fields": default_fields(port) + passwordless_default_fields(port),
        "target": node_key(host, port),
        "resume": "配置保存后执行 ssh preflight；仅当 readyForCenterDeploy=true 时才能创建部署任务。",
    }


def failed_credential_interaction(host: str, port: int, failure_type: str, summary: str, scope: Optional[str]) -> Dict[str, Any]:
    if failure_type == "SSH_ARGUMENTS_INCOMPATIBLE":
        return {
            "prompt": f"节点 {node_key(host, port)} 的 SSH 客户端不支持 Skill 默认参数，请选择 SSH 参数来源：",
            "options": [
                option("USE_USER_PROVIDED_SSH_ARGUMENTS", "使用用户提供的 SSH 参数", True),
                option("RETRY_DEFAULT_SSH_ARGUMENTS", "确认客户端兼容后重试"),
                option("CANCEL", "取消本次部署"),
            ],
            "fields": [
                field("sshArgumentsMode", "SSH 参数来源", True, SSH_ARGUMENT_MODE_USER_PROVIDED,
                      "选择 USER_PROVIDED 后按 argv token 顺序填写参数"),
                field("sshArguments", "用户 SSH 参数 argv token", True,
                      ["-o", "StrictHostKeyChecking=no"],
                      "可重复传入 --ssh-argument；不要填写目标地址和 SSH 端口"),
            ],
            "failureType": failure_type,
            "failureSummary": summary,
            "credentialScope": scope,
            "resume": "保存 SSH 参数后重新执行原 ssh identity plan|apply 命令。",
        }
    return {
        "prompt": f"SSH 配置无法连接节点 {node_key(host, port)}。失败类型: {failure_type}。请选择处理方式：",
        "options": [
            option("CREATE_NODE", "为该节点新增单独 SSH 配置（推荐）", True),
            option("UPDATE_DEFAULT", "更新通用 SSH 配置并重新测试"),
            option("SKIP_NODE", "跳过该节点"),
            option("CANCEL", "取消本次部署"),
        ],
        "fields": node_fields(host, port),
        "failureType": failure_type,
        "failureSummary": summary,
        "credentialScope": scope,
        "resume": "保存凭据后立即重新执行 ssh preflight。",
    }


def passwordless_required_interaction(host: str, port: int) -> Dict[str, Any]:
    return {
        "prompt": "密码认证成功，但中心 Java 部署只接受免密 SSH。需要先初始化公钥。",
        "options": [
            option("BOOTSTRAP_KEY", "立即配置免密并重新测试（推荐）", True),
            option("UPDATE_CREDENTIAL", "更新 SSH 凭据"),
            option("SKIP_NODE", "跳过该节点"),
            option("CANCEL", "取消本次部署"),
        ],
        "fields": [],
        "target": node_key(host, port),
    }


def local_tool_interaction(missing: List[str]) -> Dict[str, Any]:
    return {
        "prompt": f"本机缺少 SSH 工具: {', '.join(missing)}。请先安装后重新预检。",
        "options": [
            option("INSTALL_OPENSSH", "安装或启用 OpenSSH Client（推荐）", True),
            option("RETRY", "重新检查"),
            option("CANCEL", "取消本次部署"),
        ],
        "fields": [],
    }


def default_fields(port: int) -> List[Dict[str, Any]]:
    return [
        field("username", "通用用户名", True, None, "必须由用户输入，不从本机账号或历史样例推断"),
        field("password", "通用密码", True, None, "安全输入，不回显、不写日志", secret=True),
        field("port", "SSH 端口", True, port, "默认 22"),
        field("scope", "适用范围", True, "所有节点", "仅保存一份通用配置"),
        field("enablePasswordless", "是否启用免密配置", True, True, "中心部署需要免密 SSH"),
        field("allowClusterMutual", "是否允许用于集群内部互通", True, True, "用于源端自复制"),
    ]


def passwordless_default_fields(port: int) -> List[Dict[str, Any]]:
    visible_when = {"optionId": "USE_EXISTING_PASSWORDLESS_ACCOUNT"}
    return [
        {**field("passwordlessUsername", "已有免密 SSH 用户名", False, None,
                 "选择已有免密通用账号时必填，不从历史记录推断"), "visibleWhen": visible_when},
        {**field("passwordlessSshPort", "已有免密 SSH 端口", False, port, None),
         "visibleWhen": visible_when},
        {**field("passwordlessPrivateKeyPath", "SSH 私钥路径", False, None,
                 "可选；为空时使用系统 SSH config、Agent 或默认密钥", secret=True),
         "visibleWhen": visible_when},
    ]


def node_fields(host: str, port: int) -> List[Dict[str, Any]]:
    return [
        field("node", "节点标识(ip:port)", True, node_key(host, port), None),
        field("username", "SSH 用户名", True, None, None),
        field("password", "SSH 密码", True, None, "安全输入，不回显、不写日志", secret=True),
        field("port", "SSH 端口", True, port, "默认 22"),
        field("remoteDirectory", "远端工作目录", False, None, "为空时使用可配置目录模板"),
        field("replaceDefault", "是否替换通用配置", True, False, None),
        field("testImmediately", "是否立即测试连接", True, True, None),
    ]


def option(option_id: str, label: str, recommended: bool = False) -> Dict[str, Any]:
    return {"id": option_id, "label": label, "recommended": recommended}


def field(name: str, label: str, required: bool, default: Any, hint: Optional[str], secret: bool = False) -> Dict[str, Any]:
    return {"name": name, "label": label, "required": required, "default": default, "hint": hint, "secret": secret}


def success_result(host: str, port: int, username: str, auth_mode: str, scope: Optional[str], attempt: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "success": True,
        "status": "READY",
        "readyForCenterDeploy": True,
        "host": host,
        "port": port,
        "username": username,
        "authMode": auth_mode,
        "credentialScope": scope,
        "latencyMs": attempt.get("latencyMs"),
        "summary": attempt.get("summary"),
    }


def failure_result(host: str, port: int, failure_type: str, summary: str, **extra: Any) -> Dict[str, Any]:
    return {
        "success": False,
        "status": "FAILED",
        "readyForCenterDeploy": False,
        "host": host,
        "port": port,
        "failureType": failure_type,
        "summary": _summary(summary),
        **extra,
    }


def classify_ssh_failure(result: Dict[str, Any]) -> str:
    text = str(result.get("summary") or "").lower()
    if "bad configuration option" in text and "accept-new" in text:
        return "SSH_ARGUMENTS_INCOMPATIBLE"
    if "host key verification failed" in text or "remote host identification has changed" in text:
        return "HOST_KEY_CHANGED"
    if "permission denied" in text or "authentication failed" in text:
        return "AUTHENTICATION_FAILED"
    if "connection timed out" in text or "operation timed out" in text:
        return "NETWORK_TIMEOUT"
    if "connection refused" in text or "no route to host" in text or "could not resolve hostname" in text:
        return "NETWORK_UNREACHABLE"
    if "askpass" in text:
        return "PASSWORD_HELPER_FAILED"
    return "UNKNOWN_SSH_FAILURE"


def masked_attempt(attempt: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "success": attempt.get("success"),
        "exitCode": attempt.get("exitCode"),
        "latencyMs": attempt.get("latencyMs"),
        "summary": _summary(attempt.get("summary")),
        "authMode": attempt.get("authMode"),
    }


def credential_view(record: Dict[str, Any], scope: str, path: Path, key: Optional[str] = None) -> Dict[str, Any]:
    return {
        "scope": scope,
        "node": key,
        "username": record.get("username"),
        "credentialSource": record.get("credentialSource", "USER_PROVIDED"),
        "port": record.get("port", DEFAULT_SSH_PORT),
        "passwordConfigured": bool(record.get("protectedPassword") or record.get("password")),
        "password": "********" if record.get("protectedPassword") or record.get("password") else None,
        "authenticationMode": record.get("authenticationMode", "PASSWORD"),
        "privateKeyConfigured": bool(record.get("privateKeyPath")),
        "privateKeyPath": record.get("privateKeyPath"),
        "enablePasswordless": bool(record.get("enablePasswordless", True)),
        "allowClusterMutual": bool(record.get("allowClusterMutual", True)),
        "remoteDirectory": record.get("remoteDirectory"),
        "sshArgumentsMode": record.get("sshArgumentsMode", SSH_ARGUMENT_MODE_DEFAULT),
        "sshArgumentCount": len(record.get("sshArguments") or []),
        "updatedAt": record.get("updatedAt"),
        "configPath": str(path),
    }


def node_key(host: str, port: int) -> str:
    return f"{require_text(host, 'host')}:{normalize_port(port)}"


def require_text(value: Optional[str], name: str) -> str:
    if value is None or not str(value).strip():
        raise SshCredentialError(f"{name} 不能为空")
    return str(value).strip()


def normalize_port(port: int) -> int:
    parsed = int(port or DEFAULT_SSH_PORT)
    if parsed <= 0 or parsed > 65535:
        raise SshCredentialError(f"SSH 端口无效: {parsed}")
    return parsed


def normalize_ssh_arguments(mode: str, arguments: Optional[Iterable[str]]) -> Tuple[str, List[str]]:
    normalized_mode = str(mode or SSH_ARGUMENT_MODE_DEFAULT).strip().upper()
    if normalized_mode not in {SSH_ARGUMENT_MODE_DEFAULT, SSH_ARGUMENT_MODE_USER_PROVIDED}:
        raise SshCredentialError(
            f"ssh 参数模式无效: {normalized_mode}；可选 DEFAULT 或 USER_PROVIDED")
    normalized_arguments = [str(value) for value in (arguments or [])]
    if any("\x00" in value for value in normalized_arguments):
        raise SshCredentialError("SSH 参数不能包含 NUL 字符")
    if len(normalized_arguments) > 64:
        raise SshCredentialError("SSH 参数数量不能超过 64 个")
    if normalized_mode == SSH_ARGUMENT_MODE_USER_PROVIDED and not normalized_arguments:
        raise SshCredentialError("使用用户 SSH 参数时至少需要提供一个参数")
    if normalized_mode == SSH_ARGUMENT_MODE_DEFAULT:
        normalized_arguments = []
    return normalized_mode, normalized_arguments


def ssh_arguments_for_credential(credential: Optional[Dict[str, Any]]) -> Optional[List[str]]:
    if not credential:
        return None
    mode, arguments = normalize_ssh_arguments(
        credential.get("sshArgumentsMode", SSH_ARGUMENT_MODE_DEFAULT),
        credential.get("sshArguments"),
    )
    return list(arguments) if mode == SSH_ARGUMENT_MODE_USER_PROVIDED else None


def ssh_arguments_for(host: str, port: int = DEFAULT_SSH_PORT) -> List[str]:
    credential, _scope = resolve_credential(host, port)
    return ssh_arguments_for_credential(credential) or []


def storage_protection() -> str:
    return "WINDOWS_DPAPI_CURRENT_USER" if os.name == "nt" else "FILE_MODE_0600"


def _credential_record(
    username: str,
    password: str,
    port: int,
    enable_passwordless: bool,
    allow_cluster_mutual: bool,
    remote_directory: Optional[str],
    ssh_arguments_mode: str,
    ssh_arguments: Optional[Iterable[str]],
) -> Dict[str, Any]:
    normalized_mode, normalized_arguments = normalize_ssh_arguments(ssh_arguments_mode, ssh_arguments)
    record = {
        "username": require_text(username, "username"),
        "credentialSource": "USER_PROVIDED",
        "authenticationMode": "PASSWORD",
        "port": normalize_port(port),
        "enablePasswordless": bool(enable_passwordless),
        "allowClusterMutual": bool(allow_cluster_mutual),
        "remoteDirectory": remote_directory.strip() if remote_directory and remote_directory.strip() else None,
        "sshArgumentsMode": normalized_mode,
        "sshArguments": normalized_arguments,
        "updatedAt": int(time.time() * 1000),
    }
    if os.name == "nt":
        record["protectedPassword"] = _dpapi_protect(require_text(password, "password"))
    else:
        record["password"] = require_text(password, "password")
    return record


def _passwordless_credential_record(
    username: str,
    port: int,
    private_key_path: Optional[str],
    allow_cluster_mutual: bool,
    remote_directory: Optional[str],
    ssh_arguments_mode: str,
    ssh_arguments: Optional[Iterable[str]],
) -> Dict[str, Any]:
    normalized_mode, normalized_arguments = normalize_ssh_arguments(ssh_arguments_mode, ssh_arguments)
    normalized_key_path = None
    if private_key_path and str(private_key_path).strip():
        key_path = Path(str(private_key_path)).expanduser().resolve()
        if not key_path.is_file():
            raise SshCredentialError(f"SSH 私钥不存在: {key_path}")
        normalized_key_path = str(key_path)
    return {
        "username": require_text(username, "username"),
        "credentialSource": "EXISTING_PASSWORDLESS",
        "authenticationMode": "PUBLIC_KEY",
        "privateKeyPath": normalized_key_path,
        "port": normalize_port(port),
        "enablePasswordless": True,
        "allowClusterMutual": bool(allow_cluster_mutual),
        "remoteDirectory": remote_directory.strip() if remote_directory and remote_directory.strip() else None,
        "sshArgumentsMode": normalized_mode,
        "sshArguments": normalized_arguments,
        "updatedAt": int(time.time() * 1000),
    }


def _credential_with_password(record: Dict[str, Any]) -> Dict[str, Any]:
    resolved = dict(record)
    if record.get("protectedPassword"):
        resolved["password"] = _dpapi_unprotect(str(record["protectedPassword"]))
    return resolved


def _restrict_file(path: Path) -> None:
    try:
        path.chmod(stat.S_IRUSR | stat.S_IWUSR)
    except OSError:
        pass
    if os.name == "nt":
        username = os.getenv("USERNAME")
        icacls = shutil.which("icacls")
        if username and icacls:
            subprocess.run(
                [icacls, str(path), "/reset"],
                capture_output=True,
                check=False,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
            subprocess.run(
                [icacls, str(path), "/inheritance:r", "/grant:r", f"{username}:(F)"],
                capture_output=True,
                check=False,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )


def _run_ssh(
    host: str,
    port: int,
    username: str,
    timeout_seconds: int,
    password: Optional[str] = None,
    key_path: Optional[Path] = None,
    remote_command: str = "printf CCRELAY_SSH_OK",
    stdin_text: Optional[str] = None,
    include_output: bool = False,
) -> Dict[str, Any]:
    executable = shutil.which("ssh")
    if not executable:
        return {"success": False, "exitCode": None, "summary": "ssh executable not found", "authMode": "NONE"}
    credential, _scope = resolve_credential(host, port)
    custom_arguments = ssh_arguments_for_credential(credential)
    command = [executable]
    if custom_arguments is None:
        command.extend([
            "-o", "ConnectTimeout={}".format(max(1, int(timeout_seconds))),
            "-o", "ConnectionAttempts=1",
            "-o", "StrictHostKeyChecking=no",
            "-o", "NumberOfPasswordPrompts=1",
        ])
    else:
        command.extend(custom_arguments)
    command.extend(["-p", str(normalize_port(port))])
    environment = os.environ.copy()
    temporary_directory = None
    auth_mode = "PUBLIC_KEY"
    if password is not None:
        auth_mode = "PASSWORD"
        if custom_arguments is None:
            command.extend([
                "-o", "BatchMode=no",
                "-o", "PubkeyAuthentication=no",
                "-o", "PreferredAuthentications=password,keyboard-interactive",
            ])
        temporary_directory = tempfile.TemporaryDirectory(prefix="ccrelay-askpass-")
        askpass = _write_askpass_helper(Path(temporary_directory.name))
        environment["CCRELAY_SSH_PASSWORD"] = password
        environment["SSH_ASKPASS"] = str(askpass)
        environment["SSH_ASKPASS_REQUIRE"] = "force"
        environment.setdefault("DISPLAY", "ccrelay:0")
    else:
        if custom_arguments is None:
            command.extend(["-o", "BatchMode=yes"])
        if key_path and key_path.is_file():
            if custom_arguments is None:
                command.extend(["-o", "IdentitiesOnly=yes"])
            command.extend(["-i", str(key_path)])
    command.extend([f"{username}@{host}", remote_command])
    started = time.monotonic()
    try:
        binary_input = stdin_text is not None
        run_options = {
            "capture_output": True,
            "timeout": max(3, int(timeout_seconds) + 3),
            "input": normalize_posix_stdin(stdin_text).encode("utf-8") if binary_input else None,
            "env": environment,
            "check": False,
            "creationflags": getattr(subprocess, "CREATE_NO_WINDOW", 0),
        }
        if not binary_input:
            run_options.update({"text": True, "encoding": "utf-8", "errors": "replace"})
        completed = subprocess.run(command, **run_options)
        stdout = completed.stdout.decode("utf-8", errors="replace") if binary_input else completed.stdout
        stderr = completed.stderr.decode("utf-8", errors="replace") if binary_input else completed.stderr
        output = _summary(stderr or stdout)
        result = {
            "success": completed.returncode == 0,
            "exitCode": completed.returncode,
            "summary": output,
            "latencyMs": int((time.monotonic() - started) * 1000),
            "authMode": auth_mode,
        }
        if include_output:
            result["stdout"] = stdout
            result["stderr"] = stderr
        return result
    except subprocess.TimeoutExpired:
        return {
            "success": False,
            "exitCode": None,
            "summary": "SSH connection timed out",
            "latencyMs": int((time.monotonic() - started) * 1000),
            "authMode": auth_mode,
        }
    except OSError as exc:
        return {
            "success": False,
            "exitCode": None,
            "summary": str(exc),
            "latencyMs": int((time.monotonic() - started) * 1000),
            "authMode": auth_mode,
        }
    finally:
        environment.pop("CCRELAY_SSH_PASSWORD", None)
        if temporary_directory is not None:
            temporary_directory.cleanup()


def normalize_posix_stdin(value: Optional[str]) -> str:
    return str(value or "").replace("\r\n", "\n").replace("\r", "\n")


def _write_askpass_helper(directory: Path) -> Path:
    if os.name == "nt":
        helper = directory / "ccrelay-askpass.cmd"
        helper.write_text("@echo off\r\n\"{}\" \"{}\"\r\n".format(sys.executable, directory / "askpass.py"), encoding="utf-8")
        script = directory / "askpass.py"
        script.write_text("import os\nprint(os.environ.get('CCRELAY_SSH_PASSWORD', ''))\n", encoding="utf-8")
        return helper
    helper = directory / "ccrelay-askpass.sh"
    helper.write_text("#!/bin/sh\nprintf '%s\\n' \"$CCRELAY_SSH_PASSWORD\"\n", encoding="utf-8")
    helper.chmod(stat.S_IRUSR | stat.S_IWUSR | stat.S_IXUSR)
    return helper


def run_authenticated_command(
    host: str,
    port: int,
    username: str,
    remote_command: str,
    timeout_seconds: int = DEFAULT_TIMEOUT_SECONDS,
    stdin_text: Optional[str] = None,
    include_output: bool = False,
) -> Dict[str, Any]:
    credential, _scope = resolve_credential(host, port)
    key_result = _run_ssh(
        host, port, username, timeout_seconds,
        key_path=credential_key_path(credential), remote_command=remote_command,
        stdin_text=stdin_text, include_output=include_output,
    )
    if key_result.get("success"):
        return key_result
    if not credential or not credential.get("password"):
        return key_result
    return _run_ssh(
        host, port, username, timeout_seconds,
        password=credential.get("password"), remote_command=remote_command,
        stdin_text=stdin_text, include_output=include_output,
    )


def run_key_command(
    host: str,
    port: int,
    username: str,
    key_path: Path,
    remote_command: str,
    timeout_seconds: int = DEFAULT_TIMEOUT_SECONDS,
    stdin_text: Optional[str] = None,
    include_output: bool = False,
) -> Dict[str, Any]:
    return _run_ssh(
        host,
        port,
        username,
        timeout_seconds,
        key_path=Path(key_path).expanduser().resolve(),
        remote_command=remote_command,
        stdin_text=stdin_text,
        include_output=include_output,
    )


def copy_to_remote(
    host: str,
    port: int,
    username: str,
    sources: Iterable[Path],
    remote_directory: str,
    timeout_seconds: int = 600,
    key_path: Optional[Path] = None,
    allow_password_fallback: bool = True,
) -> Dict[str, Any]:
    source_paths = [Path(item).expanduser().resolve() for item in sources]
    missing = [str(item) for item in source_paths if not item.exists()]
    if missing:
        return {"success": False, "exitCode": None, "summary": "local source missing: " + ", ".join(missing)}
    credential, _scope = resolve_credential(host, port)
    selected_key = Path(key_path).expanduser().resolve() if key_path else credential_key_path(credential)
    result = _run_scp(
        host,
        port,
        username,
        source_paths,
        remote_directory,
        timeout_seconds,
        key_path=selected_key,
    )
    if result.get("success") or not allow_password_fallback:
        return result
    if not credential or credential.get("username") != username or not credential.get("password"):
        return result
    return _run_scp(
        host,
        port,
        username,
        source_paths,
        remote_directory,
        timeout_seconds,
        password=credential.get("password"),
    )


def _run_scp(
    host: str,
    port: int,
    username: str,
    sources: List[Path],
    remote_directory: str,
    timeout_seconds: int,
    password: Optional[str] = None,
    key_path: Optional[Path] = None,
) -> Dict[str, Any]:
    executable = shutil.which("scp")
    if not executable:
        return {"success": False, "exitCode": None, "summary": "scp executable not found", "authMode": "NONE"}
    credential, _scope = resolve_credential(host, port)
    custom_arguments = ssh_arguments_for_credential(credential)
    command = [executable, "-r"]
    if custom_arguments is None:
        command.extend([
            "-o", "ConnectTimeout={}".format(max(1, min(int(timeout_seconds), 60))),
            "-o", "ConnectionAttempts=1",
            "-o", "StrictHostKeyChecking=no",
            "-o", "NumberOfPasswordPrompts=1",
        ])
    else:
        command.extend(custom_arguments)
    command.extend(["-P", str(normalize_port(port))])
    environment = os.environ.copy()
    temporary_directory = None
    auth_mode = "PUBLIC_KEY"
    if password is not None:
        auth_mode = "PASSWORD"
        if custom_arguments is None:
            command.extend([
                "-o", "BatchMode=no",
                "-o", "PubkeyAuthentication=no",
                "-o", "PreferredAuthentications=password,keyboard-interactive",
            ])
        temporary_directory = tempfile.TemporaryDirectory(prefix="ccrelay-askpass-")
        askpass = _write_askpass_helper(Path(temporary_directory.name))
        environment["CCRELAY_SSH_PASSWORD"] = password
        environment["SSH_ASKPASS"] = str(askpass)
        environment["SSH_ASKPASS_REQUIRE"] = "force"
        environment.setdefault("DISPLAY", "ccrelay:0")
    else:
        if custom_arguments is None:
            command.extend(["-o", "BatchMode=yes"])
        if key_path and key_path.is_file():
            if custom_arguments is None:
                command.extend(["-o", "IdentitiesOnly=yes"])
            command.extend(["-i", str(key_path)])
    command.extend(str(item).replace("\\", "/") if os.name == "nt" else str(item) for item in sources)
    command.append(f"{username}@{host}:{remote_directory}")
    started = time.monotonic()
    try:
        completed = subprocess.run(
            command,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=max(10, int(timeout_seconds)),
            env=environment,
            check=False,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
        return {
            "success": completed.returncode == 0,
            "exitCode": completed.returncode,
            "summary": _summary(completed.stderr or completed.stdout),
            "latencyMs": int((time.monotonic() - started) * 1000),
            "authMode": auth_mode,
        }
    except subprocess.TimeoutExpired:
        return {
            "success": False,
            "exitCode": None,
            "summary": "SCP transfer timed out",
            "latencyMs": int((time.monotonic() - started) * 1000),
            "authMode": auth_mode,
        }
    except OSError as exc:
        return {
            "success": False,
            "exitCode": None,
            "summary": str(exc),
            "latencyMs": int((time.monotonic() - started) * 1000),
            "authMode": auth_mode,
        }
    finally:
        environment.pop("CCRELAY_SSH_PASSWORD", None)
        if temporary_directory is not None:
            temporary_directory.cleanup()


def probe_connection(host: str, port: int = DEFAULT_SSH_PORT, username: Optional[str] = None,
                     timeout_seconds: int = DEFAULT_TIMEOUT_SECONDS) -> Dict[str, Any]:
    credential, scope = resolve_credential(host, port)
    selected_username = username or (credential or {}).get("username")
    if not selected_username:
        return missing_credential_result(host, port)
    result = run_authenticated_command(host, port, selected_username, "printf CCRELAY_SSH_OK", timeout_seconds)
    if result.get("success"):
        auth_mode = result.get("authMode", "PUBLIC_KEY")
        success = success_result(host, port, selected_username, auth_mode, scope, result)
        if auth_mode == "PASSWORD":
            success["status"] = "CREDENTIAL_VALID"
            success["readyForCenterDeploy"] = False
        return success
    return failure_result(host, port, classify_ssh_failure(result), result.get("summary") or "SSH 连接失败",
                          username=selected_username, credentialScope=scope)


def protect_secret(value: str) -> str:
    if os.name == "nt":
        return _dpapi_protect(require_text(value, "secret"))
    return require_text(value, "secret")


def unprotect_secret(value: str) -> str:
    if os.name == "nt":
        return _dpapi_unprotect(require_text(value, "secret"))
    return require_text(value, "secret")


def random_secret() -> str:
    groups = (
        "ABCDEFGHJKLMNPQRSTUVWXYZ",
        "abcdefghijkmnopqrstuvwxyz",
        "23456789",
        "!@#%+=_-",
    )
    characters = [secrets.choice(group) for group in groups]
    alphabet = "".join(groups)
    characters.extend(secrets.choice(alphabet) for _ in range(28))
    secrets.SystemRandom().shuffle(characters)
    return "".join(characters)


def secret_meets_complexity_policy(value: str) -> bool:
    secret = str(value or "")
    return (
        len(secret) >= 24
        and any(character.isupper() for character in secret)
        and any(character.islower() for character in secret)
        and any(character.isdigit() for character in secret)
        and any(character in "!@#%+=_-" for character in secret)
        and not any(character in ":'\"\\" or character.isspace() for character in secret)
    )


def _summary(value: Any, limit: int = 600) -> str:
    text = str(value or "").strip().replace("\x00", "")
    return text if len(text) <= limit else text[: limit - 3] + "..."


def _shell_single_quote(value: str) -> str:
    return "'" + value.replace("'", "'\"'\"'") + "'"


class _DataBlob(ctypes.Structure):
    _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_byte))]


def _dpapi_protect(value: str) -> str:
    if os.name != "nt":
        return value
    raw = value.encode("utf-8")
    buffer = ctypes.create_string_buffer(raw)
    input_blob = _DataBlob(len(raw), ctypes.cast(buffer, ctypes.POINTER(ctypes.c_byte)))
    output_blob = _DataBlob()
    crypt32 = ctypes.windll.crypt32
    kernel32 = ctypes.windll.kernel32
    if not crypt32.CryptProtectData(ctypes.byref(input_blob), "ccrelay", None, None, None, 0, ctypes.byref(output_blob)):
        raise SshCredentialError("Windows DPAPI 加密 SSH 密码失败")
    try:
        protected = ctypes.string_at(output_blob.pbData, output_blob.cbData)
        return base64.b64encode(protected).decode("ascii")
    finally:
        kernel32.LocalFree(output_blob.pbData)


def _dpapi_unprotect(value: str) -> str:
    if os.name != "nt":
        return value
    try:
        raw = base64.b64decode(value)
    except ValueError as exc:
        raise SshCredentialError("SSH 密码密文格式无效") from exc
    buffer = ctypes.create_string_buffer(raw)
    input_blob = _DataBlob(len(raw), ctypes.cast(buffer, ctypes.POINTER(ctypes.c_byte)))
    output_blob = _DataBlob()
    crypt32 = ctypes.windll.crypt32
    kernel32 = ctypes.windll.kernel32
    if not crypt32.CryptUnprotectData(ctypes.byref(input_blob), None, None, None, None, 0, ctypes.byref(output_blob)):
        raise SshCredentialError("Windows DPAPI 解密 SSH 密码失败；配置可能由其他用户创建")
    try:
        return ctypes.string_at(output_blob.pbData, output_blob.cbData).decode("utf-8")
    finally:
        kernel32.LocalFree(output_blob.pbData)
