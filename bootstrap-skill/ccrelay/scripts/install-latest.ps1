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
$volumeRoot = Join-Path $workRoot 'gitee-volumes'
$recoveryRoot = Join-Path $workRoot 'gitee-recovered'

function Download-File {
    param(
        [string]$Url,
        [string]$Destination,
        [string]$DisplayName = (Split-Path $Destination -Leaf)
    )
    $curl = Get-Command curl.exe -ErrorAction SilentlyContinue
    if ($curl) {
        for ($attempt = 1; $attempt -le 3; $attempt++) {
            Remove-Item -LiteralPath $Destination -Force -ErrorAction SilentlyContinue
            Write-Host "Downloading $DisplayName (attempt $attempt/3)"
            & $curl.Source --fail --location --progress-bar --show-error --connect-timeout 20 --output $Destination $Url
            if ($LASTEXITCODE -eq 0) {
                $size = (Get-Item -LiteralPath $Destination).Length
                Write-Host ('Downloaded {0}: {1:N1} MiB' -f $DisplayName, ($size / 1MB))
                return
            }
            if ($attempt -lt 3) {
                Start-Sleep -Seconds (2 * $attempt)
            }
        }
    }
    Remove-Item -LiteralPath $Destination -Force -ErrorAction SilentlyContinue
    try {
        Write-Host "Downloading $DisplayName with Invoke-WebRequest"
        Invoke-WebRequest -UseBasicParsing $Url -OutFile $Destination -TimeoutSec 1800
        $size = (Get-Item -LiteralPath $Destination).Length
        Write-Host ('Downloaded {0}: {1:N1} MiB' -f $DisplayName, ($size / 1MB))
    } catch {
        throw "Download failed: $Url. $($_.Exception.Message)"
    }
}

function Clear-DownloadedPackage {
    Remove-Item -LiteralPath $archivePath, $checksumPath -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $volumeRoot, $recoveryRoot -Recurse -Force -ErrorAction SilentlyContinue
}

function Test-PackageChecksum {
    $expected = ((Get-Content -Raw $checksumPath).Trim() -split '\s+')[0].ToLowerInvariant()
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $archivePath).Hash.ToLowerInvariant()
    if ([string]::IsNullOrWhiteSpace($expected) -or $expected -ne $actual) {
        throw 'SHA-256 verification failed'
    }
}

function Get-SevenZipCommand {
    $bundled = Join-Path $skillRoot 'assets\7zip\windows-x64\7za.exe'
    $windowsArchitecture = if ($env:PROCESSOR_ARCHITEW6432) { $env:PROCESSOR_ARCHITEW6432 } else { $env:PROCESSOR_ARCHITECTURE }
    if ($windowsArchitecture -eq 'AMD64' -and (Test-Path $bundled -PathType Leaf)) {
        return $bundled
    }
    foreach ($name in @('7z.exe', '7zz.exe', '7z', '7zz')) {
        $command = Get-Command $name -ErrorAction SilentlyContinue
        if ($command) {
            return $command.Source
        }
    }
    throw 'No compatible bundled or system 7-Zip command is available'
}

function Assert-ContinuousVolumes {
    param([object[]]$Volumes)
    if (-not $Volumes -or $Volumes.Count -eq 0) {
        throw 'Gitee latest Release does not contain ccrelay-full.zip.001 split assets'
    }
    $expected = 1
    foreach ($volume in $Volumes) {
        if ($volume.Number -ne $expected) {
            throw ('Gitee split package is incomplete: expected .{0:d3}, found .{1:d3}' -f $expected, $volume.Number)
        }
        $expected++
    }
}

function Expand-GiteeSplitArchive {
    param([string]$FirstVolume)
    $sevenZip = Get-SevenZipCommand
    Write-Host 'Restoring the complete package from Gitee split volumes'
    Remove-Item -LiteralPath $recoveryRoot -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $recoveryRoot | Out-Null
    & $sevenZip x -tSplit -y "-o$recoveryRoot" $FirstVolume | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "7-Zip failed to restore the Gitee split package (exit code $LASTEXITCODE)"
    }
    $restored = Join-Path $recoveryRoot 'ccrelay-full.zip'
    if (-not (Test-Path $restored -PathType Leaf)) {
        throw 'Gitee split package did not restore the outer ZIP'
    }
    return $restored
}

function Expand-GiteeOuterArchive {
    param([string]$OuterArchive)
    Write-Host 'Extracting the restored Gitee package'
    $entryName = Get-GiteeOuterArchiveEntry $OuterArchive
    $innerRoot = Join-Path $recoveryRoot 'inner'
    New-Item -ItemType Directory -Force -Path $innerRoot | Out-Null
    Expand-Archive -LiteralPath $OuterArchive -DestinationPath $innerRoot -Force
    $innerArchive = Join-Path $innerRoot $entryName
    if (-not (Test-Path $innerArchive -PathType Leaf)) {
        throw 'Gitee outer ZIP does not contain ccrelay-full.zip'
    }
    Move-Item -LiteralPath $innerArchive -Destination $archivePath -Force
}

function Get-GiteeOuterArchiveEntry {
    param([string]$OuterArchive)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $package = [System.IO.Compression.ZipFile]::OpenRead($OuterArchive)
    try {
        $entries = @($package.Entries)
        if ($entries.Count -ne 1 -or $entries[0].FullName -ne 'ccrelay-full.zip') {
            throw 'Gitee outer ZIP must contain only ccrelay-full.zip at its root'
        }
        return $entries[0].FullName
    } finally {
        $package.Dispose()
    }
}

function Download-Package {
    param([string]$BaseUrl)
    $downloadBase = $BaseUrl.TrimEnd('/')
    try {
        Download-File "$downloadBase/ccrelay-full.zip" $archivePath 'ccrelay-full.zip'
        Download-File "$downloadBase/ccrelay-full.zip.sha256" $checksumPath 'ccrelay-full.zip.sha256'
        Write-Host 'Verifying CC Relay package SHA-256'
        Test-PackageChecksum
        Write-Output "CC Relay package source: $downloadBase"
        return $true
    } catch {
        Write-Warning "CC Relay package source failed: $downloadBase. $($_.Exception.Message)"
        Clear-DownloadedPackage
        return $false
    }
}

function Download-GiteePackage {
    $apiBase = $GiteeApiBaseUrl.TrimEnd('/')
    $releaseMetadata = Join-Path $workRoot 'gitee-release.json'
    $attachmentMetadata = Join-Path $workRoot 'gitee-attachments.json'
    try {
        Download-File "$apiBase/releases/latest" $releaseMetadata 'Gitee release metadata'
        $release = Get-Content -Raw $releaseMetadata | ConvertFrom-Json
        if (-not $release.id) {
            throw 'Gitee latest Release does not contain an id'
        }
        Download-File "$apiBase/releases/$($release.id)/attach_files" $attachmentMetadata 'Gitee attachment metadata'
        $attachments = @(Get-Content -Raw $attachmentMetadata | ConvertFrom-Json)
        $checksum = $attachments | Where-Object { $_.name -eq 'ccrelay-full.zip.sha256' } | Select-Object -First 1
        if (-not $checksum.id) {
            throw 'Gitee latest Release does not contain ccrelay-full.zip.sha256'
        }
        $volumes = @($attachments | ForEach-Object {
            if ($_.name -match '^ccrelay-full\.zip\.(\d{3})$') {
                [pscustomobject]@{ Number = [int]$Matches[1]; Name = $_.name; Id = $_.id }
            }
        } | Sort-Object Number)
        Assert-ContinuousVolumes $volumes
        New-Item -ItemType Directory -Force -Path $volumeRoot | Out-Null
        $volumeIndex = 0
        foreach ($volume in $volumes) {
            $volumeIndex++
            if (-not $volume.Id) {
                throw "Gitee split asset has no id: $($volume.Name)"
            }
            Download-File "$apiBase/releases/$($release.id)/attach_files/$($volume.Id)/download" (Join-Path $volumeRoot $volume.Name) "$($volume.Name) [$volumeIndex/$($volumes.Count)]"
        }
        Download-File "$apiBase/releases/$($release.id)/attach_files/$($checksum.id)/download" $checksumPath 'ccrelay-full.zip.sha256'
        $outerArchive = Expand-GiteeSplitArchive (Join-Path $volumeRoot 'ccrelay-full.zip.001')
        Expand-GiteeOuterArchive $outerArchive
        Write-Host 'Verifying CC Relay package SHA-256'
        Test-PackageChecksum
        Write-Output "CC Relay package source: $apiBase/releases/latest"
        return $true
    } catch {
        Write-Warning "CC Relay Gitee source failed: $apiBase. $($_.Exception.Message)"
        Clear-DownloadedPackage
        Remove-Item -LiteralPath $releaseMetadata, $attachmentMetadata -Force -ErrorAction SilentlyContinue
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

    Write-Host 'Extracting the complete CC Relay Skill'
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
