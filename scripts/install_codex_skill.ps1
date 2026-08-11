param(
    [Parameter(Position = 0, Mandatory = $true)]
    [string]$SkillDir,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Arguments
)

$ErrorActionPreference = 'Stop'
$resolvedSkillDir = (Resolve-Path $SkillDir -ErrorAction Stop).Path
$bundledPython = Join-Path $resolvedSkillDir 'assets\runtime-bundle\ccrelay\python-windows\python.exe'
if (Test-Path -LiteralPath $bundledPython -PathType Leaf) {
    & $bundledPython (Join-Path $PSScriptRoot 'install_codex_skill.py') $resolvedSkillDir @Arguments
    exit $LASTEXITCODE
}

function Resolve-Python {
    $candidates = @(
        (Get-Command python -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -ErrorAction SilentlyContinue),
        (Get-Command python3 -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -ErrorAction SilentlyContinue),
        (Get-Command py -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -ErrorAction SilentlyContinue)
    ) | Where-Object { $_ -and (Test-Path $_) -and ($_ -notmatch 'WindowsApps') }

    if (-not $candidates -or $candidates.Count -eq 0) {
        throw 'No usable Python interpreter found.'
    }
    return [string]($candidates | Select-Object -First 1)
}

$python = Resolve-Python
& $python (Join-Path $PSScriptRoot 'install_codex_skill.py') $resolvedSkillDir @Arguments
exit $LASTEXITCODE
