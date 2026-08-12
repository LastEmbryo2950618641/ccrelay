param(
    [string]$ReleaseBaseUrl = 'https://github.com/LastEmbryo2950618641/ccrelay/releases/latest/download'
)

$ErrorActionPreference = 'Stop'
$skillRoot = Split-Path $PSScriptRoot -Parent
$installRoot = Split-Path $skillRoot -Parent
$operationId = [Guid]::NewGuid().ToString('N')
$workRoot = Join-Path $installRoot ".ccrelay-download-$operationId"
$backupRoot = Join-Path $installRoot ".ccrelay-bootstrap-$operationId"
$archivePath = Join-Path $workRoot 'ccrelay-full.zip'
$checksumPath = Join-Path $workRoot 'ccrelay-full.zip.sha256'
$extractRoot = Join-Path $workRoot 'extracted'
$downloadBase = $ReleaseBaseUrl.TrimEnd('/')

function Download-File {
    param([string]$Url, [string]$Destination)
    $curl = Get-Command curl.exe -ErrorAction SilentlyContinue
    if ($curl) {
        & $curl.Source --fail --location --silent --show-error --output $Destination $Url
        if ($LASTEXITCODE -ne 0) {
            throw "Download failed: $Url"
        }
        return
    }
    Invoke-WebRequest -UseBasicParsing $Url -OutFile $Destination
}

try {
    New-Item -ItemType Directory -Force -Path $workRoot, $extractRoot | Out-Null
    Download-File "$downloadBase/ccrelay-full.zip" $archivePath
    Download-File "$downloadBase/ccrelay-full.zip.sha256" $checksumPath

    $expected = ((Get-Content -Raw $checksumPath).Trim() -split '\s+')[0].ToLowerInvariant()
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $archivePath).Hash.ToLowerInvariant()
    if ([string]::IsNullOrWhiteSpace($expected) -or $expected -ne $actual) {
        throw "SHA-256 verification failed for ccrelay-full.zip"
    }

    Expand-Archive -LiteralPath $archivePath -DestinationPath $extractRoot -Force
    $completeSkill = Join-Path $extractRoot 'ccrelay'
    if (-not (Test-Path (Join-Path $completeSkill 'SKILL.md') -PathType Leaf) -or
        -not (Test-Path (Join-Path $completeSkill 'assets\runtime-bundle\ccrelay\app.jar') -PathType Leaf)) {
        throw 'Downloaded archive is not a complete CC Relay Skill package'
    }

    $localState = Join-Path $skillRoot '.local'
    if (Test-Path $localState -PathType Container) {
        Copy-Item -Recurse -Force $localState (Join-Path $completeSkill '.local')
    }

    Set-Location $installRoot
    Move-Item -LiteralPath $skillRoot -Destination $backupRoot
    try {
        Move-Item -LiteralPath $completeSkill -Destination $skillRoot
    } catch {
        Move-Item -LiteralPath $backupRoot -Destination $skillRoot
        throw
    }
    Remove-Item -LiteralPath $backupRoot -Recurse -Force -ErrorAction SilentlyContinue
    Write-Output "CC Relay complete Skill installed: $skillRoot"
} finally {
    Remove-Item -LiteralPath $workRoot -Recurse -Force -ErrorAction SilentlyContinue
}
