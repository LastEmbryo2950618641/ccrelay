#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SKILL_NAME="ccrelay"
SRC_DIR="$SCRIPT_DIR/codex-skill/$SKILL_NAME"
OUT_ROOT="${1:-$SCRIPT_DIR/dist/skill}"
RELEASE_ROOT="${2:-$SCRIPT_DIR/dist/release}"
PACKAGE_DIR="$OUT_ROOT/$SKILL_NAME"
TMP_DIR="$OUT_ROOT/.tmp-$SKILL_NAME"
RUNTIME_SOURCE="$SCRIPT_DIR/build/runtime-bundle/$SKILL_NAME"
RUNTIME_TARGET="$TMP_DIR/assets/runtime-bundle/$SKILL_NAME"

fail() {
  printf '%s\n' "[ERROR] $1" >&2
  exit 1
}

log() {
  printf '%s\n' "[INFO] $1"
}

[ -d "$SRC_DIR" ] || fail "Skill source directory not found: $SRC_DIR"
[ -f "$SRC_DIR/SKILL.md" ] || fail "SKILL.md not found: $SRC_DIR/SKILL.md"

SKILL_NAME_FROM_MD=$(grep '^name:' "$SRC_DIR/SKILL.md" | head -n 1 | cut -d ':' -f 2- | sed 's/^ *//;s/ *$//')
[ -n "$SKILL_NAME_FROM_MD" ] || fail "Failed to parse skill name from $SRC_DIR/SKILL.md"
[ "$SKILL_NAME_FROM_MD" = "$SKILL_NAME" ] || fail "Skill name mismatch: expected $SKILL_NAME but found $SKILL_NAME_FROM_MD"

mkdir -p "$OUT_ROOT"
rm -rf "$TMP_DIR" "$PACKAGE_DIR"
mkdir -p "$TMP_DIR"

cp -R "$SRC_DIR"/. "$TMP_DIR"/
rm -rf "$TMP_DIR/.local" "$TMP_DIR/assets/runtime-bundle"

[ -f "$TMP_DIR/SKILL.md" ] || fail "Packaged SKILL.md missing"

[ -d "$RUNTIME_SOURCE" ] || fail "Runtime bundle not found: $RUNTIME_SOURCE. Run powershell -ExecutionPolicy Bypass -File scripts/build_runtime_bundle.ps1 first, or use build.ps1."
mkdir -p "$(dirname "$RUNTIME_TARGET")"
cp -R "$RUNTIME_SOURCE" "$RUNTIME_TARGET"
[ -f "$RUNTIME_TARGET/app.jar" ] || fail "Runtime app.jar missing: $RUNTIME_TARGET"
[ -f "$RUNTIME_TARGET/bin/start.sh" ] || fail "Runtime start.sh missing: $RUNTIME_TARGET"
[ -f "$RUNTIME_TARGET/runtime.tar.gz" ] || fail "Bundled Linux Java archive missing: $RUNTIME_TARGET"
[ -f "$RUNTIME_TARGET/runtime-windows.zip" ] || fail "Bundled Windows Java archive missing: $RUNTIME_TARGET"
[ ! -d "$RUNTIME_TARGET/runtime" ] || fail "Runtime bundle contains duplicate extracted Linux JRE: $RUNTIME_TARGET"
[ ! -d "$RUNTIME_TARGET/runtime-windows" ] || fail "Runtime bundle contains duplicate extracted Windows JRE: $RUNTIME_TARGET"
[ -f "$RUNTIME_TARGET/python-windows/python.exe" ] || fail "Bundled Windows Python missing: $RUNTIME_TARGET"
[ -f "$RUNTIME_TARGET/python-linux.tar.gz" ] || fail "Bundled Linux Python missing: $RUNTIME_TARGET"

mv "$TMP_DIR" "$PACKAGE_DIR"

PYTHON_COMMAND=$(command -v python3 || command -v python || true)
[ -n "$PYTHON_COMMAND" ] || fail "Python 3 is required for release packaging"
"$PYTHON_COMMAND" "$SCRIPT_DIR/scripts/package_skill_release.py" \
  --full-skill-dir "$PACKAGE_DIR" \
  --bootstrap-skill-dir "$SCRIPT_DIR/bootstrap-skill/ccrelay" \
  --release-dir "$RELEASE_ROOT" \
  --build-file "$SCRIPT_DIR/build.gradle"

log "Standard runnable skill directory created"
log "Output: $PACKAGE_DIR"
log "Runtime bundle: $PACKAGE_DIR/assets/runtime-bundle/$SKILL_NAME"
log "Complete ZIP: $RELEASE_ROOT/ccrelay-full.zip"
log "Bootstrap ZIP: $RELEASE_ROOT/ccrelay-bootstrap.zip"
log "Install command: powershell -ExecutionPolicy Bypass -File scripts/install_codex_skill.ps1 $PACKAGE_DIR --force"
