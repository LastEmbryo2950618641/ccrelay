#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

log() {
  printf '%s\n' "[INFO] $1"
}

fail() {
  printf '%s\n' "[ERROR] $1" >&2
  exit 1
}

have() {
  command -v "$1" >/dev/null 2>&1
}

install_powershell() {
  if have pwsh; then
    return 0
  fi

  if have apt-get; then
    log 'Installing PowerShell via apt-get'
    apt-get update
    apt-get install -y wget apt-transport-https software-properties-common gnupg ca-certificates
    . /etc/os-release
    wget -q "https://packages.microsoft.com/config/${ID}/${VERSION_ID}/packages-microsoft-prod.deb" -O /tmp/packages-microsoft-prod.deb
    dpkg -i /tmp/packages-microsoft-prod.deb
    rm -f /tmp/packages-microsoft-prod.deb
    apt-get update
    apt-get install -y powershell
    return 0
  fi

  if have dnf; then
    log 'Installing PowerShell via dnf'
    rpm --import https://packages.microsoft.com/keys/microsoft.asc
    . /etc/os-release
    cat >/etc/yum.repos.d/microsoft.repo <<EOF
[packages-microsoft-com-prod]
name=packages-microsoft-com-prod
baseurl=https://packages.microsoft.com/yumrepos/microsoft-${ID}-${VERSION_ID}-prod
enabled=1
gpgcheck=1
gpgkey=https://packages.microsoft.com/keys/microsoft.asc
EOF
    dnf install -y powershell
    return 0
  fi

  if have yum; then
    log 'Installing PowerShell via yum'
    rpm --import https://packages.microsoft.com/keys/microsoft.asc
    . /etc/os-release
    cat >/etc/yum.repos.d/microsoft.repo <<EOF
[packages-microsoft-com-prod]
name=packages-microsoft-com-prod
baseurl=https://packages.microsoft.com/yumrepos/microsoft-${ID}-${VERSION_ID}-prod
enabled=1
gpgcheck=1
gpgkey=https://packages.microsoft.com/keys/microsoft.asc
EOF
    yum install -y powershell
    return 0
  fi

  fail 'pwsh is required and automatic installation is unsupported on this Gitee Go runner'
}

ensure_python() {
  if have python3; then
    PYTHON_BIN=$(command -v python3)
    return 0
  fi
  if have python; then
    PYTHON_BIN=$(command -v python)
    return 0
  fi
  fail 'Python 3 is required on the Gitee Go runner'
}

ensure_gradle_command() {
  if [ -x "$REPO_ROOT/gradlew" ]; then
    GRADLE_CMD="$REPO_ROOT/gradlew"
    return 0
  fi
  if have gradle; then
    GRADLE_CMD=$(command -v gradle)
    return 0
  fi
  fail 'Gradle Wrapper or Gradle is required on the Gitee Go runner'
}

ensure_java() {
  if have java; then
    return 0
  fi
  fail 'Java 21 is required on the Gitee Go runner'
}

install_powershell
ensure_python
ensure_gradle_command
ensure_java

cd "$REPO_ROOT"
chmod +x "$REPO_ROOT/gradlew"

log 'Running Java tests'
"$GRADLE_CMD" test

log 'Running CLI tests'
"$PYTHON_BIN" scripts/test_ccrelay_cli.py
"$PYTHON_BIN" scripts/test_release_packages.py

log 'Building boot JAR'
"$GRADLE_CMD" bootJar

BOOT_JAR=$(ls -1t build/libs/*.jar 2>/dev/null | head -n 1 || true)
[ -n "$BOOT_JAR" ] || fail 'Boot JAR was not generated'

log 'Building runtime bundle'
pwsh -NoLogo -NoProfile -File "$REPO_ROOT/scripts/build_runtime_bundle.ps1" \
  -BootJarPath "$BOOT_JAR" \
  -BundleRoot "$REPO_ROOT/build/runtime-bundle/ccrelay"

log 'Assembling release packages'
sh "$REPO_ROOT/build.sh"

log 'Release artifacts ready under dist/release'