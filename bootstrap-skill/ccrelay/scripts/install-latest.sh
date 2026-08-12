#!/usr/bin/env sh
set -eu

RELEASE_BASE_URL=${CCRELAY_RELEASE_BASE_URL:-}
GITEE_API_BASE_URL=${CCRELAY_GITEE_API_BASE_URL:-https://gitee.com/api/v5/repos/nekoneko-acg/ccrelay}
GITHUB_RELEASE_BASE_URL=${CCRELAY_GITHUB_RELEASE_BASE_URL:-https://github.com/LastEmbryo2950618641/ccrelay/releases/latest/download}
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SKILL_ROOT=$(dirname "$SCRIPT_DIR")
INSTALL_ROOT=$(dirname "$SKILL_ROOT")
WORK_ROOT=$(mktemp -d "$INSTALL_ROOT/.ccrelay-download.XXXXXX")
BACKUP_ROOT="$INSTALL_ROOT/.ccrelay-bootstrap.$$"
ARCHIVE_PATH="$WORK_ROOT/ccrelay-full.zip"
CHECKSUM_PATH="$WORK_ROOT/ccrelay-full.zip.sha256"
EXTRACT_ROOT="$WORK_ROOT/extracted"
GITEE_RELEASE_METADATA="$WORK_ROOT/gitee-release.json"
GITEE_ATTACHMENT_METADATA="$WORK_ROOT/gitee-attachments.json"

cleanup() {
  rm -rf "$WORK_ROOT"
}
trap cleanup EXIT INT TERM

command -v curl >/dev/null 2>&1 || { printf '%s\n' 'curl is required to download CC Relay' >&2; exit 1; }
command -v unzip >/dev/null 2>&1 || { printf '%s\n' 'unzip is required to install CC Relay' >&2; exit 1; }

mkdir -p "$EXTRACT_ROOT"
download_file() {
  url=$1
  destination=$2
  attempt=1
  while [ "$attempt" -le 3 ]; do
    rm -f "$destination"
    if curl -fsSL --connect-timeout 20 "$url" -o "$destination"; then
      return 0
    fi
    [ "$attempt" -eq 3 ] || sleep $((attempt * 2))
    attempt=$((attempt + 1))
  done
  printf '%s\n' "Download failed: $url" >&2
  return 1
}

package_sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$ARCHIVE_PATH" | awk '{ print tolower($1) }'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$ARCHIVE_PATH" | awk '{ print tolower($1) }'
  else
    printf '%s\n' 'sha256sum or shasum is required to verify CC Relay' >&2
    return 1
  fi
}

download_package() {
  base_url=${1%/}
  if download_file "$base_url/ccrelay-full.zip" "$ARCHIVE_PATH" &&
     download_file "$base_url/ccrelay-full.zip.sha256" "$CHECKSUM_PATH"; then
    expected=$(awk 'NR == 1 { print tolower($1) }' "$CHECKSUM_PATH")
    actual=$(package_sha256) || return 1
    if [ -n "$expected" ] && [ "$expected" = "$actual" ]; then
      printf '%s\n' "CC Relay package source: $base_url"
      return 0
    fi
    printf '%s\n' "SHA-256 verification failed for source: $base_url" >&2
  fi
  rm -f "$ARCHIVE_PATH" "$CHECKSUM_PATH"
  return 1
}

json_object_id_by_name() {
  name=$1
  tr '{' '\n' < "$GITEE_ATTACHMENT_METADATA" |
    awk -v target="\"name\":\"$name\"" '
      index($0, target) {
        if (match($0, /"id"[[:space:]]*:[[:space:]]*[0-9]+/)) {
          value = substr($0, RSTART, RLENGTH)
          sub(/^.*:/, "", value)
          gsub(/[[:space:]]/, "", value)
          print value
          exit
        }
      }
    '
}

download_gitee_package() {
  api_base=${GITEE_API_BASE_URL%/}
  if ! download_file "$api_base/releases/latest" "$GITEE_RELEASE_METADATA"; then
    return 1
  fi
  release_id=$(tr ',' '\n' < "$GITEE_RELEASE_METADATA" |
    awk 'match($0, /"id"[[:space:]]*:[[:space:]]*[0-9]+/) { value=substr($0,RSTART,RLENGTH); sub(/^.*:/,"",value); gsub(/[[:space:]]/,"",value); print value; exit }')
  [ -n "$release_id" ] || return 1
  download_file "$api_base/releases/$release_id/attach_files" "$GITEE_ATTACHMENT_METADATA" || return 1
  archive_id=$(json_object_id_by_name 'ccrelay-full.zip')
  checksum_id=$(json_object_id_by_name 'ccrelay-full.zip.sha256')
  [ -n "$archive_id" ] && [ -n "$checksum_id" ] || return 1
  download_file "$api_base/releases/$release_id/attach_files/$archive_id/download" "$ARCHIVE_PATH" || return 1
  download_file "$api_base/releases/$release_id/attach_files/$checksum_id/download" "$CHECKSUM_PATH" || return 1
  expected=$(awk 'NR == 1 { print tolower($1) }' "$CHECKSUM_PATH")
  actual=$(package_sha256) || return 1
  if [ -n "$expected" ] && [ "$expected" = "$actual" ]; then
    printf '%s\n' "CC Relay package source: $api_base/releases/latest"
    return 0
  fi
  printf '%s\n' 'SHA-256 verification failed for Gitee source' >&2
  rm -f "$ARCHIVE_PATH" "$CHECKSUM_PATH"
  return 1
}

if [ -n "$RELEASE_BASE_URL" ]; then
  download_package "$RELEASE_BASE_URL" || {
    printf '%s\n' 'Unable to download and verify the CC Relay complete package' >&2
    exit 1
  }
elif ! download_gitee_package && ! download_package "$GITHUB_RELEASE_BASE_URL"; then
  printf '%s\n' 'Unable to download and verify the CC Relay complete package from Gitee or GitHub' >&2
  exit 1
fi

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
