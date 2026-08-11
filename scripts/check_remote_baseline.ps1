param(
    [string]$CenterBaseUrl = 'http://127.0.0.1:19291',
    [string[]]$ExpectedNodeIds = @('47.93.195.246:18091', '111.229.32.85:18091', '47.93.195.246:18092'),
    [switch]$IncludeSshDetails
)

$ErrorActionPreference = 'Stop'

function Invoke-Json {
    param(
        [string]$Method,
        [string]$Uri,
        [object]$Body = $null
    )

    if ($null -eq $Body) {
        return Invoke-RestMethod -Method $Method -Uri $Uri
    }

    return Invoke-RestMethod -Method $Method -Uri $Uri -ContentType 'application/json' -Body ($Body | ConvertTo-Json -Depth 10)
}

function Get-NodeHost {
    param([string]$NodeId)
    if ([string]::IsNullOrWhiteSpace($NodeId)) { return $null }
    $index = $NodeId.LastIndexOf(':')
    if ($index -lt 0) { return $NodeId }
    return $NodeId.Substring(0, $index)
}

function Probe-Ssh {
    param(
        [string]$Host,
        [string]$Command
    )

    try {
        $output = & ssh -o StrictHostKeyChecking=no "liuqi@$Host" $Command 2>&1
        return [ordered]@{
            ok = $LASTEXITCODE -eq 0
            output = ($output -join "`n")
        }
    } catch {
        return [ordered]@{
            ok = $false
            output = $_.Exception.Message
        }
    }
}

$health = Invoke-Json -Method Get -Uri "$CenterBaseUrl/api/skill/health"
$scan = Invoke-Json -Method Get -Uri "$CenterBaseUrl/api/skill/relay/heartbeat/scan"
$rawNodes = Invoke-Json -Method Get -Uri "$CenterBaseUrl/api/skill/relay/nodes"
if ($rawNodes -is [System.Array]) {
    $nodes = @($rawNodes)
} elseif ($null -eq $rawNodes) {
    $nodes = @()
} else {
    $nodes = @($rawNodes)
}

$nodeMap = @{}
foreach ($node in $nodes) {
    $nodeMap[$node.nodeId] = $node
}

$missing = @()
$unexpected = @()
foreach ($expected in $ExpectedNodeIds) {
    if (-not $nodeMap.ContainsKey($expected)) {
        $missing += $expected
    }
}
foreach ($node in $nodes) {
    if ($ExpectedNodeIds -notcontains $node.nodeId) {
        $unexpected += $node.nodeId
    }
}

$allAvailable = $true
foreach ($expected in $ExpectedNodeIds) {
    if (-not $nodeMap.ContainsKey($expected) -or $nodeMap[$expected].status -ne 'AVAILABLE') {
        $allAvailable = $false
    }
}

$result = [ordered]@{
    checkedAt = (Get-Date).ToString('o')
    centerBaseUrl = $CenterBaseUrl
    centerHealth = $health
    heartbeatScan = $scan
    expectedNodeIds = $ExpectedNodeIds
    missingNodeIds = $missing
    unexpectedNodeIds = $unexpected
    allExpectedNodesAvailable = $allAvailable
    nodes = $nodes
}

if ($IncludeSshDetails) {
    $sshDetails = @()
    $seenHosts = @{}
    foreach ($expected in $ExpectedNodeIds) {
        $host = Get-NodeHost $expected
        if ([string]::IsNullOrWhiteSpace($host) -or $seenHosts.ContainsKey($host)) {
            continue
        }
        $seenHosts[$host] = $true
        $probe = Probe-Ssh -Host $host -Command '/usr/bin/hostname 2>/dev/null || true; /usr/bin/ps -ef | /usr/bin/grep -i RemoteCcRelayServer | /usr/bin/grep -v grep || true'
        $sshDetails += [ordered]@{
            host = $host
            ok = $probe.ok
            output = $probe.output
        }
    }
    $result['sshDetails'] = $sshDetails
}

$result | ConvertTo-Json -Depth 10
