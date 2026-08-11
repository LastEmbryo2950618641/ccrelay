#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SKILL_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
SCRIPT_PATH="$SKILL_DIR/scripts/open-observer.py"
. "$SCRIPT_DIR/python-runtime.sh"
PYTHON=$(resolve_skill_python "$SKILL_DIR") || exit $?
exec "$PYTHON" "$SCRIPT_PATH" "$@"
