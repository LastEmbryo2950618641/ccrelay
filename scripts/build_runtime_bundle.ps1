param(
    [string]$BootJarPath,
    [string]$BundleRoot = (Join-Path $PSScriptRoot '../build/runtime-bundle/ccrelay'),
    [string]$RuntimeCacheRoot = (Join-Path $PSScriptRoot '../.cache/runtime-bundle'),
    [string]$RuntimeArchivePath,
    [string]$RuntimeHomePath,
    [string]$RuntimeArchiveUrl = 'https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jre/hotspot/normal/eclipse',
    [string]$WindowsRuntimeArchivePath,
    [string]$WindowsRuntimeArchiveUrl = 'https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jre/hotspot/normal/eclipse',
    [string]$PythonWindowsArchivePath,
    [string]$PythonWindowsArchiveUrl = 'https://www.python.org/ftp/python/3.13.15/python-3.13.15-embed-amd64.zip',
    [string]$PythonLinuxArchivePath,
    [string]$PythonLinuxArchiveUrl = 'https://github.com/astral-sh/python-build-standalone/releases/download/20260807/cpython-3.13.15%2B20260807-x86_64-unknown-linux-gnu-install_only_stripped.tar.gz',
    [string]$ClaudeCodeVersion = '2.1.220',
    [string]$ClaudeCodeArchivePath,
    [string]$ModelConfigPath
)

$ErrorActionPreference = 'Stop'

function Test-TarArchive {
    param([string]$ArchivePath)
    if (-not (Test-Path $ArchivePath)) {
        return $false
    }
    & tar -tzf $ArchivePath 1>$null
    return $LASTEXITCODE -eq 0
}

function Test-ZipArchive {
    param([string]$ArchivePath)
    if (-not (Test-Path $ArchivePath)) {
        return $false
    }
    try {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $archive = [System.IO.Compression.ZipFile]::OpenRead($ArchivePath)
        $archive.Dispose()
        return $true
    } catch {
        return $false
    }
}

function Download-RuntimeArchive {
    param(
        [string]$Url,
        [string]$DestinationPath
    )
    try {
        Invoke-WebRequest -Uri $Url -OutFile $DestinationPath -Headers @{ 'User-Agent' = 'Mozilla/5.0' }
    } catch {
        throw "Failed to download Linux JRE archive from $Url. Provide -RuntimeArchivePath or -RuntimeHomePath instead."
    }
    if (-not (Test-TarArchive $DestinationPath)) {
        throw "Downloaded runtime archive is invalid: $DestinationPath"
    }
}

function Download-WindowsRuntimeArchive {
    param(
        [string]$Url,
        [string]$DestinationPath
    )
    try {
        Invoke-WebRequest -Uri $Url -OutFile $DestinationPath -Headers @{ 'User-Agent' = 'Mozilla/5.0' }
    } catch {
        throw "Failed to download Windows JRE archive from $Url. Provide -WindowsRuntimeArchivePath instead."
    }
    if (-not (Test-ZipArchive $DestinationPath)) {
        throw "Downloaded Windows JRE archive is invalid: $DestinationPath"
    }
}

function Download-PythonArchive {
    param(
        [string]$Url,
        [string]$DestinationPath,
        [ValidateSet('ZIP', 'TAR')]
        [string]$Kind
    )
    try {
        Invoke-WebRequest -Uri $Url -OutFile $DestinationPath -Headers @{ 'User-Agent' = 'ccrelay-build' }
    } catch {
        throw "Failed to download bundled Python runtime from $Url. Provide the corresponding archive path instead."
    }
    $valid = if ($Kind -eq 'ZIP') { Test-ZipArchive $DestinationPath } else { Test-TarArchive $DestinationPath }
    if (-not $valid) {
        throw "Downloaded bundled Python runtime archive is invalid: $DestinationPath"
    }
}

function Resolve-ClaudeCodeArchive {
    param(
        [string]$Version,
        [string]$CacheRoot,
        [string]$ArchivePath
    )
    if ($ArchivePath) {
        $resolved = (Resolve-Path $ArchivePath).Path
        if (-not (Test-TarArchive $resolved)) {
            throw "Claude Code archive is invalid: $resolved"
        }
        return $resolved
    }

    $claudeCacheRoot = Join-Path $CacheRoot '../claude-code-linux-x64'
    New-Item -ItemType Directory -Force -Path $claudeCacheRoot | Out-Null
    $archiveName = "anthropic-ai-claude-code-linux-x64-$Version.tgz"
    $archive = Join-Path $claudeCacheRoot $archiveName
    if (Test-TarArchive $archive) {
        return $archive
    }

    if (Test-Path $archive) {
        Remove-Item -Force $archive
    }
    $archiveUrl = "https://registry.npmjs.org/@anthropic-ai/claude-code-linux-x64/-/claude-code-linux-x64-$Version.tgz"
    $curl = Get-Command curl.exe -ErrorAction SilentlyContinue
    if (-not $curl) {
        $curl = Get-Command curl -ErrorAction SilentlyContinue
    }
    if (-not $curl) {
        throw "curl is required to download the Claude Code Linux archive. Provide -ClaudeCodeArchivePath instead."
    }
    & $curl.Source --fail --location --silent --show-error --output $archive $archiveUrl
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to download Claude Code Linux archive from $archiveUrl. Provide -ClaudeCodeArchivePath instead."
    }
    if (-not (Test-TarArchive $archive)) {
        throw "Downloaded Claude Code Linux archive is invalid: $archive"
    }
    return $archive
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$installRelayScriptPath = Join-Path $repoRoot 'scripts/install-relay.sh'
$installRelayPowerShellScriptPath = Join-Path $repoRoot 'scripts/install-relay.ps1'
$ccConfigTemplatePath = Join-Path $repoRoot 'codex-skill/ccrelay/assets/config-templates/cc-model-config.template.yml'
$relaySystemPromptPath = Join-Path $repoRoot 'src/main/resources/config/relay-system-prompt.txt'
$prepareCcConfigPath = Join-Path $repoRoot 'scripts/prepare_cc_config.py'
if (-not $BootJarPath) {
    & (Join-Path $repoRoot 'gradlew.bat') bootJar
    if ($LASTEXITCODE -ne 0) {
        throw 'bootJar build failed.'
    }
    $bootJar = Get-ChildItem -Path (Join-Path $repoRoot 'build/libs') -Filter '*.jar' -File | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $bootJar) {
        throw 'bootJar was not found after build.'
    }
    $BootJarPath = $bootJar.FullName
}

if (-not (Test-Path $BootJarPath)) {
    throw "bootJar not found: $BootJarPath"
}

$runtimeArchivePath = if ($RuntimeArchivePath) { (Resolve-Path $RuntimeArchivePath).Path } else { Join-Path $RuntimeCacheRoot 'temurin-linux-x64-jre21.tar.gz' }
$windowsRuntimeArchivePath = if ($WindowsRuntimeArchivePath) { (Resolve-Path $WindowsRuntimeArchivePath).Path } else { Join-Path $RuntimeCacheRoot 'temurin-windows-x64-jre21.zip' }
$pythonWindowsArchivePath = if ($PythonWindowsArchivePath) { (Resolve-Path $PythonWindowsArchivePath).Path } else { Join-Path $RuntimeCacheRoot 'python-windows-x64-3.13.15-embed.zip' }
$pythonLinuxArchivePath = if ($PythonLinuxArchivePath) { (Resolve-Path $PythonLinuxArchivePath).Path } else { Join-Path $RuntimeCacheRoot 'python-linux-x64-3.13.15-stripped.tar.gz' }
$runtimeExtractRoot = Join-Path $RuntimeCacheRoot 'extract'
$windowsRuntimeExtractRoot = Join-Path $RuntimeCacheRoot 'windows-extract'
$pythonWindowsExtractRoot = Join-Path $RuntimeCacheRoot 'python-windows-extract'
$claudeExtractRoot = Join-Path $RuntimeCacheRoot 'claude-extract'
$claudeCodeArchive = Resolve-ClaudeCodeArchive -Version $ClaudeCodeVersion -CacheRoot $RuntimeCacheRoot -ArchivePath $ClaudeCodeArchivePath

if ($RuntimeHomePath) {
    $runtimeHome = Get-Item -LiteralPath $RuntimeHomePath
    if (-not $runtimeHome.PSIsContainer) {
        throw "RuntimeHomePath must be a directory: $RuntimeHomePath"
    }
} else {
    New-Item -ItemType Directory -Force -Path $RuntimeCacheRoot | Out-Null
    if (-not (Test-TarArchive $runtimeArchivePath)) {
        if (Test-Path $runtimeArchivePath) {
            Remove-Item -Force $runtimeArchivePath
        }
        Download-RuntimeArchive -Url $RuntimeArchiveUrl -DestinationPath $runtimeArchivePath
    }

    $runtimeHome = Get-ChildItem -Path $runtimeExtractRoot -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $runtimeHome -or -not (Test-Path (Join-Path $runtimeHome.FullName 'bin/java'))) {
        if (Test-Path $runtimeExtractRoot) {
            Remove-Item -Recurse -Force $runtimeExtractRoot
        }
        New-Item -ItemType Directory -Force -Path $runtimeExtractRoot | Out-Null
        & tar -xzf $runtimeArchivePath -C $runtimeExtractRoot
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to extract runtime archive: $runtimeArchivePath"
        }
        $runtimeHome = Get-ChildItem -Path $runtimeExtractRoot -Directory | Select-Object -First 1
    }
    if (-not $runtimeHome) {
        throw 'Unable to locate extracted runtime directory.'
    }
    if (-not (Test-Path (Join-Path $runtimeHome.FullName 'bin/java'))) {
        throw 'Extracted runtime does not contain bin/java.'
    }
}

New-Item -ItemType Directory -Force -Path $RuntimeCacheRoot | Out-Null
if (-not (Test-ZipArchive $windowsRuntimeArchivePath)) {
    if (Test-Path $windowsRuntimeArchivePath) {
        Remove-Item -Force $windowsRuntimeArchivePath
    }
    Download-WindowsRuntimeArchive -Url $WindowsRuntimeArchiveUrl -DestinationPath $windowsRuntimeArchivePath
}
$windowsRuntimeHome = Get-ChildItem -Path $windowsRuntimeExtractRoot -Directory -ErrorAction SilentlyContinue |
    Where-Object { Test-Path (Join-Path $_.FullName 'bin/java.exe') } | Select-Object -First 1
if (-not $windowsRuntimeHome) {
    if (Test-Path $windowsRuntimeExtractRoot) {
        Remove-Item -Recurse -Force $windowsRuntimeExtractRoot
    }
    New-Item -ItemType Directory -Force -Path $windowsRuntimeExtractRoot | Out-Null
    Expand-Archive -LiteralPath $windowsRuntimeArchivePath -DestinationPath $windowsRuntimeExtractRoot -Force
    $windowsRuntimeHome = Get-ChildItem -Path $windowsRuntimeExtractRoot -Directory |
        Where-Object { Test-Path (Join-Path $_.FullName 'bin/java.exe') } | Select-Object -First 1
}
if (-not $windowsRuntimeHome) {
    throw 'Unable to locate extracted Windows JRE directory.'
}

if (-not (Test-ZipArchive $pythonWindowsArchivePath)) {
    if (Test-Path $pythonWindowsArchivePath) {
        Remove-Item -Force $pythonWindowsArchivePath
    }
    Download-PythonArchive -Url $PythonWindowsArchiveUrl -DestinationPath $pythonWindowsArchivePath -Kind ZIP
}
if (Test-Path $pythonWindowsExtractRoot) {
    Remove-Item -Recurse -Force $pythonWindowsExtractRoot
}
New-Item -ItemType Directory -Force -Path $pythonWindowsExtractRoot | Out-Null
Expand-Archive -LiteralPath $pythonWindowsArchivePath -DestinationPath $pythonWindowsExtractRoot -Force
$pythonWindowsHome = Get-Item -LiteralPath $pythonWindowsExtractRoot
if (-not (Test-Path (Join-Path $pythonWindowsHome.FullName 'python.exe'))) {
    throw 'Bundled Windows Python archive does not contain python.exe.'
}
$pythonPathConfig = Get-ChildItem -LiteralPath $pythonWindowsHome.FullName -Filter '*._pth' -File | Select-Object -First 1
if (-not $pythonPathConfig) {
    throw 'Bundled Windows Python archive does not contain a ._pth configuration file.'
}
$pythonPathLines = @(Get-Content -LiteralPath $pythonPathConfig.FullName)
$skillScriptsRelativePath = '..\..\..\..\scripts'
if ($pythonPathLines -notcontains $skillScriptsRelativePath) {
    $pythonPathLines += $skillScriptsRelativePath
}
[System.IO.File]::WriteAllLines(
    $pythonPathConfig.FullName,
    $pythonPathLines,
    [System.Text.UTF8Encoding]::new($false)
)

if (-not (Test-TarArchive $pythonLinuxArchivePath)) {
    if (Test-Path $pythonLinuxArchivePath) {
        Remove-Item -Force $pythonLinuxArchivePath
    }
    Download-PythonArchive -Url $PythonLinuxArchiveUrl -DestinationPath $pythonLinuxArchivePath -Kind TAR
}
if (Test-Path $BundleRoot) {
    Remove-Item -Recurse -Force $BundleRoot
}
New-Item -ItemType Directory -Force -Path (Join-Path $BundleRoot 'bin') | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $BundleRoot 'tools') | Out-Null
Copy-Item -Path $BootJarPath -Destination (Join-Path $BundleRoot 'app.jar') -Force
Copy-Item -Path $runtimeArchivePath -Destination (Join-Path $BundleRoot 'runtime.tar.gz') -Force
Copy-Item -Path $windowsRuntimeArchivePath -Destination (Join-Path $BundleRoot 'runtime-windows.zip') -Force
Copy-Item -Recurse -Force -Path $pythonWindowsHome.FullName -Destination (Join-Path $BundleRoot 'python-windows')
Copy-Item -Force -Path $pythonLinuxArchivePath -Destination (Join-Path $BundleRoot 'python-linux.tar.gz')
Copy-Item -Path $claudeCodeArchive -Destination (Join-Path $BundleRoot 'tools/claude-code-linux-x64.tgz') -Force
if (Test-Path $claudeExtractRoot) {
    Remove-Item -Recurse -Force $claudeExtractRoot
}
New-Item -ItemType Directory -Force -Path $claudeExtractRoot | Out-Null
& tar -xzf $claudeCodeArchive -C $claudeExtractRoot
if ($LASTEXITCODE -ne 0) {
    throw "Failed to extract Claude Code archive: $claudeCodeArchive"
}
$claudeBinary = Join-Path $claudeExtractRoot 'package/claude'
if (-not (Test-Path $claudeBinary)) {
    throw "Claude binary not found after extract: $claudeBinary"
}
Copy-Item -Path $claudeBinary -Destination (Join-Path $BundleRoot 'bin/claude') -Force
if (-not (Test-Path $installRelayScriptPath)) {
    throw "install-relay.sh not found: $installRelayScriptPath"
}
Copy-Item -Path $installRelayScriptPath -Destination (Join-Path $BundleRoot 'install-relay.sh') -Force
if (-not (Test-Path $installRelayPowerShellScriptPath)) {
    throw "install-relay.ps1 not found: $installRelayPowerShellScriptPath"
}
Copy-Item -Path $installRelayPowerShellScriptPath -Destination (Join-Path $BundleRoot 'install-relay.ps1') -Force
$collaborationCliShPath = Join-Path $repoRoot 'scripts/relay-collaboration-cli.sh'
$collaborationCliCmdPath = Join-Path $repoRoot 'scripts/relay-collaboration-cli.cmd'
if (-not (Test-Path $collaborationCliShPath) -or -not (Test-Path $collaborationCliCmdPath)) {
    throw 'Bundled relay collaboration CLI wrappers are missing.'
}
Copy-Item -Path $collaborationCliShPath -Destination (Join-Path $BundleRoot 'bin/ccrelay-cli') -Force
Copy-Item -Path $collaborationCliCmdPath -Destination (Join-Path $BundleRoot 'bin/ccrelay-cli.cmd') -Force
if (Test-Path $ccConfigTemplatePath) {
    New-Item -ItemType Directory -Force -Path (Join-Path $BundleRoot 'config') | Out-Null
    Copy-Item -Path $ccConfigTemplatePath -Destination (Join-Path $BundleRoot 'config/cc-model-config.template.yml') -Force
}
if ($ModelConfigPath) {
    $resolvedModelConfigPath = (Resolve-Path $ModelConfigPath -ErrorAction Stop).Path
    if (-not (Test-Path $resolvedModelConfigPath -PathType Leaf)) {
        throw "ModelConfigPath must point to a file: $ModelConfigPath"
    }
    New-Item -ItemType Directory -Force -Path (Join-Path $BundleRoot 'config') | Out-Null
    Copy-Item -Path $resolvedModelConfigPath -Destination (Join-Path $BundleRoot 'config/cc-model-config.yml') -Force
}
if (Test-Path $relaySystemPromptPath) {
    New-Item -ItemType Directory -Force -Path (Join-Path $BundleRoot 'config') | Out-Null
    Copy-Item -Path $relaySystemPromptPath -Destination (Join-Path $BundleRoot 'config/relay-system-prompt.txt') -Force
}
if (Test-Path $prepareCcConfigPath) {
    New-Item -ItemType Directory -Force -Path (Join-Path $BundleRoot 'bin') | Out-Null
    Copy-Item -Path $prepareCcConfigPath -Destination (Join-Path $BundleRoot 'bin/prepare_cc_config.py') -Force
}

$startScript = @'
#!/usr/bin/env sh
set -eu
bundle_dir="${1:?bundle directory is required}"
shift || true
java_bin="$bundle_dir/runtime/bin/java"
runtime_archive="$bundle_dir/runtime.tar.gz"
app_jar="$bundle_dir/app.jar"
relay_main="com.webank.wedatasphere.wdsavs.aiagent.remote.RemoteCcRelayServer"
loader_main="org.springframework.boot.loader.launch.PropertiesLauncher"
if [ ! -x "$java_bin" ]; then
  runtime_tmp="$bundle_dir/.runtime-extract.$$"
  rm -rf "$runtime_tmp"
  mkdir -p "$runtime_tmp"
  tar -xzf "$runtime_archive" -C "$runtime_tmp" --strip-components=1
  rm -rf "$bundle_dir/runtime"
  mv "$runtime_tmp" "$bundle_dir/runtime"
  java_bin="$bundle_dir/runtime/bin/java"
fi
exec "$java_bin" -Dloader.main="$relay_main" -cp "$app_jar" "$loader_main" "$@"
'@
$startScriptPath = Join-Path $BundleRoot 'bin/start.sh'
$startScript = $startScript -replace "`r`n", "`n"
[System.IO.File]::WriteAllText($startScriptPath, $startScript, [System.Text.UTF8Encoding]::new($false))

$manifest = [ordered]@{
    bundleRoot = $BundleRoot
    bootJar = $BootJarPath
    runtimeArchive = $runtimeArchivePath
    runtimeArchiveInBundle = (Join-Path $BundleRoot 'runtime.tar.gz')
    runtimeHome = $null
    windowsRuntimeArchiveInBundle = (Join-Path $BundleRoot 'runtime-windows.zip')
    windowsRuntimeHome = $null
    runtimeExtractionMode = 'ON_DEMAND_LOCAL_CACHE'
    pythonWindowsHome = (Join-Path $BundleRoot 'python-windows')
    pythonLinuxArchiveInBundle = (Join-Path $BundleRoot 'python-linux.tar.gz')
    pythonLinuxHome = '.local/runtime/python-linux-3.13.15'
    pythonVersion = '3.13.15'
    claudeCodeArchive = $claudeCodeArchive
    claudeCodeArchiveInBundle = (Join-Path $BundleRoot 'tools/claude-code-linux-x64.tgz')
    appJar = (Join-Path $BundleRoot 'app.jar')
    installRelayScript = (Join-Path $BundleRoot 'install-relay.sh')
    installRelayPowerShellScript = (Join-Path $BundleRoot 'install-relay.ps1')
    ccConfigTemplate = if (Test-Path (Join-Path $BundleRoot 'config/cc-model-config.template.yml')) { (Join-Path $BundleRoot 'config/cc-model-config.template.yml') } else { $null }
    relaySystemPrompt = if (Test-Path (Join-Path $BundleRoot 'config/relay-system-prompt.txt')) { (Join-Path $BundleRoot 'config/relay-system-prompt.txt') } else { $null }
    ccConfig = if (Test-Path (Join-Path $BundleRoot 'config/cc-model-config.yml')) { (Join-Path $BundleRoot 'config/cc-model-config.yml') } else { $null }
    modelConfigRequired = $true
    modelConfigSource = if ($ModelConfigPath) { 'LOCAL_PROTECTED_CONFIG' } else { 'NOT_PROVIDED' }
    prepareCcConfig = if (Test-Path (Join-Path $BundleRoot 'bin/prepare_cc_config.py')) { (Join-Path $BundleRoot 'bin/prepare_cc_config.py') } else { $null }
    relayCli = (Join-Path $BundleRoot 'bin/ccrelay-cli')
    relayCliWindows = (Join-Path $BundleRoot 'bin/ccrelay-cli.cmd')
    startScript = (Join-Path $BundleRoot 'bin/start.sh')
}
$manifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $BundleRoot 'bundle-manifest.json') -Encoding UTF8

[ordered]@{
    status = 'BUNDLE_READY'
    bundleRoot = $BundleRoot
    bootJar = $BootJarPath
    runtimeArchive = $runtimeArchivePath
    runtimeArchiveInBundle = (Join-Path $BundleRoot 'runtime.tar.gz')
    runtimeHome = $null
    windowsRuntimeArchiveInBundle = (Join-Path $BundleRoot 'runtime-windows.zip')
    windowsRuntimeHome = $null
    runtimeExtractionMode = 'ON_DEMAND_LOCAL_CACHE'
    pythonWindowsHome = (Join-Path $BundleRoot 'python-windows')
    pythonLinuxArchiveInBundle = (Join-Path $BundleRoot 'python-linux.tar.gz')
    pythonLinuxHome = '.local/runtime/python-linux-3.13.15'
    pythonVersion = '3.13.15'
    claudeCodeArchive = $claudeCodeArchive
    claudeCodeArchiveInBundle = (Join-Path $BundleRoot 'tools/claude-code-linux-x64.tgz')
    appJar = (Join-Path $BundleRoot 'app.jar')
    installRelayScript = (Join-Path $BundleRoot 'install-relay.sh')
    installRelayPowerShellScript = (Join-Path $BundleRoot 'install-relay.ps1')
    ccConfigTemplate = if (Test-Path (Join-Path $BundleRoot 'config/cc-model-config.template.yml')) { (Join-Path $BundleRoot 'config/cc-model-config.template.yml') } else { $null }
    relaySystemPrompt = if (Test-Path (Join-Path $BundleRoot 'config/relay-system-prompt.txt')) { (Join-Path $BundleRoot 'config/relay-system-prompt.txt') } else { $null }
    ccConfig = if (Test-Path (Join-Path $BundleRoot 'config/cc-model-config.yml')) { (Join-Path $BundleRoot 'config/cc-model-config.yml') } else { $null }
    modelConfigRequired = $true
    modelConfigSource = if ($ModelConfigPath) { 'LOCAL_PROTECTED_CONFIG' } else { 'NOT_PROVIDED' }
    prepareCcConfig = if (Test-Path (Join-Path $BundleRoot 'bin/prepare_cc_config.py')) { (Join-Path $BundleRoot 'bin/prepare_cc_config.py') } else { $null }
    relayCli = (Join-Path $BundleRoot 'bin/ccrelay-cli')
    relayCliWindows = (Join-Path $BundleRoot 'bin/ccrelay-cli.cmd')
    startScript = (Join-Path $BundleRoot 'bin/start.sh')
} | ConvertTo-Json -Depth 4

