param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Arguments
)

$ErrorActionPreference = 'Stop'
$scriptPath = Join-Path $PSScriptRoot 'ccrelay-cli.py'
$skillRoot = (Resolve-Path (Join-Path $PSScriptRoot '..') -ErrorAction Stop).Path
$env:PYTHONIOENCODING = 'utf-8'

$bundledPython = Join-Path $skillRoot 'assets\runtime-bundle\ccrelay\python-windows\python.exe'
if (Test-Path -LiteralPath $bundledPython -PathType Leaf) {
    & $bundledPython $scriptPath @Arguments
    exit $LASTEXITCODE
}

$externalPython = @(
    (Get-Command python -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -ErrorAction SilentlyContinue),
    (Get-Command python3 -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -ErrorAction SilentlyContinue),
    (Get-Command py -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -ErrorAction SilentlyContinue)
) | Where-Object { $_ -and (Test-Path $_) -and ($_ -notmatch 'WindowsApps') } | Select-Object -First 1
if (-not $externalPython) {
    throw "Bundled Python runtime is missing: $(Join-Path $skillRoot 'assets\runtime-bundle\ccrelay\python-windows\python.exe')"
}
& $externalPython $scriptPath @Arguments
exit $LASTEXITCODE
