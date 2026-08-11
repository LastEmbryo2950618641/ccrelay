$ErrorActionPreference = 'Stop'
$env:PYTHONUTF8 = '1'

function Resolve-Python {
    $candidates = @(
        'C:\Users\liuqi\AppData\Local\Programs\Python\Python313\python.exe',
        (Get-Command python -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -ErrorAction SilentlyContinue),
        (Get-Command py -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -ErrorAction SilentlyContinue)
    ) | Where-Object { $_ -and (Test-Path $_) -and ($_ -notmatch 'WindowsApps') }

    if (-not $candidates -or $candidates.Count -eq 0) {
        throw 'No usable Python interpreter found.'
    }
    return $candidates[0]
}

$python = Resolve-Python
$skillDir = Join-Path $PSScriptRoot '..\codex-skill\ccrelay'
$validator = 'C:\Users\liuqi\.codex\skills\.system\skill-creator\scripts\quick_validate.py'
& $python $validator $skillDir
