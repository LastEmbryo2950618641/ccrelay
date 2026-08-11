#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SKILL_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
SCRIPT_PATH="$SKILL_DIR/scripts/ccrelay-cli.py"
. "$SCRIPT_DIR/python-runtime.sh"
PYTHON=$(resolve_skill_python "$SKILL_DIR") || exit $?
case "$(uname -s 2>/dev/null || printf unknown)" in
  MINGW*|MSYS*|CYGWIN*)
    SCRIPT_PATH=$(cygpath -w "$SCRIPT_PATH")
    export MSYS2_ARG_CONV_EXCL='*'
    ;;
esac
exec "$PYTHON" "$SCRIPT_PATH" "$@"
