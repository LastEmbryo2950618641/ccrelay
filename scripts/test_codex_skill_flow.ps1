$ErrorActionPreference = 'Stop'

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
& $python "$PSScriptRoot\test_codex_skill_flow.py" @args
