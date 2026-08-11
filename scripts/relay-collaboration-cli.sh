#!/usr/bin/env sh
set -eu
bundle_dir="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
java_bin="$bundle_dir/runtime/bin/java"
if [ ! -x "$java_bin" ]; then
  runtime_tmp="$bundle_dir/.runtime-extract.$$"
  rm -rf "$runtime_tmp"
  mkdir -p "$runtime_tmp"
  tar -xzf "$bundle_dir/runtime.tar.gz" -C "$runtime_tmp" --strip-components=1
  rm -rf "$bundle_dir/runtime"
  mv "$runtime_tmp" "$bundle_dir/runtime"
  java_bin="$bundle_dir/runtime/bin/java"
fi
exec "$java_bin" \
  -Dloader.main=com.webank.wedatasphere.wdsavs.aiagent.remote.RelayCollaborationCli \
  -cp "$bundle_dir/app.jar" \
  org.springframework.boot.loader.launch.PropertiesLauncher "$@"
