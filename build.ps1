param(
    [string]$OutRoot = (Join-Path $PSScriptRoot 'dist/skill'),
    [string]$ReleaseRoot = (Join-Path $PSScriptRoot 'dist/release')
)

$ErrorActionPreference = 'Stop'

$skillName = 'ccrelay'
$srcDir = Join-Path $PSScriptRoot "codex-skill/$skillName"
$packageDir = Join-Path $OutRoot $skillName
$tmpDir = Join-Path $OutRoot ".tmp-$skillName"

function Fail {
    param([string]$Message)
    throw "[ERROR] $Message"
}

function Log {
    param([string]$Message)
    Write-Host "[INFO] $Message"
}

if (-not (Test-Path $srcDir -PathType Container)) {
    Fail "Skill source directory not found: $srcDir"
}

$skillMd = Join-Path $srcDir 'SKILL.md'
if (-not (Test-Path $skillMd -PathType Leaf)) {
    Fail "SKILL.md not found: $skillMd"
}

$nameLine = Select-String -Path $skillMd -Pattern '^name:' | Select-Object -First 1
if (-not $nameLine) {
    Fail "Failed to parse skill name from $skillMd"
}

$skillNameFromMd = ($nameLine.Line -replace '^name:\s*', '').Trim()
if ([string]::IsNullOrWhiteSpace($skillNameFromMd)) {
    Fail "Failed to parse skill name from $skillMd"
}

if ($skillNameFromMd -ne $skillName) {
    Fail "Skill name mismatch: expected $skillName but found $skillNameFromMd"
}

New-Item -ItemType Directory -Force -Path $OutRoot | Out-Null
Remove-Item -Recurse -Force $tmpDir, $packageDir -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null

Copy-Item -Recurse -Force (Join-Path $srcDir '*') $tmpDir

foreach ($relativePath in @('.local', 'assets/runtime-bundle')) {
    $generatedPath = Join-Path $tmpDir $relativePath
    if (Test-Path $generatedPath) {
        Remove-Item -Recurse -Force $generatedPath
    }
}
Get-ChildItem -Path $tmpDir -Recurse -Force -Directory -Filter '__pycache__' -ErrorAction SilentlyContinue |
    Sort-Object FullName -Descending |
    Remove-Item -Recurse -Force
Get-ChildItem -Path $tmpDir -Recurse -Force -File -Filter '*.pyc' -ErrorAction SilentlyContinue |
    Remove-Item -Force

if (-not (Test-Path (Join-Path $tmpDir 'SKILL.md') -PathType Leaf)) {
    Fail 'Packaged SKILL.md missing'
}

$runtimeBundleDir = Join-Path $tmpDir "assets/runtime-bundle/$skillName"
& (Join-Path $PSScriptRoot 'scripts/build_runtime_bundle.ps1') -BundleRoot $runtimeBundleDir
if (-not (Test-Path (Join-Path $runtimeBundleDir 'app.jar') -PathType Leaf)) {
    Fail "Runtime app.jar missing: $runtimeBundleDir"
}
if (-not (Test-Path (Join-Path $runtimeBundleDir 'bin/start.sh') -PathType Leaf)) {
    Fail "Runtime start.sh missing: $runtimeBundleDir"
}
if (-not (Test-Path (Join-Path $runtimeBundleDir 'bin/ccrelay-cli') -PathType Leaf)) {
    Fail "Runtime Linux collaboration CLI missing: $runtimeBundleDir"
}
if (-not (Test-Path (Join-Path $runtimeBundleDir 'bin/ccrelay-cli.cmd') -PathType Leaf)) {
    Fail "Runtime Windows collaboration CLI missing: $runtimeBundleDir"
}
if (-not (Test-Path (Join-Path $runtimeBundleDir 'runtime.tar.gz') -PathType Leaf)) {
    Fail "Bundled Linux Java 21 archive missing: $runtimeBundleDir"
}
if (-not (Test-Path (Join-Path $runtimeBundleDir 'runtime-windows.zip') -PathType Leaf)) {
    Fail "Bundled Windows Java 21 archive missing: $runtimeBundleDir"
}
if ((Test-Path (Join-Path $runtimeBundleDir 'runtime')) -or (Test-Path (Join-Path $runtimeBundleDir 'runtime-windows'))) {
    Fail "Runtime bundle must not duplicate extracted JRE directories: $runtimeBundleDir"
}
if (-not (Test-Path (Join-Path $runtimeBundleDir 'python-windows/python.exe') -PathType Leaf)) {
    Fail "Bundled Windows Python 3.13 missing: $runtimeBundleDir"
}
if (-not (Test-Path (Join-Path $runtimeBundleDir 'python-linux.tar.gz') -PathType Leaf)) {
    Fail "Bundled Linux Python 3.13 missing: $runtimeBundleDir"
}

Move-Item -Force $tmpDir $packageDir

$bundledPython = Join-Path $packageDir 'assets/runtime-bundle/ccrelay/python-windows/python.exe'
if (-not (Test-Path $bundledPython -PathType Leaf)) {
    Fail "Bundled Python required for release packaging: $bundledPython"
}
& $bundledPython (Join-Path $PSScriptRoot 'scripts/package_skill_release.py') `
    --full-skill-dir $packageDir `
    --bootstrap-skill-dir (Join-Path $PSScriptRoot 'bootstrap-skill/ccrelay') `
    --release-dir $ReleaseRoot `
    --build-file (Join-Path $PSScriptRoot 'build.gradle')
if ($LASTEXITCODE -ne 0) {
    Fail 'Unable to create release ZIP packages'
}

Log 'Standard runnable skill directory created'
Log "Output: $packageDir"
Log "Runtime bundle: $(Join-Path $packageDir "assets/runtime-bundle/$skillName")"
Log "Complete ZIP: $(Join-Path $ReleaseRoot 'ccrelay-full.zip')"
Log "Bootstrap ZIP: $(Join-Path $ReleaseRoot 'ccrelay-bootstrap.zip')"
Log "Install command: powershell -ExecutionPolicy Bypass -File scripts/install_codex_skill.ps1 $packageDir --force"
