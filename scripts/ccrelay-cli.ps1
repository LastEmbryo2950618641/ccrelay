param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Arguments
)

$ErrorActionPreference = 'Stop'
$skillRootCandidates = @(
    (Join-Path $PSScriptRoot '..\codex-skill\ccrelay'),
    (Join-Path $PSScriptRoot '..\ccrelay')
) | ForEach-Object { try { (Resolve-Path $_ -ErrorAction Stop).Path } catch { $null } } | Where-Object { $_ }
$skillRoot = $skillRootCandidates | Select-Object -First 1
if (-not $skillRoot) { throw 'ccrelay skill directory was not found.' }
$scriptPath = Join-Path $skillRoot 'scripts\ccrelay-cli.py'
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
