param(
    [string]$ReleaseBaseUrl = '',
    [string]$GiteeApiBaseUrl = 'https://gitee.com/api/v5/repos/nekoneko-acg/ccrelay',
    [string]$GitHubReleaseBaseUrl = 'https://github.com/LastEmbryo2950618641/ccrelay/releases/latest/download'
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

function Download-File {
    param([string]$Url, [string]$Destination)
    $curl = Get-Command curl.exe -ErrorAction SilentlyContinue
    if ($curl) {
        for ($attempt = 1; $attempt -le 3; $attempt++) {
            Remove-Item -LiteralPath $Destination -Force -ErrorAction SilentlyContinue
            & $curl.Source --fail --location --silent --show-error --connect-timeout 20 --output $Destination $Url
            if ($LASTEXITCODE -eq 0) {
                return
            }
            if ($attempt -lt 3) {
                Start-Sleep -Seconds (2 * $attempt)
            }
        }
    }
    Remove-Item -LiteralPath $Destination -Force -ErrorAction SilentlyContinue
    try {
        Invoke-WebRequest -UseBasicParsing $Url -OutFile $Destination -TimeoutSec 1800
    } catch {
        throw "Download failed: $Url. $($_.Exception.Message)"
    }
}

function Download-Package {
    param([string]$BaseUrl)
    $downloadBase = $BaseUrl.TrimEnd('/')
    try {
        Download-File "$downloadBase/ccrelay-full.zip" $archivePath
        Download-File "$downloadBase/ccrelay-full.zip.sha256" $checksumPath
        $expected = ((Get-Content -Raw $checksumPath).Trim() -split '\s+')[0].ToLowerInvariant()
        $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $archivePath).Hash.ToLowerInvariant()
        if ([string]::IsNullOrWhiteSpace($expected) -or $expected -ne $actual) {
            throw 'SHA-256 verification failed'
        }
        Write-Output "CC Relay package source: $downloadBase"
        return $true
    } catch {
        Write-Warning "CC Relay package source failed: $downloadBase. $($_.Exception.Message)"
        Remove-Item -LiteralPath $archivePath, $checksumPath -Force -ErrorAction SilentlyContinue
        return $false
    }
}

function Download-GiteePackage {
    $apiBase = $GiteeApiBaseUrl.TrimEnd('/')
    $releaseMetadata = Join-Path $workRoot 'gitee-release.json'
    $attachmentMetadata = Join-Path $workRoot 'gitee-attachments.json'
    try {
        Download-File "$apiBase/releases/latest" $releaseMetadata
        $release = Get-Content -Raw $releaseMetadata | ConvertFrom-Json
        if (-not $release.id) {
            throw 'Gitee latest Release does not contain an id'
        }
        Download-File "$apiBase/releases/$($release.id)/attach_files" $attachmentMetadata
        $attachments = @(Get-Content -Raw $attachmentMetadata | ConvertFrom-Json)
        $archive = $attachments | Where-Object { $_.name -eq 'ccrelay-full.zip' } | Select-Object -First 1
        $checksum = $attachments | Where-Object { $_.name -eq 'ccrelay-full.zip.sha256' } | Select-Object -First 1
        if (-not $archive.id -or -not $checksum.id) {
            throw 'Gitee latest Release does not contain the complete package assets'
        }
        Download-File "$apiBase/releases/$($release.id)/attach_files/$($archive.id)/download" $archivePath
        Download-File "$apiBase/releases/$($release.id)/attach_files/$($checksum.id)/download" $checksumPath
        $expected = ((Get-Content -Raw $checksumPath).Trim() -split '\s+')[0].ToLowerInvariant()
        $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $archivePath).Hash.ToLowerInvariant()
        if ([string]::IsNullOrWhiteSpace($expected) -or $expected -ne $actual) {
            throw 'SHA-256 verification failed'
        }
        Write-Output "CC Relay package source: $apiBase/releases/latest"
        return $true
    } catch {
        Write-Warning "CC Relay Gitee source failed: $apiBase. $($_.Exception.Message)"
        Remove-Item -LiteralPath $archivePath, $checksumPath, $releaseMetadata, $attachmentMetadata -Force -ErrorAction SilentlyContinue
        return $false
    }
}

try {
    New-Item -ItemType Directory -Force -Path $workRoot, $extractRoot | Out-Null
    $downloaded = if ([string]::IsNullOrWhiteSpace($ReleaseBaseUrl)) {
        (Download-GiteePackage) -or (Download-Package $GitHubReleaseBaseUrl)
    } else {
        Download-Package $ReleaseBaseUrl
    }
    if (-not $downloaded) {
        throw 'Unable to download and verify the CC Relay complete package from any release source'
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
