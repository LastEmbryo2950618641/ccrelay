param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Arguments
)

$ErrorActionPreference = 'Stop'
$skillRoot = (Resolve-Path (Join-Path $PSScriptRoot '..') -ErrorAction Stop).Path
$scriptPath = Join-Path $PSScriptRoot 'prepare_cc_config.py'
$env:PYTHONIOENCODING = 'utf-8'
$bundledPython = Join-Path $skillRoot 'assets\runtime-bundle\ccrelay\python-windows\python.exe'
if (-not (Test-Path -LiteralPath $bundledPython -PathType Leaf)) {
    throw "Bundled Python runtime is missing: $bundledPython"
}
& $bundledPython $scriptPath @Arguments
exit $LASTEXITCODE
