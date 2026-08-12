#!/usr/bin/env sh
set -eu

bundle_dir="${1:?bundle directory is required}"
shift || true

PATH="$bundle_dir/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin${PATH:+:$PATH}"
export PATH

java_bin="$bundle_dir/runtime/bin/java"
app_jar="$bundle_dir/app.jar"
runtime_archive="$bundle_dir/runtime.tar.gz"
claude_archive="$bundle_dir/tools/claude-code-linux-x64.tgz"
bundled_claude="$bundle_dir/bin/claude"
collaboration_cli="$bundle_dir/bin/ccrelay-cli"
relay_main="com.webank.wedatasphere.wdsavs.aiagent.remote.RemoteCcRelayServer"
loader_main="org.springframework.boot.loader.launch.PropertiesLauncher"
cc_config="${WDSAVS_CC_MODEL_CONFIG:-$bundle_dir/config/cc-model-config.yml}"
relay_hmac_secret_file="${WDSAVS_AI_RELAY_HMAC_SECRET_FILE:-$bundle_dir/config/relay-hmac-secret}"
relay_system_prompt_file="${WDSAVS_AI_RELAY_SYSTEM_PROMPT_FILE:-$bundle_dir/config/relay-system-prompt.txt}"
claude_config_dir="$bundle_dir/config/claude-runtime"
claude_settings_file="$claude_config_dir/settings.json"
skill_directory="${CCRELAY_SKILL_DIR:-$bundle_dir/skills}"

relay_port="${WDSAVS_CC_RELAY_PORT:-18091}"
relay_bind_host="${WDSAVS_CC_RELAY_HOST:-0.0.0.0}"
relay_node_host="${WDSAVS_AI_RELAY_NODE_HOST:-}"
relay_endpoint="${WDSAVS_AI_RELAY_ENDPOINT:-}"
relay_workdir="${WDSAVS_CC_RELAY_WORKDIR:-}"
relay_register_endpoint="${WDSAVS_AI_RELAY_REGISTER_ENDPOINT:-}"
relay_heartbeat_endpoint="${WDSAVS_AI_RELAY_HEARTBEAT_ENDPOINT:-}"
relay_grant_validate_endpoint="${WDSAVS_AI_RELAY_GRANT_VALIDATE_ENDPOINT:-}"
relay_kill_all_existing="${WDSAVS_CC_RELAY_KILL_ALL_EXISTING:-false}"
relay_startup_timeout_seconds="${WDSAVS_CC_RELAY_STARTUP_TIMEOUT_SECONDS:-10}"
preserve_archives="${WDSAVS_CC_RELAY_PRESERVE_ARCHIVES:-false}"
relay_pid_file="${WDSAVS_CC_RELAY_PID_FILE:-$bundle_dir/relay.pid}"
relay_log_file="${WDSAVS_CC_RELAY_LOG_FILE:-$bundle_dir/relay-startup.log}"
for arg in "$@"; do
  case "$arg" in
    --server.port=*)
      relay_port="${arg#--server.port=}"
      ;;
    --wdsavs.ai.remote-cc.relay.port=*)
      relay_port="${arg#--wdsavs.ai.remote-cc.relay.port=}"
      ;;
    --wdsavs.ai.remote-cc.relay.host=*)
      relay_node_host="${arg#--wdsavs.ai.remote-cc.relay.host=}"
      ;;
    --wdsavs.ai.remote-cc.relay.workspace-root=*)
      relay_workdir="${arg#--wdsavs.ai.remote-cc.relay.workspace-root=}"
      ;;
    --wdsavs.ai.remote-cc.relay.relay-endpoint=*)
      relay_endpoint="${arg#--wdsavs.ai.remote-cc.relay.relay-endpoint=}"
      ;;
    --wdsavs.ai.remote-cc.relay.center-register-endpoint=*)
      relay_register_endpoint="${arg#--wdsavs.ai.remote-cc.relay.center-register-endpoint=}"
      ;;
    --wdsavs.ai.remote-cc.relay.center-heartbeat-endpoint=*)
      relay_heartbeat_endpoint="${arg#--wdsavs.ai.remote-cc.relay.center-heartbeat-endpoint=}"
      ;;
    --wdsavs.ai.remote-cc.relay.center-grant-validate-endpoint=*)
      relay_grant_validate_endpoint="${arg#--wdsavs.ai.remote-cc.relay.center-grant-validate-endpoint=}"
      ;;
    --wdsavs.ai.remote-cc.relay.kill-all-existing=*)
      relay_kill_all_existing="${arg#--wdsavs.ai.remote-cc.relay.kill-all-existing=}"
      ;;
    --wdsavs.ai.remote-cc.relay.startup-timeout-seconds=*)
      relay_startup_timeout_seconds="${arg#--wdsavs.ai.remote-cc.relay.startup-timeout-seconds=}"
      ;;
  esac
done
if [ -z "$relay_endpoint" ] && [ -n "$relay_node_host" ] && [ -n "$relay_port" ]; then
  relay_endpoint="http://$relay_node_host:$relay_port/api/ai/remote-cc/chat"
fi
export WDSAVS_CC_RELAY_HOST="$relay_bind_host"
export WDSAVS_CC_RELAY_PORT="$relay_port"
export CCRELAY_SKILL_DIR="$skill_directory"
export WDSAVS_AI_RELAY_NODE_ID_FILE="${WDSAVS_AI_RELAY_NODE_ID_FILE:-$bundle_dir/config/node-id.txt}"
[ ! -f "$relay_hmac_secret_file" ] || /usr/bin/chmod 600 "$relay_hmac_secret_file" >/dev/null 2>&1 || true
if [ -f "$relay_hmac_secret_file" ]; then
  relay_hmac_secret="$(/bin/cat "$relay_hmac_secret_file")"
  [ -z "$relay_hmac_secret" ] || export WDSAVS_AI_HMAC_SECRET="$relay_hmac_secret"
fi
[ -n "$relay_node_host" ] && export WDSAVS_AI_RELAY_NODE_HOST="$relay_node_host"
[ -n "$relay_endpoint" ] && export WDSAVS_AI_RELAY_ENDPOINT="$relay_endpoint"
[ -n "$relay_workdir" ] && export WDSAVS_CC_RELAY_WORKDIR="$relay_workdir"
[ -n "$relay_register_endpoint" ] && export WDSAVS_AI_RELAY_REGISTER_ENDPOINT="$relay_register_endpoint"
[ -n "$relay_heartbeat_endpoint" ] && export WDSAVS_AI_RELAY_HEARTBEAT_ENDPOINT="$relay_heartbeat_endpoint"
[ -n "$relay_grant_validate_endpoint" ] && export WDSAVS_AI_RELAY_GRANT_VALIDATE_ENDPOINT="$relay_grant_validate_endpoint"
[ -f "$relay_system_prompt_file" ] && export WDSAVS_AI_RELAY_SYSTEM_PROMPT_FILE="$relay_system_prompt_file"

if [ ! -f "$app_jar" ]; then
  echo "Bundled application jar not found: $app_jar" >&2
  exit 22
fi
if [ ! -f "$collaboration_cli" ]; then
  echo "Bundled Relay collaboration CLI not found: $collaboration_cli" >&2
  exit 25
fi

extract_runtime_from_archive() {
  if [ ! -f "$runtime_archive" ]; then
    return 1
  fi
  /bin/rm -rf "$bundle_dir/runtime.tmp"
  /bin/mkdir -p "$bundle_dir/runtime.tmp"
  if [ -x /usr/bin/python3 ]; then
    /usr/bin/python3 - "$runtime_archive" "$bundle_dir/runtime.tmp" "$bundle_dir/runtime" <<'PY'
import shutil
import sys
import tarfile
from pathlib import Path
archive = Path(sys.argv[1])
extract_root = Path(sys.argv[2])
out_dir = Path(sys.argv[3])
if out_dir.exists():
    shutil.rmtree(out_dir)
if extract_root.exists():
    shutil.rmtree(extract_root)
extract_root.mkdir(parents=True, exist_ok=True)
with tarfile.open(archive, 'r:gz') as tf:
    tf.extractall(extract_root)
children = [p for p in extract_root.iterdir() if p.is_dir()]
if not children:
    raise SystemExit('no extracted runtime directory found')
shutil.move(str(children[0]), str(out_dir))
PY
  elif [ -x /usr/bin/tar ]; then
    /usr/bin/tar -xzf "$runtime_archive" -C "$bundle_dir/runtime.tmp"
    first_dir="$(/bin/ls -1 "$bundle_dir/runtime.tmp" | /usr/bin/head -n 1)"
    /bin/rm -rf "$bundle_dir/runtime"
    /bin/mkdir -p "$bundle_dir/runtime"
    /bin/rmdir "$bundle_dir/runtime"
    /bin/mv "$bundle_dir/runtime.tmp/$first_dir" "$bundle_dir/runtime"
  else
    echo "No extractor available for bundled runtime archive: $runtime_archive" >&2
    return 1
  fi
  /bin/rm -rf "$bundle_dir/runtime.tmp"
  if [ "$preserve_archives" != "true" ]; then
    /bin/rm -f "$runtime_archive" >/dev/null 2>&1 || true
  fi
  return 0
}

extract_claude_code_from_archive() {
  if [ ! -f "$claude_archive" ]; then
    return 1
  fi
  /bin/mkdir -p "$bundle_dir/bin" "$bundle_dir/tools/claude.tmp"
  /bin/rm -rf "$bundle_dir/tools/claude.tmp"
  /bin/mkdir -p "$bundle_dir/tools/claude.tmp"
  if [ -x /usr/bin/python3 ]; then
    /usr/bin/python3 - "$claude_archive" "$bundle_dir/tools/claude.tmp" "$bundled_claude" <<'PY'
import shutil
import sys
import tarfile
from pathlib import Path
archive = Path(sys.argv[1])
extract_root = Path(sys.argv[2])
out_file = Path(sys.argv[3])
if extract_root.exists():
    shutil.rmtree(extract_root)
extract_root.mkdir(parents=True, exist_ok=True)
with tarfile.open(archive, 'r:gz') as tf:
    tf.extractall(extract_root)
candidate = extract_root / "package" / "claude"
if not candidate.exists():
    matches = list(extract_root.rglob("claude"))
    if not matches:
        raise SystemExit("claude binary not found in archive")
    candidate = matches[0]
out_file.parent.mkdir(parents=True, exist_ok=True)
if out_file.exists():
    out_file.unlink()
shutil.move(str(candidate), str(out_file))
PY
  elif [ -x /usr/bin/tar ]; then
    /usr/bin/tar -xzf "$claude_archive" -C "$bundle_dir/tools/claude.tmp"
    /bin/rm -f "$bundled_claude"
    /bin/mv "$bundle_dir/tools/claude.tmp/package/claude" "$bundled_claude"
  else
    echo "No extractor available for bundled Claude Code archive: $claude_archive" >&2
    return 1
  fi
  /bin/rm -rf "$bundle_dir/tools/claude.tmp"
  if [ "$preserve_archives" != "true" ]; then
    /bin/rm -f "$claude_archive" >/dev/null 2>&1 || true
  fi
  if [ -x /usr/bin/chmod ]; then
    /usr/bin/chmod +x "$bundled_claude" >/dev/null 2>&1 || true
  fi
  return 0
}

/bin/mkdir -p "$bundle_dir/runtime/ccrelay"
/bin/mkdir -p "$claude_config_dir"
/bin/mkdir -p "$skill_directory"
printf '{}\n' > "$claude_settings_file"
/usr/bin/chmod 600 "$claude_settings_file" >/dev/null 2>&1 || true
export CLAUDE_CONFIG_DIR="$claude_config_dir"
export WDSAVS_CC_CLAUDE_SETTINGS_FILE="$claude_settings_file"
if [ -n "$relay_register_endpoint" ]; then
  export CCRELAY_CENTER_URL="${relay_register_endpoint%%/api/skill/*}"
fi

if [ -x /usr/bin/chmod ] && [ -f "$java_bin" ]; then
  /usr/bin/chmod +x "$java_bin" >/dev/null 2>&1 || true
fi

if [ ! -x "$java_bin" ]; then
  extract_runtime_from_archive || true
fi

if [ -x /usr/bin/chmod ] && [ -d "$bundle_dir/runtime/bin" ]; then
  /usr/bin/chmod +x "$bundle_dir/runtime/bin"/* >/dev/null 2>&1 || true
fi

if [ -x /usr/bin/find ] && [ -d "$bundle_dir/runtime" ]; then
  /usr/bin/find "$bundle_dir/runtime" -type f -name jspawnhelper -exec /usr/bin/chmod +x {} + >/dev/null 2>&1 || true
fi

if [ ! -x "$java_bin" ]; then
  echo "Bundled Java runtime not found: $java_bin" >&2
  exit 21
fi

cd "$bundle_dir"
unset WDSAVS_CC_RELAY_COMMAND
unset WDSAVS_CC_RELAY_ARGS
if [ -f "$bundled_claude" ] && [ -x /usr/bin/chmod ]; then
  /usr/bin/chmod +x "$bundled_claude" >/dev/null 2>&1 || true
fi
if [ -x /usr/bin/chmod ]; then
  /usr/bin/chmod +x "$collaboration_cli" >/dev/null 2>&1 || true
fi
if [ ! -x "$bundled_claude" ]; then
  if ! extract_claude_code_from_archive; then
    echo "Bundled Claude Code is unavailable and could not be extracted: $claude_archive" >&2
    exit 23
  fi
fi
if [ -f "$bundled_claude" ] && [ -x /usr/bin/chmod ]; then
  /usr/bin/chmod +x "$bundled_claude" >/dev/null 2>&1 || true
fi
if [ ! -x "$bundled_claude" ]; then
  echo "Bundled Claude Code executable not found: $bundled_claude" >&2
  exit 23
fi
if ! "$bundled_claude" --version >/dev/null 2>&1; then
  echo "Bundled Claude Code preflight failed: $bundled_claude --version" >&2
  exit 24
fi
if [ -f "$relay_pid_file" ]; then
  old_pid="$(/bin/cat "$relay_pid_file" 2>/dev/null || true)"
  if [ -n "$old_pid" ]; then
    /bin/kill "$old_pid" >/dev/null 2>&1 || true
    /bin/sleep 2
  fi
fi
if [ -n "$relay_port" ]; then
  if [ -x /bin/ps ] && [ -x /usr/bin/awk ]; then
    relay_pids="$(/bin/ps -eo pid=,args= 2>/dev/null | /usr/bin/awk -v port="$relay_port" '/RemoteCcRelayServer/ && ($0 ~ ("--server.port=" port "([[:space:]]|$)") || $0 ~ ("--wdsavs.ai.remote-cc.relay.port=" port "([[:space:]]|$)")) { print $1 }')"
    if [ -z "$relay_pids" ] && [ "$relay_kill_all_existing" = "true" ]; then
      relay_pids="$(/bin/ps -eo pid=,args= 2>/dev/null | /usr/bin/awk '
        /RemoteCcRelayServer/ { print $1; next }
        /ccrelay/ && /java|PropertiesLauncher/ { print $1 }
      ')"
    fi
    if [ -n "$relay_pids" ]; then
      /bin/kill $relay_pids >/dev/null 2>&1 || true
      /bin/sleep 2
    fi
  fi
fi
if [ ! -f "$cc_config" ]; then
  echo "Cluster model configuration was not delivered by the Skill host: $cc_config" >&2
  exit 35
fi
unset OPENAI_MODEL OPENAI_BASE_URL OPENAI_API_KEY
unset ANTHROPIC_MODEL ANTHROPIC_BASE_URL ANTHROPIC_AUTH_TOKEN ANTHROPIC_API_KEY
unset ANTHROPIC_DEFAULT_OPUS_MODEL ANTHROPIC_DEFAULT_SONNET_MODEL ANTHROPIC_DEFAULT_HAIKU_MODEL
unset CLAUDE_CODE_API_KEY CLAUDE_CODE_SUBAGENT_MODEL WDSAVS_CC_RELAY_COMMAND WDSAVS_CC_RELAY_ARGS
cc_model="$(/usr/bin/awk -F ':' '/^model:/ {sub(/^[ \t]+/, "", $2); gsub(/\r/, "", $2); gsub(/^"|"$/, "", $2); print $2; exit}' "$cc_config" 2>/dev/null || true)"
cc_base_url="$(/usr/bin/awk -F ':' '/^baseUrl:/ {value=$0; sub(/^[^:]*:[ \t]*/, "", value); gsub(/\r/, "", value); gsub(/^"|"$/, "", value); print value; exit}' "$cc_config" 2>/dev/null || true)"
cc_api_key="$(/usr/bin/awk -F ':' '/^apiKey:/ {value=$0; sub(/^[^:]*:[ \t]*/, "", value); gsub(/\r/, "", value); gsub(/^"|"$/, "", value); print value; exit}' "$cc_config" 2>/dev/null || true)"
cc_relay_command="$(/usr/bin/awk -F ':' '/^relayCommand:/ {sub(/^[ \t]+/, "", $2); gsub(/\r/, "", $2); gsub(/^"|"$/, "", $2); print $2; exit}' "$cc_config" 2>/dev/null || true)"
cc_relay_args="$(/usr/bin/awk '
    /^relayArgs:/ { in_args=1; next }
    in_args && /^[^[:space:]-]/ { exit }
    in_args && /^[[:space:]]*-/ {
      value=$0
      sub(/^[[:space:]]*-[[:space:]]*/, "", value)
      gsub(/\r/, "", value)
      gsub(/^"|"$/, "", value)
      printf "%s ", value
    }
  ' "$cc_config" 2>/dev/null || true)"
if [ -z "$cc_model" ] || [ -z "$cc_base_url" ] || [ -z "$cc_api_key" ]; then
  echo "Cluster model configuration is incomplete: model, baseUrl and apiKey are required" >&2
  exit 35
fi
export OPENAI_MODEL="$cc_model"
export OPENAI_BASE_URL="$cc_base_url"
export OPENAI_API_KEY="$cc_api_key"
export ANTHROPIC_MODEL="$cc_model"
export ANTHROPIC_BASE_URL="$cc_base_url"
export ANTHROPIC_AUTH_TOKEN="$cc_api_key"
export ANTHROPIC_API_KEY="$cc_api_key"
export CLAUDE_CODE_API_KEY="$cc_api_key"
export ANTHROPIC_DEFAULT_OPUS_MODEL="$cc_model"
export ANTHROPIC_DEFAULT_SONNET_MODEL="$cc_model"
export ANTHROPIC_DEFAULT_HAIKU_MODEL="$cc_model"
export CLAUDE_CODE_SUBAGENT_MODEL="$cc_model"
export CLAUDE_CODE_EFFORT_LEVEL="${CLAUDE_CODE_EFFORT_LEVEL:-max}"
[ -n "$cc_relay_command" ] && export WDSAVS_CC_RELAY_COMMAND="$cc_relay_command"
if [ "$cc_relay_command" = "claude" ] && [ -x "$bundled_claude" ]; then
  export WDSAVS_CC_RELAY_COMMAND="$bundled_claude"
fi
if [ -n "$cc_relay_args" ]; then
  export WDSAVS_CC_RELAY_ARGS="$cc_relay_args"
elif [ "$cc_relay_command" = "claude" ]; then
  export WDSAVS_CC_RELAY_ARGS="--bare --print --model $cc_model --effort low"
fi
: > "$relay_log_file"
/usr/bin/nohup "$java_bin" -Dloader.main="$relay_main" -cp "$app_jar" "$loader_main" "$@" >> "$relay_log_file" 2>&1 < /dev/null &
echo $! > "$relay_pid_file"
relay_pid="$(/bin/cat "$relay_pid_file" 2>/dev/null || true)"
health_url="http://127.0.0.1:$relay_port/health"
if ! "$java_bin" -Dloader.main=com.webank.wedatasphere.wdsavs.aiagent.remote.RelayHealthProbe \
    -cp "$app_jar" "$loader_main" "$health_url" "$relay_startup_timeout_seconds"; then
  if [ -n "$relay_pid" ]; then
    /bin/kill "$relay_pid" >/dev/null 2>&1 || true
  fi
  echo "Relay health check failed during startup. See $relay_log_file" >&2
  /usr/bin/tail -n 80 "$relay_log_file" >&2 2>/dev/null || /bin/cat "$relay_log_file" >&2 2>/dev/null || true
  exit 32
fi
ai_readiness_url="http://127.0.0.1:$relay_port/ai-readiness"
if ! "$java_bin" -Dloader.main=com.webank.wedatasphere.wdsavs.aiagent.remote.RelayHealthProbe \
    -cp "$app_jar" "$loader_main" "$ai_readiness_url" "$relay_startup_timeout_seconds" "READY"; then
  if [ -n "$relay_pid" ]; then
    /bin/kill "$relay_pid" >/dev/null 2>&1 || true
  fi
  echo "Relay AI readiness check failed: model configuration is missing or incomplete. See $relay_log_file" >&2
  /usr/bin/tail -n 80 "$relay_log_file" >&2 2>/dev/null || /bin/cat "$relay_log_file" >&2 2>/dev/null || true
  exit 33
fi
if [ -z "$relay_pid" ] || ! /bin/kill -0 "$relay_pid" >/dev/null 2>&1; then
  echo "Relay process exited during startup. See $relay_log_file" >&2
  /usr/bin/tail -n 80 "$relay_log_file" >&2 2>/dev/null || /bin/cat "$relay_log_file" >&2 2>/dev/null || true
  exit 31
fi
exit 0


