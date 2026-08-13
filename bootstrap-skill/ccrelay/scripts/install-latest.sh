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
VOLUME_ROOT="$WORK_ROOT/gitee-volumes"
RECOVERY_ROOT="$WORK_ROOT/gitee-recovered"
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

clear_downloaded_package() {
  rm -f "$ARCHIVE_PATH" "$CHECKSUM_PATH"
  rm -rf "$VOLUME_ROOT" "$RECOVERY_ROOT"
}

verify_package_checksum() {
  expected=$(awk 'NR == 1 { print tolower($1) }' "$CHECKSUM_PATH")
  actual=$(package_sha256) || return 1
  [ -n "$expected" ] && [ "$expected" = "$actual" ] || {
    printf '%s\n' 'SHA-256 verification failed' >&2
    return 1
  }
}

seven_zip_command() {
  os=$(uname -s)
  arch=$(uname -m)
  bundled=
  case "$os:$arch" in
    Linux:x86_64|Linux:amd64) bundled="$SKILL_ROOT/assets/7zip/linux-x64/7zz" ;;
    Darwin:x86_64|Darwin:amd64) bundled="$SKILL_ROOT/assets/7zip/macos-x64/7zz" ;;
    Darwin:arm64|Darwin:aarch64) bundled="$SKILL_ROOT/assets/7zip/macos-arm64/7zz" ;;
  esac
  if [ -n "$bundled" ] && [ -f "$bundled" ]; then
    chmod +x "$bundled" 2>/dev/null || true
    [ -x "$bundled" ] && { printf '%s\n' "$bundled"; return 0; }
  fi
  if command -v 7z >/dev/null 2>&1; then
    command -v 7z
    return 0
  fi
  if command -v 7zz >/dev/null 2>&1; then
    command -v 7zz
    return 0
  fi
  printf '%s\n' 'No compatible bundled or system 7-Zip command is available' >&2
  return 1
}

gitee_volume_rows() {
  tr '{' '\n' < "$GITEE_ATTACHMENT_METADATA" |
    awk '
      match($0, /"name"[[:space:]]*:[[:space:]]*"ccrelay-full\.zip\.[0-9][0-9][0-9]"/) {
        name_field = substr($0, RSTART, RLENGTH)
        if (match(name_field, /ccrelay-full\.zip\.[0-9][0-9][0-9]/)) {
          name = substr(name_field, RSTART, RLENGTH)
        }
        if (name != "" && match($0, /"id"[[:space:]]*:[[:space:]]*[0-9]+/)) {
          id_field = substr($0, RSTART, RLENGTH)
          sub(/^.*:/, "", id_field)
          gsub(/[[:space:]]/, "", id_field)
          number = name
          sub(/^.*\./, "", number)
          print number "\t" id_field "\t" name
        }
      }
    ' | sort -n -k1,1
}

validate_gitee_volumes() {
  rows=$1
  [ -n "$rows" ] || {
    printf '%s\n' 'Gitee latest Release does not contain ccrelay-full.zip.001 split assets' >&2
    return 1
  }
  expected=1
  printf '%s\n' "$rows" | while IFS="$(printf '\t')" read -r number _id _name; do
    [ "$number" -eq "$expected" ] || {
      printf 'Gitee split package is incomplete: expected .%03d, found .%03d\n' "$expected" "$number" >&2
      exit 1
    }
    expected=$((expected + 1))
  done
}

expand_gitee_split_archive() {
  first_volume=$1
  seven_zip=$(seven_zip_command) || return 1
  rm -rf "$RECOVERY_ROOT"
  mkdir -p "$RECOVERY_ROOT"
  "$seven_zip" x -tSplit -y "-o$RECOVERY_ROOT" "$first_volume" >/dev/null || return 1
  restored="$RECOVERY_ROOT/ccrelay-full.zip"
  [ -f "$restored" ] || {
    printf '%s\n' 'Gitee split package did not restore the outer ZIP' >&2
    return 1
  }
  printf '%s\n' "$restored"
}

expand_gitee_outer_archive() {
  outer_archive=$1
  validate_gitee_outer_archive "$outer_archive" || return 1
  inner_root="$RECOVERY_ROOT/inner"
  mkdir -p "$inner_root"
  unzip -q "$outer_archive" -d "$inner_root" || return 1
  inner_archive="$inner_root/ccrelay-full.zip"
  [ -f "$inner_archive" ] || {
    printf '%s\n' 'Gitee outer ZIP does not contain ccrelay-full.zip' >&2
    return 1
  }
  mv "$inner_archive" "$ARCHIVE_PATH"
}

validate_gitee_outer_archive() {
  outer_archive=$1
  entries=$(unzip -Z1 "$outer_archive") || return 1
  [ "$entries" = 'ccrelay-full.zip' ] || {
    printf '%s\n' 'Gitee outer ZIP must contain only ccrelay-full.zip at its root' >&2
    return 1
  }
}

download_package() {
  base_url=${1%/}
  if download_file "$base_url/ccrelay-full.zip" "$ARCHIVE_PATH" &&
     download_file "$base_url/ccrelay-full.zip.sha256" "$CHECKSUM_PATH"; then
    if verify_package_checksum; then
      printf '%s\n' "CC Relay package source: $base_url"
      return 0
    fi
    printf '%s\n' "SHA-256 verification failed for source: $base_url" >&2
  fi
  clear_downloaded_package
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
    clear_downloaded_package
    return 1
  fi
  release_id=$(tr ',' '\n' < "$GITEE_RELEASE_METADATA" |
    awk 'match($0, /"id"[[:space:]]*:[[:space:]]*[0-9]+/) { value=substr($0,RSTART,RLENGTH); sub(/^.*:/,"",value); gsub(/[[:space:]]/,"",value); print value; exit }')
  [ -n "$release_id" ] || { clear_downloaded_package; return 1; }
  download_file "$api_base/releases/$release_id/attach_files" "$GITEE_ATTACHMENT_METADATA" || { clear_downloaded_package; return 1; }
  checksum_id=$(json_object_id_by_name 'ccrelay-full.zip.sha256')
  [ -n "$checksum_id" ] || { clear_downloaded_package; return 1; }
  volume_rows=$(gitee_volume_rows)
  validate_gitee_volumes "$volume_rows" || { clear_downloaded_package; return 1; }
  mkdir -p "$VOLUME_ROOT"
  printf '%s\n' "$volume_rows" | while IFS="$(printf '\t')" read -r _number volume_id volume_name; do
    download_file "$api_base/releases/$release_id/attach_files/$volume_id/download" "$VOLUME_ROOT/$volume_name" || exit 1
  done || { clear_downloaded_package; return 1; }
  download_file "$api_base/releases/$release_id/attach_files/$checksum_id/download" "$CHECKSUM_PATH" || { clear_downloaded_package; return 1; }
  outer_archive=$(expand_gitee_split_archive "$VOLUME_ROOT/ccrelay-full.zip.001") || { clear_downloaded_package; return 1; }
  expand_gitee_outer_archive "$outer_archive" || { clear_downloaded_package; return 1; }
  if verify_package_checksum; then
    printf '%s\n' "CC Relay package source: $api_base/releases/latest"
    return 0
  fi
  printf '%s\n' 'SHA-256 verification failed for Gitee source' >&2
  clear_downloaded_package
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
