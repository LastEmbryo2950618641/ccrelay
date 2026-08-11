#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SKILL_DIR="$SCRIPT_DIR/../codex-skill/ccrelay"
SCRIPT_PATH="$SKILL_DIR/scripts/ccrelay-cli.py"
. "$SKILL_DIR/scripts/python-runtime.sh"
PYTHON=$(resolve_skill_python "$SKILL_DIR") || exit $?
exec "$PYTHON" "$SCRIPT_PATH" "$@"
