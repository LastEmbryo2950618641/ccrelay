param(
    [string]$ConfigPath = (Join-Path $PSScriptRoot 'remote_acceptance_config.json')
)

$ErrorActionPreference = 'Stop'

function Require-Command {
    param([string]$Name)
    $cmd = Get-Command $Name -ErrorAction SilentlyContinue
    if (-not $cmd) {
        throw "Required command not found: $Name"
    }
    return $cmd.Source
}

function Require-Property {
    param(
        [object]$Object,
        [string]$Name
    )
    $value = $Object.$Name
    if ($null -eq $value -or [string]::IsNullOrWhiteSpace([string]$value)) {
        throw "Missing required property: $Name"
    }
    return $value
}

function Resolve-RemoteDirectory {
    param(
        [object]$Object,
        [string]$TargetUser
    )

    $value = if ($Object.PSObject.Properties.Name -contains 'remoteDirectory') { $Object.remoteDirectory } else { $null }
    if ($null -ne $value -and -not [string]::IsNullOrWhiteSpace([string]$value)) {
        return [string]$value
    }

    return "/home/$TargetUser/ccrelay"
}

if (-not (Test-Path $ConfigPath)) {
    throw "Config file not found: $ConfigPath"
}

$config = Get-Content $ConfigPath -Raw | ConvertFrom-Json

$ssh = Require-Command 'ssh'
$scp = Require-Command 'scp'

$centerBaseUrl = Require-Property $config 'centerBaseUrl'
$sourceNodeId = Require-Property $config 'sourceNodeId'
$sourceRelayEndpoint = Require-Property $config 'sourceRelayEndpoint'
$sourceHost = Require-Property $config 'sourceHost'
$sourceUser = Require-Property $config 'sourceUser'
$targetNodeId = Require-Property $config 'targetNodeId'
$targetHost = Require-Property $config 'targetHost'
$targetUser = Require-Property $config 'targetUser'
$scriptPath = Require-Property $config 'scriptPath'
$artifactPath = Require-Property $config 'artifactPath'
$remoteDirectory = Resolve-RemoteDirectory $config $targetUser

$skillPath = 'C:\Users\liuqi\.codex\skills\ccrelay'
if (-not (Test-Path $skillPath)) {
    throw "Installed skill not found: $skillPath"
}

$result = [ordered]@{
    status = 'READY_FOR_REMOTE_EXECUTION'
    ssh = $ssh
    scp = $scp
    configPath = (Resolve-Path $ConfigPath).Path
    skillPath = $skillPath
    centerBaseUrl = $centerBaseUrl
    sourceNodeId = $sourceNodeId
    sourceRelayEndpoint = $sourceRelayEndpoint
    sourceSshTarget = "$sourceUser@$sourceHost"
    targetNodeId = $targetNodeId
    targetSshTarget = "$targetUser@$targetHost"
    scriptPath = $scriptPath
    artifactPath = $artifactPath
    remoteDirectory = $remoteDirectory
    remoteDirectorySource = if ($config.PSObject.Properties.Name -contains 'remoteDirectory' -and -not [string]::IsNullOrWhiteSpace([string]$config.remoteDirectory)) { 'config' } else { 'default:/home/<targetUser>/ccrelay' }
    note = 'Preflight passed locally. Next step requires actual SSH connectivity to source/target hosts.'
}

$result | ConvertTo-Json -Depth 4
