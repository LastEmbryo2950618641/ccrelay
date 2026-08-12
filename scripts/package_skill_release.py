from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import stat
import zipfile
from pathlib import Path


FULL_ARCHIVE = "ccrelay-full.zip"
BOOTSTRAP_ARCHIVE = "ccrelay-bootstrap.zip"
STORED_SUFFIXES = {".gz", ".jar", ".tgz", ".zip"}
FIXED_TIMESTAMP = (2026, 1, 1, 0, 0, 0)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Create the complete and bootstrap CC Relay Skill ZIP files.")
    parser.add_argument("--full-skill-dir", required=True)
    parser.add_argument("--bootstrap-skill-dir", required=True)
    parser.add_argument("--release-dir", required=True)
    parser.add_argument("--build-file", required=True)
    return parser.parse_args()


def read_version(build_file: Path) -> str:
    match = re.search(r"^version\s*=\s*['\"]([^'\"]+)['\"]", build_file.read_text(encoding="utf-8"), re.MULTILINE)
    if not match:
        raise ValueError(f"Unable to read version from {build_file}")
    return match.group(1)


def archive_tree(source: Path, archive: Path, root_name: str) -> None:
    if not (source / "SKILL.md").is_file():
        raise ValueError(f"SKILL.md not found in {source}")
    archive.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive, "w") as package:
        for path in sorted((item for item in source.rglob("*") if item.is_file()), key=lambda item: item.as_posix()):
            relative = path.relative_to(source)
            if ".local" in relative.parts or "__pycache__" in relative.parts or path.suffix == ".pyc":
                continue
            archive_name = (Path(root_name) / relative).as_posix()
            info = zipfile.ZipInfo(archive_name, FIXED_TIMESTAMP)
            executable = path.suffix == ".sh" or path.name in {"ccrelay-cli", "claude"}
            mode = stat.S_IFREG | (0o755 if executable else 0o644)
            info.external_attr = mode << 16
            info.compress_type = zipfile.ZIP_STORED if path.suffix.lower() in STORED_SUFFIXES else zipfile.ZIP_DEFLATED
            with path.open("rb") as source_file, package.open(info, "w", force_zip64=True) as archive_file:
                shutil.copyfileobj(source_file, archive_file, length=1024 * 1024)


def write_checksum(path: Path) -> Path:
    hasher = hashlib.sha256()
    with path.open("rb") as archive:
        for chunk in iter(lambda: archive.read(1024 * 1024), b""):
            hasher.update(chunk)
    digest = hasher.hexdigest()
    checksum_path = path.with_name(path.name + ".sha256")
    checksum_path.write_text(f"{digest}  {path.name}\n", encoding="ascii")
    return checksum_path


def package_release(full_skill_dir: Path, bootstrap_skill_dir: Path, release_dir: Path, version: str) -> dict[str, object]:
    release_dir.mkdir(parents=True, exist_ok=True)
    full_archive = release_dir / FULL_ARCHIVE
    bootstrap_archive = release_dir / BOOTSTRAP_ARCHIVE
    for path in (full_archive, bootstrap_archive, full_archive.with_name(full_archive.name + ".sha256"),
                 bootstrap_archive.with_name(bootstrap_archive.name + ".sha256")):
        path.unlink(missing_ok=True)

    archive_tree(full_skill_dir, full_archive, "ccrelay")
    archive_tree(bootstrap_skill_dir, bootstrap_archive, "ccrelay")
    full_checksum = write_checksum(full_archive)
    bootstrap_checksum = write_checksum(bootstrap_archive)
    manifest = {
        "name": "ccrelay",
        "version": version,
        "fullArchive": full_archive.name,
        "fullChecksum": full_checksum.name,
        "bootstrapArchive": bootstrap_archive.name,
        "bootstrapChecksum": bootstrap_checksum.name,
    }
    manifest_path = release_dir / "release-manifest.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return {**manifest, "releaseDirectory": str(release_dir.resolve())}


def main() -> int:
    args = parse_args()
    result = package_release(
        Path(args.full_skill_dir).resolve(),
        Path(args.bootstrap_skill_dir).resolve(),
        Path(args.release_dir).resolve(),
        read_version(Path(args.build_file).resolve()),
    )
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
