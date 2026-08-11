param(
    [string]$OutRoot = (Join-Path $PSScriptRoot 'dist\release')
)

$ErrorActionPreference = 'Stop'

& (Join-Path $PSScriptRoot 'build.ps1') $OutRoot
