from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Validate, install, and regression-test the local Codex skill flow.")
    parser.add_argument("--repo-root", default=str(Path(__file__).resolve().parents[1]), help="Standalone repo root")
    parser.add_argument("--codex-home", help="Override CODEX_HOME; defaults to $CODEX_HOME or ~/.codex")
    parser.add_argument("--skip-build", action="store_true", help="Use the existing dist skill without rebuilding")
    parser.add_argument("--skip-gradle-tests", action="store_true", help="Skip Java regression tests")
    return parser.parse_args()


def run(cmd: list[str], cwd: Path | None = None) -> str:
    environment = os.environ.copy()
    environment["PYTHONUTF8"] = "1"
    completed = subprocess.run(
        cmd,
        cwd=str(cwd) if cwd else None,
        check=False,
        text=True,
        encoding="utf-8",
        errors="replace",
        capture_output=True,
        env=environment,
    )
    if completed.returncode != 0:
        raise RuntimeError(
            "Command failed (exit code {code}): {cmd}\nstdout:\n{stdout}\nstderr:\n{stderr}".format(
                code=completed.returncode,
                cmd=" ".join(cmd),
                stdout=completed.stdout,
                stderr=completed.stderr,
            )
        )
    return completed.stdout.strip()


def resolve_python() -> str:
    executable = Path(sys.executable) if sys.executable else None
    if executable and executable.exists() and "WindowsApps" not in str(executable):
        return str(executable)
    candidates = [
        shutil.which("python.exe"),
        shutil.which("python"),
        shutil.which("py.exe"),
        shutil.which("py"),
    ]
    for candidate in candidates:
        if candidate and "WindowsApps" not in candidate:
            return candidate
    if executable and executable.exists():
        return str(executable)
    raise RuntimeError("Unable to locate a usable Python interpreter")


def resolve_codex_home(value: str | None) -> Path:
    if value:
        return Path(value).expanduser().resolve()
    env = os.environ.get("CODEX_HOME")
    if env:
        return Path(env).expanduser().resolve()
    return (Path.home() / ".codex").resolve()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def main() -> int:
    args = parse_args()
    repo_root = Path(args.repo_root).expanduser().resolve()
    skill_dir = repo_root / "codex-skill" / "ccrelay"
    package_dir = repo_root / "dist" / "skill" / "ccrelay"
    skill_md = skill_dir / "SKILL.md"
    validator = Path.home() / ".codex" / "skills" / ".system" / "skill-creator" / "scripts" / "quick_validate.py"
    installer = repo_root / "scripts" / "install_codex_skill.py"
    codex_home = resolve_codex_home(args.codex_home)
    python_exe = resolve_python()

    run([python_exe, str(validator), str(skill_dir)], cwd=repo_root)
    if not args.skip_build:
        run(["powershell", "-ExecutionPolicy", "Bypass", "-File", str(repo_root / "build.ps1")], cwd=repo_root)
    run([python_exe, str(validator), str(package_dir)], cwd=repo_root)
    install_output = run([python_exe, str(installer), str(package_dir), "--codex-home", str(codex_home), "--force"], cwd=repo_root)
    install_lines = [line for line in install_output.splitlines() if line.strip()]
    installed_path = Path(install_lines[0])
    require(
        not any(line.startswith("optional ccrelay-cli shim:") for line in install_lines),
        "Installer must not create a global ccrelay-cli shim by default",
    )

    require(installed_path.exists(), f"Installed skill path does not exist: {installed_path}")
    require((installed_path / "SKILL.md").is_file(), "Installed SKILL.md is missing")
    installed_cli = installed_path / "scripts" / "ccrelay-cli.py"
    installed_cli_ps1 = installed_path / "scripts" / "ccrelay-cli.ps1"
    installed_cli_sh = installed_path / "scripts" / "ccrelay-cli.sh"
    installed_center_module = installed_path / "scripts" / "ccrelay_center.py"
    require(installed_cli.is_file(), "Skill-local ccrelay-cli.py is missing")
    require(installed_cli_ps1.is_file(), "Skill-local ccrelay-cli.ps1 is missing")
    require(installed_cli_sh.is_file(), "Skill-local ccrelay-cli.sh is missing")
    require(installed_center_module.is_file(), "Skill-local remote center planner is missing")
    run([python_exe, str(installed_cli), "--help"], cwd=installed_path)
    installed_cli_text = installed_cli.read_text(encoding="utf-8")
    require(
        'DEFAULT_CENTER_URL = "http://127.0.0.1:18191"' in installed_cli_text,
        "Production CLI default center must be loopback",
    )
    require(
        "http://47.93.195.246:29292" not in installed_cli_text,
        "Production CLI contains a fixed remote test center",
    )
    require(
        "wdsavs-ai-agent-center-dev-secret" not in installed_cli_text,
        "Production CLI contains a development HMAC secret",
    )
    runtime_bundle = installed_path / "assets" / "runtime-bundle" / "ccrelay"
    require((runtime_bundle / "app.jar").is_file(), "Runtime app.jar is missing")
    require((runtime_bundle / "runtime.tar.gz").is_file(), "Bundled Linux JRE archive is missing")
    require((runtime_bundle / "runtime-windows.zip").is_file(), "Bundled Windows JRE archive is missing")
    require(not (runtime_bundle / "runtime").exists(), "Release must not duplicate extracted Linux JRE")
    require(not (runtime_bundle / "runtime-windows").exists(), "Release must not duplicate extracted Windows JRE")
    require((runtime_bundle / "python-windows" / "python.exe").is_file(), "Bundled Windows Python is missing")
    require((runtime_bundle / "python-linux.tar.gz").is_file(), "Bundled Linux Python archive is missing")
    require((runtime_bundle / "bin" / "claude").is_file(), "Extracted bundled Claude Code binary is missing")
    require((runtime_bundle / "tools" / "claude-code-linux-x64.tgz").is_file(), "Bundled Claude Code Linux archive is missing")
    require((runtime_bundle / "install-relay.sh").is_file(), "Relay install script is missing")
    require((runtime_bundle / "install-relay.ps1").is_file(), "Windows Relay install script is missing")
    require((runtime_bundle / "bin" / "ccrelay-cli").is_file(), "Linux Relay collaboration CLI is missing")
    require((runtime_bundle / "bin" / "ccrelay-cli.cmd").is_file(), "Windows Relay collaboration CLI is missing")
    require((runtime_bundle / "bin" / "start.sh").is_file(), "Relay start script is missing")

    source_text = skill_md.read_text(encoding="utf-8")
    installed_text = (installed_path / "SKILL.md").read_text(encoding="utf-8")
    require(source_text == installed_text, "Installed SKILL.md differs from source")

    required_terms = [
        "优先访问已注册且健康的远端 relay",
        "SELF_REPLICATE",
        "中心 SSH 部署只能作为最后兜底",
        "注册成功且健康检查通过",
        "不作为标准 Skill 依赖",
        "NEED_USER_INPUT",
        "ssh prepare-center",
        "deploy resume",
        "自动执行",
        "检视模式",
        "center plan|bootstrap",
    ]
    for term in required_terms:
        require(term in installed_text, f"Required skill guidance term not found: {term}")

    gradle_stdout = ""
    if not args.skip_gradle_tests:
        gradle_cmd = [
            str(repo_root / "gradlew.bat"),
            "--no-daemon",
            "test",
            "--tests",
            "com.webank.wedatasphere.wdsavs.aiagent.service.A2aAgentServiceMultiNodeLocalTest",
            "--tests",
            "com.webank.wedatasphere.wdsavs.aiagent.service.A2aTaskRelayServerMultiNodeLocalTest",
            "--tests",
            "com.webank.wedatasphere.wdsavs.aiagent.service.AiRelayDeployRemoteFirstFallbackTest",
            "--tests",
            "com.webank.wedatasphere.wdsavs.aiagentskill.AiAgentSkillRuntimeStandaloneIntegrationTest",
            "--console=plain",
        ]
        gradle_stdout = run(gradle_cmd, cwd=repo_root)

    result = {
        "repoRoot": str(repo_root),
        "skillSource": str(skill_dir),
        "skillPackage": str(package_dir),
        "skillInstalled": str(installed_path),
        "skillLocalCli": str(installed_cli),
        "runtimeBundle": str(runtime_bundle),
        "validator": str(validator),
        "python": python_exe,
        "codexHome": str(codex_home),
        "buildRan": not args.skip_build,
        "gradleTestsRan": not args.skip_gradle_tests,
        "requiredTerms": required_terms,
        "gradleSummary": gradle_stdout.splitlines()[-5:] if gradle_stdout else [],
        "status": "PASS",
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
