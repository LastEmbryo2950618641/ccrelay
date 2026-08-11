param(
    [string]$CenterBaseUrl = 'http://127.0.0.1:19291',
    [string]$SourceNodeId = '111.229.32.85:18091',
    [string]$TargetNodeId = '47.93.195.246:18091',
    [string]$TargetRelayEndpoint = 'http://47.93.195.246:18091/api/ai/remote-cc/chat',
    [string]$CenterGrantValidateEndpoint = 'http://47.93.195.246:29292/api/skill/relay/access/validate',
    [string]$InitiatorType = 'CODEX',
    [string]$InitiatorId = 'codex-desktop',
    [string]$Prompt = 'final acceptance verification',
    [int]$TtlMs = 600000
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

$scan = Invoke-Json -Method Get -Uri "$CenterBaseUrl/api/skill/relay/heartbeat/scan"

$session = Invoke-Json -Method Post -Uri "$CenterBaseUrl/api/skill/session/open" -Body ([ordered]@{
    initiatorType = $InitiatorType
    initiatorId = $InitiatorId
    sourceNodeId = $SourceNodeId
})

$requestId = 'accept-' + [guid]::NewGuid().ToString('N')
$grant = Invoke-Json -Method Post -Uri "$CenterBaseUrl/api/skill/relay/access/request" -Body ([ordered]@{
    sessionId = $session.sessionId
    requestId = $requestId
    sourceNodeId = $SourceNodeId
    targetNodeId = $TargetNodeId
    reason = 'go-live acceptance verification'
    requiredCapabilities = @('A2A_MESSAGE_SEND', 'A2A_TASK_CREATE', 'A2A_TASK_GET', 'A2A_TASK_CANCEL')
    ttlMs = $TtlMs
    traceId = 'acceptance-' + [guid]::NewGuid().ToString('N')
})

if ($grant.decision -ne 'ALLOW') {
    throw "Grant decision is not ALLOW: $($grant.decision)"
}

$a2a = Invoke-Json -Method Post -Uri "$CenterBaseUrl/api/skill/a2a/message/send" -Body ([ordered]@{
    jsonrpc = '2.0'
    id = 'rpc-' + [guid]::NewGuid().ToString('N')
    method = 'message/send'
    params = [ordered]@{
        sessionId = $session.sessionId
        grantId = $grant.grantId
        signedToken = $grant.signedToken
        sourceNodeId = $SourceNodeId
        targetNodeId = $TargetNodeId
        targetRelayEndpoint = $TargetRelayEndpoint
        centerGrantValidateEndpoint = $CenterGrantValidateEndpoint
        messages = @([ordered]@{
            role = 'user'
            content = $Prompt
        })
    }
})

if ($null -ne $a2a.error) {
    throw "A2A error: $($a2a.error.message)"
}
if ($a2a.result.status -ne 'SUCCESS') {
    throw "A2A status is not SUCCESS: $($a2a.result.status)"
}
if (-not [string]::Join('', @($a2a.result.answer)).Contains('REMOTE_MOCK_RESPONSE')) {
    throw 'A2A answer does not contain REMOTE_MOCK_RESPONSE'
}

[ordered]@{
    checkedAt = (Get-Date).ToString('o')
    centerBaseUrl = $CenterBaseUrl
    heartbeatScan = $scan
    sessionId = $session.sessionId
    grantId = $grant.grantId
    grantDecision = $grant.decision
    targetRelayEndpoint = $grant.targetRelayEndpoint
    a2aStatus = $a2a.result.status
    a2aRequestId = $a2a.result.requestId
    a2aTraceId = $a2a.result.traceId
    answerContainsRemoteMockResponse = $true
} | ConvertTo-Json -Depth 10
