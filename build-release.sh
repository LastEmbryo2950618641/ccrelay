#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
OUT_ROOT="${1:-$SCRIPT_DIR/dist/release}"

"$SCRIPT_DIR/build.sh" "$OUT_ROOT"
