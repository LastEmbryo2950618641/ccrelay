#!/usr/bin/env sh

resolve_skill_python() {
  skill_dir=$1
  case "$(uname -s 2>/dev/null || printf unknown)" in
    MINGW*|MSYS*|CYGWIN*)
      python_bin="$skill_dir/assets/runtime-bundle/ccrelay/python-windows/python.exe"
      if [ ! -f "$python_bin" ]; then
        printf '%s\n' "Bundled Windows Python runtime is missing: $python_bin" >&2
        return 127
      fi
      printf '%s\n' "$python_bin"
      return 0
      ;;
  esac
  python_home="$skill_dir/.local/runtime/python-linux-3.13.15"
  python_bin="$python_home/bin/python3"
  python_archive="$skill_dir/assets/runtime-bundle/ccrelay/python-linux.tar.gz"

  if [ -x "$python_bin" ]; then
    printf '%s\n' "$python_bin"
    return 0
  fi
  if [ ! -f "$python_archive" ]; then
    printf '%s\n' "Bundled Linux Python archive is missing: $python_archive" >&2
    return 127
  fi
  if ! command -v tar >/dev/null 2>&1; then
    printf '%s\n' "tar is required to unpack the bundled Linux Python runtime." >&2
    return 127
  fi

  python_parent=$(dirname -- "$python_home")
  python_tmp="$python_home.tmp.$$"
  mkdir -p "$python_parent"
  python_lock="$python_parent/.python-linux-3.13.15.lock"
  lock_wait=0
  while ! mkdir "$python_lock" 2>/dev/null; do
    if [ -x "$python_bin" ]; then
      printf '%s\n' "$python_bin"
      return 0
    fi
    lock_wait=$((lock_wait + 1))
    if [ "$lock_wait" -ge 30 ]; then
      printf '%s\n' "Timed out waiting for bundled Linux Python extraction." >&2
      return 126
    fi
    sleep 1
  done
  if [ -x "$python_bin" ]; then
    rmdir "$python_lock"
    printf '%s\n' "$python_bin"
    return 0
  fi
  rm -rf "$python_home"
  rm -rf "$python_tmp"
  mkdir -p "$python_tmp"
  if ! tar -xzf "$python_archive" -C "$python_tmp"; then
    rm -rf "$python_tmp"
    rmdir "$python_lock"
    printf '%s\n' "Failed to unpack bundled Linux Python runtime: $python_archive" >&2
    return 126
  fi
  if [ ! -x "$python_tmp/python/bin/python3" ]; then
    rm -rf "$python_tmp"
    rmdir "$python_lock"
    printf '%s\n' "Bundled Linux Python runtime is incomplete." >&2
    return 126
  fi
  mv "$python_tmp/python" "$python_home"
  rm -rf "$python_tmp"
  rmdir "$python_lock"
  if [ ! -x "$python_bin" ]; then
    printf '%s\n' "Bundled Linux Python runtime is unavailable after extraction." >&2
    return 126
  fi
  printf '%s\n' "$python_bin"
}
