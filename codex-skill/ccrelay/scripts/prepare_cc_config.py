#!/usr/bin/env python3
import argparse
import getpass
import json
import os
import shlex
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path


DEFAULT_MODEL = "deepseek-v4-pro"
DEFAULT_BASE_URL = "https://api.deepseek.com/anthropic"
DEFAULT_API_PATH = "/v1/messages"


def windows_interactive_desktop_available():
    if os.name != "nt":
        return False
    try:
        import ctypes

        desktop = ctypes.windll.user32.OpenInputDesktop(0, False, 0x0100)
        if not desktop:
            return False
        ctypes.windll.user32.CloseDesktop(desktop)
        return True
    except Exception:
        return False


def input_capabilities():
    if os.name == "nt":
        gui_terminal = bool(
            os.environ.get("WT_SESSION")
            or os.environ.get("ConEmuANSI")
            or os.environ.get("TERM_PROGRAM")
            or os.environ.get("SESSIONNAME")
            or windows_interactive_desktop_available()
        )
        return {
            "os": "WINDOWS",
            "ttyAvailable": bool(sys.stdin.isatty() and sys.stderr.isatty()),
            "guiTerminalLauncherDetected": gui_terminal,
            "secureTerminalLauncher": "powershell.exe",
        }
    terminals = [
        candidate
        for candidate in ("x-terminal-emulator", "gnome-terminal", "konsole", "xterm")
        if shutil.which(candidate)
    ]
    return {
        "os": "LINUX",
        "ttyAvailable": bool(sys.stdin.isatty() and sys.stderr.isatty()),
        "guiTerminalLauncherDetected": bool(
            os.environ.get("DISPLAY") or os.environ.get("WAYLAND_DISPLAY")
        ) and bool(terminals),
        "secureTerminalLauncher": terminals[0] if terminals else None,
    }


def secure_terminal_command(arguments):
    script_dir = Path(__file__).resolve().parent
    if os.name == "nt":
        wrapper = script_dir / "prepare-cc-config.ps1"
        rendered = subprocess.list2cmdline([str(item) for item in arguments])
        return (
            f'powershell -NoProfile -ExecutionPolicy Bypass -File '
            f'{subprocess.list2cmdline([str(wrapper)])} {rendered}'
        )
    wrapper = script_dir / "prepare-cc-config.sh"
    rendered = " ".join(shlex.quote(str(item)) for item in arguments)
    return f"bash {shlex.quote(str(wrapper))} {rendered}"


def powershell_quote(value):
    return "'" + str(value).replace("'", "''") + "'"


def launch_secure_terminal(arguments):
    capabilities = input_capabilities()
    if not capabilities["guiTerminalLauncherDetected"]:
        return {
            "status": "NEED_USER_INPUT",
            "stage": "API_KEY_SECURE_TERMINAL_UNAVAILABLE",
            "reason": "GUI_TERMINAL_NOT_DETECTED",
            "inputCapabilities": capabilities,
            "interaction": api_key_input_interaction(
                arguments, capabilities, prefer_gui=False
            ),
        }
    script_dir = Path(__file__).resolve().parent
    if os.name == "nt":
        wrapper = script_dir / "prepare-cc-config.ps1"
        command = "& " + powershell_quote(wrapper)
        command += " " + " ".join(
            powershell_quote(item) for item in arguments
        )
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
        wrapper = script_dir / "prepare-cc-config.sh"
        command = "bash " + shlex.quote(str(wrapper))
        command += " " + " ".join(shlex.quote(str(item)) for item in arguments)
        launcher = capabilities["secureTerminalLauncher"]
        if launcher == "gnome-terminal":
            launch_arguments = [launcher, "--", "bash", "-lc", command]
        else:
            launch_arguments = [launcher, "-e", "bash", "-lc", command]
        subprocess.Popen(launch_arguments)
    return {
        "status": "SECURE_TERMINAL_LAUNCHED",
        "inputCapabilities": capabilities,
        "command": secure_terminal_command(arguments),
        "resume": "安全终端完成后回到当前会话，读取测试结果；不要回传 API key。",
    }


def api_key_input_interaction(arguments, capabilities=None, prefer_gui=True):
    capabilities = capabilities or input_capabilities()
    options = [
        {
            "id": "LAUNCH_SECURE_TERMINAL",
            "label": "弹出安全终端隐藏输入（高风险 + 有桌面 GUI）",
            "recommended": False,
            "available": capabilities["guiTerminalLauncherDetected"],
            "applicability": "高风险环境且有桌面 GUI；用户明确选择后才执行。",
            "userChoiceRequired": True,
            "command": secure_terminal_command(arguments),
            "security": "API key 只在终端隐藏输入，不进入当前会话。",
        },
        {
            "id": "SECURE_TERMINAL_COMMAND",
            "label": "安全输入命令（高风险 + 无桌面 GUI）",
            "recommended": False,
            "available": True,
            "applicability": "高风险环境且无桌面 GUI、只有终端；用户明确选择后执行返回命令。",
            "userChoiceRequired": True,
            "command": secure_terminal_command(arguments),
            "security": "暂时离开当前会话，在终端隐藏输入后再回来继续。",
        },
        {
            "id": "MANUAL_VISIBLE_INPUT",
            "label": "直接明文填写（低风险 + 无桌面 GUI）",
            "recommended": False,
            "applicability": "仅适用于低风险或用户明确接受明文风险的环境。",
            "userChoiceRequired": True,
            "available": True,
            "securityWarning": "API key 会进入对话记录、模型上下文或审计系统；仅在用户明确接受该风险时使用。",
            "fields": [
                {"name": "apiKey", "label": "API key", "required": True, "secret": True},
            ],
        },
    ]
    return {
        "agentAction": "RETURN_VERBATIM_RESPONSE_AND_STOP",
        "mustStopCurrentTurn": True,
        "prompt": "请选择 API key 输入方式：",
        "userChoiceRequired": True,
        "inputCapabilities": capabilities,
        "options": options,
        "verbatimResponse": (
            "请选择 API key 输入方式：\n\n"
            "1. 安全终端弹窗（高风险环境 + 有桌面 GUI）\n"
            "2. 安全输入命令（高风险环境 + 仅终端）\n"
            "3. 明文输入（低风险环境 + 接受 API key 进入对话记录）\n"
            "4. 取消\n\n"
            "请回复选项序号。"
        ),
        "resume": "安全输入完成后回到当前会话继续，不要在对话中回传 API key。",
    }


def read_text(path):
    try:
        return Path(path).read_text(encoding="utf-8")
    except FileNotFoundError:
        return ""


def parse_simple_yaml(text):
    data = {}
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or ":" not in stripped:
            continue
        key, value = stripped.split(":", 1)
        value = value.strip().strip('"').strip("'")
        if value and value.lower() not in {"null", "{}"}:
            data[key.strip()] = value
    return data


def parse_json_file(path):
    try:
        value = json.loads(Path(path).read_text(encoding="utf-8-sig"))
        return value if isinstance(value, dict) else {}
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        return {}


def first_value(values, *keys):
    for key in keys:
        value = str(values.get(key) or "").strip()
        if value:
            return value
    return ""


def anthropic_candidate(source, values):
    api_key = first_value(
        values,
        "ANTHROPIC_API_KEY",
        "ANTHROPIC_AUTH_TOKEN",
        "CLAUDE_CODE_API_KEY",
    )
    base_url = first_value(values, "ANTHROPIC_BASE_URL")
    model = first_value(
        values,
        "ANTHROPIC_MODEL",
        "ANTHROPIC_DEFAULT_SONNET_MODEL",
        "ANTHROPIC_DEFAULT_OPUS_MODEL",
        "ANTHROPIC_DEFAULT_HAIKU_MODEL",
    )
    if not (api_key or base_url or model):
        return None
    return {
        "source": source,
        "provider": "deepseek-anthropic-compatible",
        "model": model,
        "baseUrl": base_url,
        "apiKey": api_key,
        "apiPath": DEFAULT_API_PATH,
    }


def claude_settings_paths():
    paths = []
    configured = str(os.environ.get("CLAUDE_CONFIG_DIR") or "").strip()
    if configured:
        paths.append(Path(configured).expanduser() / "settings.json")
    paths.append(Path.home() / ".claude" / "settings.json")
    unique = []
    for path in paths:
        if path not in unique:
            unique.append(path)
    return unique


def discover_local_config():
    candidates = []
    env = os.environ
    candidate = anthropic_candidate("env:ANTHROPIC_*", env)
    if candidate:
        candidates.append(candidate)
    if env.get("OPENAI_API_KEY") or env.get("OPENAI_BASE_URL") or env.get("OPENAI_MODEL"):
        candidates.append({
            "source": "env:OPENAI_*",
            "provider": "openai-compatible",
            "model": env.get("OPENAI_MODEL") or env.get("OPENAI_DEFAULT_MODEL") or "",
            "baseUrl": env.get("OPENAI_BASE_URL") or env.get("OPENAI_API_BASE") or "",
            "apiKey": env.get("OPENAI_API_KEY") or "",
            "apiPath": "/chat/completions",
        })
    for path in claude_settings_paths():
        settings = parse_json_file(path)
        settings_env = settings.get("env")
        if not isinstance(settings_env, dict):
            continue
        candidate = anthropic_candidate(f"claude-settings:{path}", settings_env)
        if candidate:
            candidates.append(candidate)
    home = Path.home()
    for path in [
        home / ".codex" / "cc-model-config.yml",
        home / ".codex" / "model-config.yml",
        home / ".config" / "ccrelay" / "cc-model-config.yml",
        home / ".config" / "wdsavs-ai-agent-runtime" / "cc-model-config.yml",
    ]:
        parsed = parse_simple_yaml(read_text(path))
        if parsed:
            parsed.setdefault("source", str(path))
            parsed.setdefault("provider", "deepseek-anthropic-compatible")
            parsed.setdefault("apiPath", DEFAULT_API_PATH)
            candidates.append(parsed)
    return candidates


def model_config_source_interaction(candidates):
    detected = bool(candidates)
    if detected:
        options = [
            {"id": "USE_DETECTED_LOCAL_CONFIG", "label": "使用检测到的本机模型配置", "recommended": True},
            {"id": "CONFIGURE_NEW_MODEL", "label": "填写新的模型配置", "recommended": False},
            {"id": "CANCEL", "label": "取消", "recommended": False},
        ]
        verbatim_response = (
            "检测到本机模型服务配置。请选择模型配置来源：\n\n"
            "1. 使用检测到的本机模型配置\n"
            "2. 填写新的模型配置\n"
            "3. 取消\n\n"
            "请回复选项序号。"
        )
    else:
        options = [
            {"id": "CONFIGURE_NEW_MODEL", "label": "填写新的模型配置", "recommended": True},
            {"id": "CANCEL", "label": "取消", "recommended": False},
        ]
        verbatim_response = (
            "未检测到可复用的本机模型配置。请选择：\n\n"
            "1. 填写新的模型配置\n"
            "2. 取消\n\n"
            "请回复选项序号。"
        )
    return {
        "status": "NEED_USER_INPUT",
        "stage": "MODEL_CONFIG_SOURCE_REQUIRED",
        "agentAction": "RETURN_VERBATIM_RESPONSE_AND_STOP",
        "mustStopCurrentTurn": True,
        "userChoiceRequired": True,
        "detectedCandidates": candidates,
        "interaction": {
            "prompt": "请选择模型配置来源：",
            "options": options,
            "fields": [],
        },
        "verbatimResponse": verbatim_response,
        "resume": "用户选择使用本机配置后，才检查所选配置的缺失字段；选择填写新配置后，才收集模型和 Base URL。",
    }


def normalize_base_url(base_url):
    base_url = (base_url or "").strip().rstrip("/")
    if base_url.endswith("/v1/messages"):
        return base_url[: -len("/v1/messages")]
    if base_url.endswith("/chat/completions"):
        return base_url[: -len("/chat/completions")]
    return base_url


def write_config(path, values):
    output = Path(path)
    output.parent.mkdir(parents=True, exist_ok=True)
    provider = values.get("provider") or "deepseek-anthropic-compatible"
    api_path = values.get("apiPath") or DEFAULT_API_PATH
    relay_command = values.get("relayCommand") or "claude"
    relay_args = values.get("relayArgs") or ["--bare", "--print", "--model", values.get("model") or DEFAULT_MODEL, "--effort", "low"]
    if isinstance(relay_args, str):
        relay_args = [item for item in relay_args.split() if item]
    lines = [
        "# CC Relay Claude Code 配置",
        "# 该文件可随 runtime bundle 下发到远端；不要提交真实 apiKey。",
        f"provider: {provider}",
        f"model: \"{values.get('model') or DEFAULT_MODEL}\"",
        f"baseUrl: \"{normalize_base_url(values.get('baseUrl') or DEFAULT_BASE_URL)}\"",
        f"apiKey: \"{values.get('apiKey') or ''}\"",
        f"apiPath: {api_path}",
        f"relayCommand: {relay_command}",
        "relayArgs:",
    ]
    lines.extend(f"  - {arg}" for arg in relay_args)
    lines.extend([
        "temperature: null",
        "topP: null",
        "maxTokens: null",
        "extraHeaders: {}",
        "extraBody: {}",
        "",
    ])
    output.write_text("\n".join(lines), encoding="utf-8")
    return output


def test_anthropic_compatible(values):
    base_url = normalize_base_url(values.get("baseUrl") or DEFAULT_BASE_URL)
    api_path = values.get("apiPath") or DEFAULT_API_PATH
    api_key = values.get("apiKey") or ""
    model = values.get("model") or DEFAULT_MODEL
    if not base_url:
        return False, "baseUrl 为空"
    if not model:
        return False, "model 为空"
    if not api_key:
        return False, "apiKey 为空"
    url = base_url + (api_path if api_path.startswith("/") else "/" + api_path)
    payload = {
        "model": model,
        "messages": [{"role": "user", "content": "ping"}],
        "max_tokens": 8,
    }
    request = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "x-api-key": api_key,
            "anthropic-version": "2023-06-01",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            body = response.read().decode("utf-8", errors="replace")
            if response.status < 200 or response.status >= 300:
                return False, f"HTTP {response.status}: {body[:500]}"
            return True, body[:500]
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        return False, f"HTTP {exc.code}: {body[:1000]}"
    except Exception as exc:
        return False, str(exc)


def classify_test_failure(detail):
    normalized = (detail or "").lower()
    if "http 401" in normalized or "http 403" in normalized:
        return "AUTHENTICATION_FAILED"
    if "http 402" in normalized or "insufficient balance" in normalized:
        return "INSUFFICIENT_BALANCE"
    if "http 404" in normalized:
        return "ENDPOINT_OR_MODEL_NOT_FOUND"
    if "http 429" in normalized:
        return "RATE_LIMITED"
    if "timed out" in normalized or "timeout" in normalized:
        return "REQUEST_TIMEOUT"
    if "name or service not known" in normalized or "connection" in normalized:
        return "NETWORK_UNREACHABLE"
    return "MODEL_API_TEST_FAILED"


def test_failure_result(values, detail):
    input_arguments = [
        "--prompt-api-key",
        "--model",
        values.get("model") or DEFAULT_MODEL,
        "--base-url",
        normalize_base_url(values.get("baseUrl") or DEFAULT_BASE_URL),
        "--api-path",
        values.get("apiPath") or DEFAULT_API_PATH,
        "--provider",
        values.get("provider") or "deepseek-anthropic-compatible",
    ]
    return {
        "agentAction": "RETURN_VERBATIM_RESPONSE_AND_STOP",
        "mustStopCurrentTurn": True,
        "status": "NEED_USER_INPUT",
        "stage": "MODEL_CONFIG_TEST_UNAVAILABLE",
        "taskCreated": False,
        "failureType": classify_test_failure(detail),
        "summary": detail,
        "currentConfig": {
            "provider": values.get("provider"),
            "model": values.get("model"),
            "baseUrl": normalize_base_url(values.get("baseUrl")),
            "apiPath": values.get("apiPath"),
        },
        "interaction": {
            "prompt": "模型 API 当前不可用，可能是配置问题，也可能是余额、限流或临时服务状态。请选择重试当前配置、更换配置或取消。",
            "options": [
                {
                    "id": "RETRY_CURRENT_CONFIG",
                    "label": "API 已恢复，重试当前配置（推荐）",
                    "recommended": True,
                },
                {
                    "id": "REPLACE_MODEL_CONFIG",
                    "label": "更换模型、Base URL 或 API Key",
                    "recommended": False,
                },
                {
                    "id": "CANCEL",
                    "label": "取消配置",
                    "recommended": False,
                },
            ],
            "fields": [
                {
                    "name": "model",
                    "label": "模型名称",
                    "required": True,
                    "default": values.get("model"),
                    "secret": False,
                    "visibleWhen": "REPLACE_MODEL_CONFIG",
                },
                {
                    "name": "baseUrl",
                    "label": "Base URL",
                    "required": True,
                    "default": normalize_base_url(values.get("baseUrl")),
                    "secret": False,
                    "visibleWhen": "REPLACE_MODEL_CONFIG",
                },
                {
                    "name": "apiKey",
                    "label": "API Key",
                    "required": True,
                    "default": None,
                    "secret": True,
                    "visibleWhen": "REPLACE_MODEL_CONFIG",
                },
            ],
            "apiKeyInput": api_key_input_interaction(input_arguments),
        },
        "verbatimResponse": (
            "模型配置暂时不可用。\n\n"
            f"原因: {detail}\n\n"
            "1. API 已恢复，重试当前配置（推荐）\n"
            "2. 更换模型、Base URL 或 API key\n"
            "3. 取消配置\n\n"
            "请回复选项序号。"
        ),
        "resume": "选择重试时使用相同参数再次执行；选择更换时收集字段并重新测试；取消时不得写入配置。",
    }


def main():
    parser = argparse.ArgumentParser(description="准备 CC Relay Claude Code 配置")
    parser.add_argument("--discover", action="store_true", help="发现本机可用模型配置")
    parser.add_argument("--write", help="写入 yml 配置文件")
    parser.add_argument("--test", action="store_true", help="测试 Anthropic 兼容接口")
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--api-key")
    parser.add_argument("--api-key-file", help="从本地文件读取 API key，不在命令行中暴露")
    parser.add_argument("--api-key-env", help="从进程环境变量读取 API key，不写入命令行")
    parser.add_argument("--input-options", action="store_true", help="返回 API key 输入方式选项")
    parser.add_argument("--launch-secure-terminal", action="store_true", help="拉起安全终端并隐藏输入 API key")
    parser.add_argument("--prompt-api-key", action="store_true", help="在当前终端隐藏输入 API key")
    parser.add_argument("--api-path", default=DEFAULT_API_PATH)
    parser.add_argument("--provider", default="deepseek-anthropic-compatible")
    args = parser.parse_args()

    if args.discover:
        print(json.dumps(model_config_source_interaction(discover_local_config()), ensure_ascii=False, indent=2))
        return 0

    secure_arguments = [
        "--prompt-api-key",
        "--model",
        args.model or DEFAULT_MODEL,
        "--base-url",
        args.base_url or DEFAULT_BASE_URL,
        "--api-path",
        args.api_path or DEFAULT_API_PATH,
        "--provider",
        args.provider or "deepseek-anthropic-compatible",
    ]
    if args.write:
        secure_arguments.extend(["--write", args.write])
    if args.test:
        secure_arguments.append("--test")
    if args.input_options:
        print(json.dumps(api_key_input_interaction(secure_arguments), ensure_ascii=False, indent=2))
        return 0
    if args.launch_secure_terminal:
        print(json.dumps(launch_secure_terminal(secure_arguments), ensure_ascii=False, indent=2))
        return 0

    api_key = args.api_key or ""
    if args.api_key_file:
        api_key = read_text(args.api_key_file).strip()
    if args.api_key_env:
        api_key = os.environ.get(args.api_key_env, "").strip()
    if args.prompt_api_key:
        if not (sys.stdin.isatty() and sys.stderr.isatty()):
            print(json.dumps({
                "status": "NEED_USER_INPUT",
                "stage": "API_KEY_INPUT_REQUIRED",
                "reason": "NO_INTERACTIVE_TTY",
                "interaction": api_key_input_interaction(secure_arguments, prefer_gui=False),
            }, ensure_ascii=False, indent=2))
            return 3
        api_key = getpass.getpass("API key（输入不会回显）: ").strip()
    if (args.test or args.write) and not api_key:
        print(json.dumps({
            "status": "NEED_USER_INPUT",
            "stage": "API_KEY_INPUT_REQUIRED",
            "reason": "API_KEY_MISSING",
            "interaction": api_key_input_interaction(secure_arguments),
        }, ensure_ascii=False, indent=2))
        return 3
    values = {
        "provider": args.provider,
        "model": args.model or DEFAULT_MODEL,
        "baseUrl": args.base_url or DEFAULT_BASE_URL,
        "apiKey": api_key,
        "apiPath": args.api_path or DEFAULT_API_PATH,
    }
    if args.test:
        ok, detail = test_anthropic_compatible(values)
        if not ok:
            print(json.dumps(test_failure_result(values, detail), ensure_ascii=False))
            return 2
        print(json.dumps({"status": "PASS", "detail": detail}, ensure_ascii=False))
    if args.write:
        output = write_config(args.write, values)
        print(json.dumps({"status": "CONFIG_WRITTEN", "path": str(output)}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
