#!/usr/bin/env python3
"""Standard Skill packaging and CC Center catalog commands."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import struct
import tempfile
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path
from typing import Any, Dict, Iterable, List, Tuple


MAX_PACKAGE_BYTES = 50 * 1024 * 1024
MAX_FILE_BYTES = 20 * 1024 * 1024
MAX_EXTRACTED_BYTES = 100 * 1024 * 1024
MAX_FILES = 2_000
SKILL_ID_PATTERN = re.compile(r"[a-z0-9][a-z0-9-]{0,63}")
EXCLUDED_NAMES = {".git", ".local", "__pycache__", ".DS_Store"}


class SkillError(Exception):
    pass


def add_skill(subparsers: argparse._SubParsersAction) -> None:
    skill = subparsers.add_parser("skill", help="管理 CC Center 标准 Skill 目录")
    commands = skill.add_subparsers(dest="skill_command", required=True)

    install = commands.add_parser("install", help="打包并发布标准 Skill")
    install.add_argument("path", help="包含 SKILL.md 的目录或 ZIP 压缩包")
    install.set_defaults(func=install_skill)

    list_parser = commands.add_parser("list", help="列出 Center Skill 目录")
    list_parser.set_defaults(func=list_skills)

    remove = commands.add_parser("remove", help="将 Center Skill 置为 INVALID")
    remove.add_argument("skill_id")
    remove.set_defaults(func=remove_skill)


def install_skill(args: argparse.Namespace) -> Dict[str, Any]:
    source = Path(args.path).expanduser().resolve()
    if not source.exists():
        raise SkillError(f"Skill path does not exist: {source}")
    with tempfile.TemporaryDirectory(prefix="ccrelay-skill-") as temporary:
        temporary_root = Path(temporary)
        skill_root = source
        if source.is_file():
            if source.suffix.lower() != ".zip":
                raise SkillError("Skill file must be a ZIP archive")
            skill_root = extract_zip(source, temporary_root / "source")
        validate_skill_root(skill_root)
        skill_id = read_skill_id(skill_root)
        sha256 = canonical_sha256(skill_root)
        archive = temporary_root / f"{skill_id}.zip"
        create_deterministic_zip(skill_root, archive)
        response = request(
            args,
            "POST",
            "/api/skill/catalog/install",
            data=archive.read_bytes(),
            content_type="application/zip",
        )
        if isinstance(response, dict):
            response["localSha256"] = sha256
            response["sourcePath"] = str(source)
        return response


def list_skills(args: argparse.Namespace) -> Dict[str, Any]:
    return request(args, "GET", "/api/skill/catalog")


def remove_skill(args: argparse.Namespace) -> Dict[str, Any]:
    skill_id = str(args.skill_id).strip()
    if not SKILL_ID_PATTERN.fullmatch(skill_id):
        raise SkillError(f"Invalid Skill name: {skill_id}")
    return request(args, "DELETE", f"/api/skill/catalog/{urllib.parse.quote(skill_id, safe='')}")


def canonical_sha256(skill_root: Path) -> str:
    digest = hashlib.sha256()
    for relative, path in skill_files(skill_root):
        relative_bytes = relative.encode("utf-8")
        size = path.stat().st_size
        digest.update(struct.pack(">I", len(relative_bytes)))
        digest.update(relative_bytes)
        digest.update(struct.pack(">Q", size))
        with path.open("rb") as stream:
            while True:
                block = stream.read(8192)
                if not block:
                    break
                digest.update(block)
    return digest.hexdigest()


def create_deterministic_zip(skill_root: Path, archive: Path) -> None:
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as output:
        for relative, path in skill_files(skill_root):
            info = zipfile.ZipInfo(relative, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            with path.open("rb") as stream:
                output.writestr(info, stream.read())
    if archive.stat().st_size > MAX_PACKAGE_BYTES:
        raise SkillError(f"Skill package exceeds {MAX_PACKAGE_BYTES} bytes")


def extract_zip(archive: Path, destination: Path) -> Path:
    if archive.stat().st_size > MAX_PACKAGE_BYTES:
        raise SkillError(f"Skill package exceeds {MAX_PACKAGE_BYTES} bytes")
    destination.mkdir(parents=True, exist_ok=True)
    destination_resolved = destination.resolve()
    total_bytes = 0
    file_count = 0
    names: set[str] = set()
    with zipfile.ZipFile(archive) as package:
        for info in package.infolist():
            name = info.filename.replace("\\", "/")
            if not name or name.startswith("/") or "\x00" in name or any(
                part in {".", ".."} for part in name.split("/")
            ):
                raise SkillError(f"Invalid Skill archive entry: {name}")
            mode = (info.external_attr >> 16) & 0o170000
            if mode == 0o120000:
                raise SkillError(f"Symbolic links are not allowed in Skill packages: {name}")
            target = (destination / name).resolve()
            if not is_relative_to(target, destination_resolved):
                raise SkillError(f"Skill archive entry escapes destination: {name}")
            normalized_name = target.relative_to(destination_resolved).as_posix()
            if normalized_name in names:
                raise SkillError(f"Duplicate Skill archive entry: {name}")
            names.add(normalized_name)
            if info.is_dir():
                target.mkdir(parents=True, exist_ok=True)
                continue
            file_count += 1
            total_bytes += info.file_size
            if file_count > MAX_FILES or info.file_size > MAX_FILE_BYTES or total_bytes > MAX_EXTRACTED_BYTES:
                raise SkillError("Skill package exceeds extraction limits")
            target.parent.mkdir(parents=True, exist_ok=True)
            with package.open(info) as source, target.open("wb") as output:
                shutil.copyfileobj(source, output, length=8192)
    if (destination / "SKILL.md").is_file():
        return destination
    child_roots = [path for path in destination.iterdir() if path.is_dir() and (path / "SKILL.md").is_file()]
    if len(child_roots) == 1:
        return child_roots[0]
    raise SkillError("Standard Skill package must contain SKILL.md at its root")


def validate_skill_root(skill_root: Path) -> None:
    if not skill_root.is_dir() or not (skill_root / "SKILL.md").is_file():
        raise SkillError("Standard Skill directory must contain SKILL.md")
    files = skill_files(skill_root)
    if len(files) > MAX_FILES:
        raise SkillError("Skill contains too many files")
    total_bytes = 0
    for relative, path in files:
        size = path.stat().st_size
        if size > MAX_FILE_BYTES:
            raise SkillError(f"Skill file exceeds {MAX_FILE_BYTES} bytes: {relative}")
        total_bytes += size
    if total_bytes > MAX_EXTRACTED_BYTES:
        raise SkillError("Skill files exceed the allowed total size")


def read_skill_id(skill_root: Path) -> str:
    text = (skill_root / "SKILL.md").read_text(encoding="utf-8")
    if not text.startswith("---"):
        raise SkillError("SKILL.md must start with YAML frontmatter")
    end = text.find("\n---", 3)
    if end < 0:
        raise SkillError("SKILL.md frontmatter is not closed")
    match = re.search(r"(?m)^name:\s*['\"]?([^'\"\r\n]+)['\"]?\s*$", text[3:end])
    skill_id = match.group(1).strip() if match else ""
    if not SKILL_ID_PATTERN.fullmatch(skill_id):
        raise SkillError(f"Invalid Skill name: {skill_id}")
    return skill_id


def skill_files(skill_root: Path) -> List[Tuple[str, Path]]:
    result: List[Tuple[str, Path]] = []
    for path in skill_root.rglob("*"):
        relative_parts = path.relative_to(skill_root).parts
        if any(part in EXCLUDED_NAMES for part in relative_parts):
            continue
        if path.is_symlink():
            raise SkillError(f"Symbolic links are not allowed in Skill packages: {path}")
        if path.is_file():
            result.append((path.relative_to(skill_root).as_posix(), path))
    result.sort(key=lambda item: item[0])
    return result


def request(
    args: argparse.Namespace,
    method: str,
    path: str,
    data: bytes | None = None,
    content_type: str | None = None,
) -> Any:
    url = args.center.rstrip("/") + (path if path.startswith("/") else "/" + path)
    headers = {"Accept": "application/json"}
    if content_type:
        headers["Content-Type"] = content_type
    request_value = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(request_value, timeout=args.timeout) as response:
        text = response.read().decode("utf-8", errors="replace")
        if not text:
            return None
        return json.loads(text)


def is_relative_to(path: Path, parent: Path) -> bool:
    try:
        path.relative_to(parent)
        return True
    except ValueError:
        return False
