#!/usr/bin/env sh
set -eu

RELEASE_BASE_URL=${CCRELAY_RELEASE_BASE_URL:-https://github.com/LastEmbryo2950618641/ccrelay/releases/latest/download}
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SKILL_ROOT=$(dirname "$SCRIPT_DIR")
INSTALL_ROOT=$(dirname "$SKILL_ROOT")
WORK_ROOT=$(mktemp -d "$INSTALL_ROOT/.ccrelay-download.XXXXXX")
BACKUP_ROOT="$INSTALL_ROOT/.ccrelay-bootstrap.$$"
ARCHIVE_PATH="$WORK_ROOT/ccrelay-full.zip"
CHECKSUM_PATH="$WORK_ROOT/ccrelay-full.zip.sha256"
EXTRACT_ROOT="$WORK_ROOT/extracted"

cleanup() {
  rm -rf "$WORK_ROOT"
}
trap cleanup EXIT INT TERM

command -v curl >/dev/null 2>&1 || { printf '%s\n' 'curl is required to download CC Relay' >&2; exit 1; }
command -v unzip >/dev/null 2>&1 || { printf '%s\n' 'unzip is required to install CC Relay' >&2; exit 1; }

mkdir -p "$EXTRACT_ROOT"
curl -fsSL "${RELEASE_BASE_URL%/}/ccrelay-full.zip" -o "$ARCHIVE_PATH"
curl -fsSL "${RELEASE_BASE_URL%/}/ccrelay-full.zip.sha256" -o "$CHECKSUM_PATH"
EXPECTED=$(awk 'NR == 1 { print tolower($1) }' "$CHECKSUM_PATH")
if command -v sha256sum >/dev/null 2>&1; then
  ACTUAL=$(sha256sum "$ARCHIVE_PATH" | awk '{ print tolower($1) }')
elif command -v shasum >/dev/null 2>&1; then
  ACTUAL=$(shasum -a 256 "$ARCHIVE_PATH" | awk '{ print tolower($1) }')
else
  printf '%s\n' 'sha256sum or shasum is required to verify CC Relay' >&2
  exit 1
fi
[ -n "$EXPECTED" ] && [ "$EXPECTED" = "$ACTUAL" ] || { printf '%s\n' 'SHA-256 verification failed for ccrelay-full.zip' >&2; exit 1; }

unzip -q "$ARCHIVE_PATH" -d "$EXTRACT_ROOT"
COMPLETE_SKILL="$EXTRACT_ROOT/ccrelay"
[ -f "$COMPLETE_SKILL/SKILL.md" ] && [ -f "$COMPLETE_SKILL/assets/runtime-bundle/ccrelay/app.jar" ] || {
  printf '%s\n' 'Downloaded archive is not a complete CC Relay Skill package' >&2
  exit 1
}

if [ -d "$SKILL_ROOT/.local" ]; then
  mkdir -p "$COMPLETE_SKILL/.local"
  cp -R "$SKILL_ROOT/.local"/. "$COMPLETE_SKILL/.local"/
fi

cd "$INSTALL_ROOT"
mv "$SKILL_ROOT" "$BACKUP_ROOT"
if ! mv "$COMPLETE_SKILL" "$SKILL_ROOT"; then
  mv "$BACKUP_ROOT" "$SKILL_ROOT"
  exit 1
fi
rm -rf "$BACKUP_ROOT"
printf '%s\n' "CC Relay complete Skill installed: $SKILL_ROOT"
