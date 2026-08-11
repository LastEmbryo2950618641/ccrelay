from __future__ import annotations

import argparse
import os
import shutil
import stat
import subprocess
import sys
import tempfile
import time
import uuid
from pathlib import Path


CURRENT_SKILL_NAME = "ccrelay"
LEGACY_SKILL_NAMES = ("wdsavs-ai-agent-runtime",)
SUPPORTED_SKILL_NAMES = (CURRENT_SKILL_NAME, *LEGACY_SKILL_NAMES)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Install a local Codex skill into CODEX_HOME/skills.")
    parser.add_argument("skill_dir", help="Path to the local skill directory that contains SKILL.md")
    parser.add_argument("--codex-home", help="Override CODEX_HOME; defaults to $CODEX_HOME or ~/.codex")
    parser.add_argument("--force", action="store_true", help="Replace an existing installed skill")
    parser.add_argument("--install-cli-shim", action="store_true", help="Optionally install a global ccrelay-cli convenience shim")
    parser.add_argument("--skip-cli-shim", action="store_true", help="Deprecated compatibility flag; global shim is skipped by default")
    return parser.parse_args()


def parse_skill_name(skill_md: Path) -> str:
    lines = skill_md.read_text(encoding="utf-8").splitlines()
    if not lines or lines[0].strip() != "---":
        raise ValueError(f"Invalid frontmatter in {skill_md}")
    for line in lines[1:]:
        if line.strip() == "---":
            break
        if line.startswith("name:"):
            value = line.split(":", 1)[1].strip()
            if value:
                return value
    raise ValueError(f"Skill name not found in {skill_md}")


def resolve_codex_home(value: str | None) -> Path:
    if value:
        return Path(value).expanduser().resolve()
    env = os.environ.get("CODEX_HOME")
    if env:
        return Path(env).expanduser().resolve()
    return (Path.home() / ".codex").resolve()


def install_ccrelay_cli_shim(codex_home: Path, skill_name: str, target_dir: Path) -> Path | None:
    cli_script = target_dir / "scripts" / "ccrelay-cli.py"
    if skill_name != CURRENT_SKILL_NAME or not cli_script.is_file():
        return None

    bin_dir = codex_home / "bin"
    bin_dir.mkdir(parents=True, exist_ok=True)
    write_windows_cmd(bin_dir / "ccrelay-cli.cmd", cli_script)
    write_powershell_shim(bin_dir / "ccrelay-cli.ps1", cli_script)
    write_unix_shim(bin_dir / "ccrelay-cli", cli_script)
    mirror_to_current_path(codex_home, cli_script)
    return bin_dir


def remove_legacy_ccrelay_cli_shims(codex_home: Path, skill_name: str) -> list[Path]:
    if skill_name != CURRENT_SKILL_NAME:
        return []

    candidates = [
        codex_home / "bin" / "ccrelay-cli.cmd",
        codex_home / "bin" / "ccrelay-cli.ps1",
        codex_home / "bin" / "ccrelay-cli",
    ]
    for raw_dir in os.environ.get("PATH", "").split(os.pathsep):
        if not raw_dir:
            continue
        path_dir = Path(raw_dir)
        try:
            resolved = path_dir.resolve()
        except OSError:
            continue
        if str(resolved).lower().startswith(str((codex_home / "tmp" / "arg0").resolve()).lower()):
            candidates.append(resolved / "ccrelay-cli.cmd")
            candidates.append(resolved / "ccrelay-cli.ps1")

    removed: list[Path] = []
    for candidate in candidates:
        try:
            if not candidate.is_file():
                continue
            text = candidate.read_text(encoding="utf-8", errors="ignore")
            if not any(name in text for name in SUPPORTED_SKILL_NAMES) or "ccrelay-cli.py" not in text:
                continue
            candidate.unlink()
            removed.append(candidate)
        except OSError:
            continue
    return removed


def mirror_to_current_path(codex_home: Path, cli_script: Path) -> None:
    preferred_dirs = []
    for raw_dir in os.environ.get("PATH", "").split(os.pathsep):
        if not raw_dir:
            continue
        path_dir = Path(raw_dir)
        try:
            resolved = path_dir.resolve()
        except OSError:
            continue
        if not resolved.is_dir():
            continue
        if str(resolved).lower() == str((codex_home / "bin").resolve()).lower():
            preferred_dirs.append(resolved)
        elif str(resolved).lower().startswith(str((codex_home / "tmp" / "arg0").resolve()).lower()):
            preferred_dirs.append(resolved)
    for path_dir in preferred_dirs:
        try:
            write_windows_cmd(path_dir / "ccrelay-cli.cmd", cli_script)
            write_powershell_shim(path_dir / "ccrelay-cli.ps1", cli_script)
            return
        except OSError:
            continue


def write_windows_cmd(path: Path, cli_script: Path) -> None:
    path.write_text(
        "\r\n".join(
            [
                "@echo off",
                "set PYTHONIOENCODING=utf-8",
                "where py >nul 2>nul",
                "if %ERRORLEVEL%==0 (",
                f'  py -3 "{cli_script}" %*',
                "  exit /b %ERRORLEVEL%",
                ")",
                f'python "{cli_script}" %*',
                "exit /b %ERRORLEVEL%",
                "",
            ]
        ),
        encoding="utf-8",
    )


def write_powershell_shim(path: Path, cli_script: Path) -> None:
    path.write_text(
        "\n".join(
            [
                "param(",
                "    [Parameter(ValueFromRemainingArguments = $true)]",
                "    [string[]]$Arguments",
                ")",
                "$ErrorActionPreference = 'Stop'",
                "$env:PYTHONIOENCODING = 'utf-8'",
                "if (Get-Command py -ErrorAction SilentlyContinue) {",
                f"    & py -3 '{cli_script}' @Arguments",
                "    exit $LASTEXITCODE",
                "}",
                f"& python '{cli_script}' @Arguments",
                "exit $LASTEXITCODE",
                "",
            ]
        ),
        encoding="utf-8",
    )


def write_unix_shim(path: Path, cli_script: Path) -> None:
    path.write_text(
        "\n".join(
            [
                "#!/usr/bin/env sh",
                "set -eu",
                f'exec python3 "{cli_script.as_posix()}" "$@"',
                "",
            ]
        ),
        encoding="utf-8",
    )
    try:
        path.chmod(0o755)
    except OSError:
        pass


def copy_skill_tree(skill_dir: Path, target_dir: Path, merge: bool = False) -> None:
    ignore = shutil.ignore_patterns(".local", "__pycache__", "*.pyc")
    shutil.copytree(skill_dir, target_dir, ignore=ignore, dirs_exist_ok=merge)


def contains_local_state(local_dir: Path) -> bool:
    return local_dir.is_dir() and any(path.is_file() for path in local_dir.rglob("*"))


def preserve_local_state(
        install_root: Path,
        skill_name: str,
        current_local_dir: Path | None,
        recovery_roots: list[Path]) -> tuple[Path | None, Path | None]:
    recovery_dirs = [
        root / ".local"
        for root in reversed(recovery_roots)
        if contains_local_state(root / ".local")
    ]
    if current_local_dir and contains_local_state(current_local_dir):
        recovery_dirs.append(current_local_dir)
    if not recovery_dirs:
        return None, None

    temporary_root = Path(tempfile.mkdtemp(prefix=f".{skill_name}-upgrade-", dir=str(install_root)))
    preserved_local_dir = temporary_root / ".local"
    for source_dir in recovery_dirs:
        shutil.copytree(source_dir, preserved_local_dir, dirs_exist_ok=True)
    return temporary_root, preserved_local_dir


def deletion_path(target_dir: Path) -> str | Path:
    if os.name != "nt":
        return target_dir
    resolved = str(target_dir.resolve())
    if resolved.startswith("\\\\?\\"):
        return resolved
    if resolved.startswith("\\\\"):
        return "\\\\?\\UNC\\" + resolved[2:]
    return "\\\\?\\" + resolved


def remove_target_or_require_merge(target_dir: Path) -> bool:
    def retry_remove(function, path, _exc_info) -> None:
        try:
            os.chmod(path, stat.S_IWRITE)
            function(path)
        except OSError:
            pass

    for attempt in range(3):
        try:
            shutil.rmtree(deletion_path(target_dir), onexc=retry_remove)
        except OSError:
            pass
        if not target_dir.exists():
            return False
        if attempt < 2:
            time.sleep(0.2 * (attempt + 1))
    return True


def retire_legacy_skill(target_dir: Path, install_root: Path) -> Path | None:
    retired_dir = install_root / f".{CURRENT_SKILL_NAME}-legacy-{uuid.uuid4().hex}"
    try:
        target_dir.replace(retired_dir)
        return retired_dir
    except OSError:
        return None


def restrict_local_state(local_dir: Path) -> None:
    if not local_dir.is_dir():
        return
    for path in [local_dir, *local_dir.rglob("*")]:
        try:
            path.chmod(
                stat.S_IRUSR | stat.S_IWUSR | stat.S_IXUSR
                if path.is_dir()
                else stat.S_IRUSR | stat.S_IWUSR
            )
        except OSError:
            pass
    if os.name != "nt":
        return
    username = os.getenv("USERNAME")
    icacls = shutil.which("icacls")
    if not username or not icacls:
        return
    directories = [local_dir, *[path for path in local_dir.rglob("*") if path.is_dir()]]
    files = [path for path in local_dir.rglob("*") if path.is_file()]
    for path in directories:
        subprocess.run(
            [icacls, str(path), "/inheritance:r", "/grant:r", f"{username}:(OI)(CI)F"],
            capture_output=True,
            check=False,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
    for path in files:
        subprocess.run(
            [icacls, str(path), "/inheritance:r", "/grant:r", f"{username}:(F)"],
            capture_output=True,
            check=False,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )


def stop_installed_center(skill_dir: Path, target_dir: Path) -> None:
    cli_script = skill_dir / "scripts" / "ccrelay-cli.py"
    if not cli_script.is_file():
        return
    environment = os.environ.copy()
    environment["CCRELAY_SKILL_ROOT"] = str(target_dir)
    completed = subprocess.run(
        [sys.executable, str(cli_script), "center", "stop"],
        cwd=str(skill_dir),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        env=environment,
        check=False,
    )
    if completed.returncode != 0:
        raise RuntimeError(
            "Unable to stop the installed local CC center before upgrade: "
            + (completed.stderr.strip() or completed.stdout.strip() or f"exit code {completed.returncode}"))


def main() -> int:
    args = parse_args()
    skill_dir = Path(args.skill_dir).expanduser().resolve()
    skill_md = skill_dir / "SKILL.md"
    if not skill_md.is_file():
        print(f"[ERROR] SKILL.md not found in {skill_dir}", file=sys.stderr)
        return 1

    skill_name = parse_skill_name(skill_md)
    codex_home = resolve_codex_home(args.codex_home)
    install_root = codex_home / "skills"
    install_root.mkdir(parents=True, exist_ok=True)
    target_dir = install_root / skill_name
    legacy_target_dirs = [
        install_root / name
        for name in LEGACY_SKILL_NAMES
        if skill_name == CURRENT_SKILL_NAME and (install_root / name).exists()
    ]
    migration_source = legacy_target_dirs[0] if not target_dir.exists() and legacy_target_dirs else None
    preserved_local_dir: Path | None = None
    temporary_root: Path | None = None
    merge_install = False
    migrated_from: Path | None = None
    retired_legacy_dir: Path | None = None
    pending_legacy_roots = sorted(install_root.glob(f".{CURRENT_SKILL_NAME}-legacy-*"))
    orphaned_upgrade_roots = sorted(
        [
            path
            for name in (skill_name, *LEGACY_SKILL_NAMES)
            for path in install_root.glob(f".{name}-upgrade-*")
        ],
        key=lambda path: path.stat().st_mtime,
        reverse=True,
    )
    existing_dir = target_dir if target_dir.exists() else migration_source
    if existing_dir:
        if not args.force:
            print(f"[ERROR] Skill already installed: {existing_dir}", file=sys.stderr)
            return 2
        try:
            stop_installed_center(skill_dir, existing_dir)
        except RuntimeError as exc:
            print(f"[ERROR] {exc}", file=sys.stderr)
            return 3
        temporary_root, preserved_local_dir = preserve_local_state(
            install_root,
            skill_name,
            existing_dir / ".local",
            orphaned_upgrade_roots,
        )
        if existing_dir == target_dir:
            merge_install = remove_target_or_require_merge(target_dir)
        else:
            retired_legacy_dir = retire_legacy_skill(existing_dir, install_root)
            if retired_legacy_dir is None and remove_target_or_require_merge(existing_dir):
                print(f"[ERROR] Unable to remove legacy Skill directory: {existing_dir}", file=sys.stderr)
                return 4
            migrated_from = existing_dir
    else:
        temporary_root, preserved_local_dir = preserve_local_state(
            install_root,
            skill_name,
            None,
            orphaned_upgrade_roots,
        )

    copy_skill_tree(skill_dir, target_dir, merge=merge_install)
    if preserved_local_dir and preserved_local_dir.is_dir():
        shutil.copytree(preserved_local_dir, target_dir / ".local", dirs_exist_ok=True)
        restrict_local_state(target_dir / ".local")
    for legacy_dir in legacy_target_dirs:
        if legacy_dir.exists():
            try:
                stop_installed_center(skill_dir, legacy_dir)
            except RuntimeError as exc:
                print(f"[ERROR] {exc}", file=sys.stderr)
                return 3
            if remove_target_or_require_merge(legacy_dir):
                print(f"[ERROR] Unable to remove legacy Skill directory: {legacy_dir}", file=sys.stderr)
                return 4
    legacy_cleanup_roots = [*pending_legacy_roots]
    if retired_legacy_dir:
        legacy_cleanup_roots.append(retired_legacy_dir)
    for legacy_root in dict.fromkeys(legacy_cleanup_roots):
        if legacy_root.exists() and remove_target_or_require_merge(legacy_root):
            print(f"legacy skill cleanup deferred: {legacy_root}")
    if temporary_root and temporary_root.is_dir():
        shutil.rmtree(temporary_root)
    for orphaned_root in orphaned_upgrade_roots:
        if orphaned_root.is_dir():
            shutil.rmtree(orphaned_root, ignore_errors=True)
    print(target_dir)
    if migrated_from:
        print(f"migrated legacy skill: {migrated_from}")
    if merge_install:
        print("existing skill directory was in use; updated files in place")
    if args.install_cli_shim and not args.skip_cli_shim:
        shim_dir = install_ccrelay_cli_shim(codex_home, skill_name, target_dir)
        if shim_dir:
            print(f"optional ccrelay-cli shim: {shim_dir}")
    else:
        removed = remove_legacy_ccrelay_cli_shims(codex_home, skill_name)
        for path in removed:
            print(f"removed legacy ccrelay-cli shim: {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
