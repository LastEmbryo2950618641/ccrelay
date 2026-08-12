param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$BundleDirectory,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$RelayArguments
)

$ErrorActionPreference = 'Stop'
$bundle = (Resolve-Path -LiteralPath $BundleDirectory).Path
$machinePath = [Environment]::GetEnvironmentVariable('Path', 'Machine')
$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
$env:Path = (@((Join-Path $bundle 'bin'), $machinePath, $userPath, $env:Path) | Where-Object { $_ }) -join [IO.Path]::PathSeparator
$java = Join-Path $bundle 'runtime-windows/bin/java.exe'
$appJar = Join-Path $bundle 'app.jar'
$collaborationCli = Join-Path $bundle 'bin/ccrelay-cli.cmd'
$runtimeArchive = Join-Path $bundle 'runtime-windows.zip'
$pidFile = if ($env:WDSAVS_CC_RELAY_PID_FILE) { $env:WDSAVS_CC_RELAY_PID_FILE } else { Join-Path $bundle 'relay.pid' }
$logFile = if ($env:WDSAVS_CC_RELAY_LOG_FILE) { $env:WDSAVS_CC_RELAY_LOG_FILE } else { Join-Path $bundle 'relay-startup.log' }
$errorFile = [IO.Path]::ChangeExtension($logFile, '.error.log')
$hmacSecretFile = if ($env:WDSAVS_AI_RELAY_HMAC_SECRET_FILE) { $env:WDSAVS_AI_RELAY_HMAC_SECRET_FILE } else { Join-Path $bundle 'config/relay-hmac-secret' }
$systemPromptFile = if ($env:WDSAVS_AI_RELAY_SYSTEM_PROMPT_FILE) { $env:WDSAVS_AI_RELAY_SYSTEM_PROMPT_FILE } else { Join-Path $bundle 'config/relay-system-prompt.txt' }
$skillDirectory = if ($env:CCRELAY_SKILL_DIR) { $env:CCRELAY_SKILL_DIR } else { Join-Path $bundle 'skills' }
$startupTimeout = if ($env:WDSAVS_CC_RELAY_STARTUP_TIMEOUT_SECONDS) { [int]$env:WDSAVS_CC_RELAY_STARTUP_TIMEOUT_SECONDS } else { 10 }
$preserveArchives = $env:WDSAVS_CC_RELAY_PRESERVE_ARCHIVES -eq 'true'
$relayPort = if ($env:WDSAVS_CC_RELAY_PORT) { [int]$env:WDSAVS_CC_RELAY_PORT } else { 18091 }
foreach ($argument in @($RelayArguments)) {
    if ($argument -match '^--(?:server\.port|wdsavs\.ai\.remote-cc\.relay\.port)=(\d+)$') {
        $relayPort = [int]$matches[1]
    }
}
$env:WDSAVS_CC_RELAY_PORT = [string]$relayPort
$env:CCRELAY_SKILL_DIR = $skillDirectory
New-Item -ItemType Directory -Path $skillDirectory -Force | Out-Null
if (Test-Path -LiteralPath $systemPromptFile -PathType Leaf) { $env:WDSAVS_AI_RELAY_SYSTEM_PROMPT_FILE = $systemPromptFile }

if (-not (Test-Path -LiteralPath $appJar -PathType Leaf)) {
    throw "Bundled application jar not found: $appJar"
}
if (-not (Test-Path -LiteralPath $collaborationCli -PathType Leaf)) {
    throw "Bundled Relay collaboration CLI not found: $collaborationCli"
}
if (Test-Path -LiteralPath $hmacSecretFile -PathType Leaf) {
    $hmacSecret = (Get-Content -LiteralPath $hmacSecretFile -Raw).Trim()
    if ($hmacSecret) { $env:WDSAVS_AI_HMAC_SECRET = $hmacSecret }
}
if (-not (Test-Path -LiteralPath $java -PathType Leaf)) {
    if (-not (Test-Path -LiteralPath $runtimeArchive -PathType Leaf)) {
        throw "Bundled Windows Java runtime not found: $java"
    }
    $runtime = Join-Path $bundle 'runtime-windows'
    $temporary = Join-Path $bundle ('.runtime-windows-' + [Guid]::NewGuid().ToString('N'))
    Expand-Archive -LiteralPath $runtimeArchive -DestinationPath $temporary -Force
    if (Test-Path -LiteralPath $runtime) { Remove-Item -LiteralPath $runtime -Recurse -Force }
    Move-Item -LiteralPath (Get-ChildItem -LiteralPath $temporary | Select-Object -First 1).FullName -Destination $runtime
    Remove-Item -LiteralPath $temporary -Recurse -Force -ErrorAction SilentlyContinue
    if (-not $preserveArchives) { Remove-Item -LiteralPath $runtimeArchive -Force -ErrorAction SilentlyContinue }
}

$configPath = if ($env:WDSAVS_CC_MODEL_CONFIG) { $env:WDSAVS_CC_MODEL_CONFIG } else { Join-Path $bundle 'config/cc-model-config.yml' }
$claudeConfigDirectory = Join-Path $bundle 'config/claude-runtime'
$claudeSettingsFile = Join-Path $claudeConfigDirectory 'settings.json'
New-Item -ItemType Directory -Path $claudeConfigDirectory -Force | Out-Null
Set-Content -LiteralPath $claudeSettingsFile -Value "{}`n" -Encoding utf8
$env:CLAUDE_CONFIG_DIR = $claudeConfigDirectory
$env:WDSAVS_CC_CLAUDE_SETTINGS_FILE = $claudeSettingsFile
$env:CCRELAY_CENTER_URL = if ($env:WDSAVS_AI_RELAY_REGISTER_ENDPOINT -match '^(.+)/api/skill/') { $matches[1] } else { $env:CCRELAY_CENTER_URL }
if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
    throw "Cluster model configuration was not delivered by the Skill host: $configPath"
}
@(
    'OPENAI_MODEL', 'OPENAI_BASE_URL', 'OPENAI_API_KEY',
    'ANTHROPIC_MODEL', 'ANTHROPIC_BASE_URL', 'ANTHROPIC_AUTH_TOKEN', 'ANTHROPIC_API_KEY',
    'ANTHROPIC_DEFAULT_OPUS_MODEL', 'ANTHROPIC_DEFAULT_SONNET_MODEL', 'ANTHROPIC_DEFAULT_HAIKU_MODEL',
    'CLAUDE_CODE_API_KEY', 'CLAUDE_CODE_SUBAGENT_MODEL', 'WDSAVS_CC_RELAY_COMMAND', 'WDSAVS_CC_RELAY_ARGS'
) | ForEach-Object { Remove-Item -LiteralPath "Env:$_" -ErrorAction SilentlyContinue }
$values = @{}
$relayArgs = [System.Collections.Generic.List[string]]::new()
$inRelayArgs = $false
foreach ($line in Get-Content -LiteralPath $configPath) {
    if ($line -match '^\s*(model|baseUrl|apiKey|relayCommand):\s*["'']?(.*?)["'']?\s*$') {
        $values[$matches[1]] = $matches[2]
        $inRelayArgs = $false
    } elseif ($line -match '^\s*relayArgs:\s*$') {
        $inRelayArgs = $true
    } elseif ($inRelayArgs -and $line -match '^\s*-\s*["'']?(.*?)["'']?\s*$') {
        $relayArgs.Add($matches[1])
    } elseif ($line -match '^\S') {
        $inRelayArgs = $false
    }
}
if (-not $values.model -or -not $values.baseUrl -or -not $values.apiKey) {
    throw 'Cluster model configuration is incomplete: model, baseUrl and apiKey are required'
}
$env:OPENAI_MODEL = $values.model
$env:OPENAI_BASE_URL = $values.baseUrl
$env:OPENAI_API_KEY = $values.apiKey
$env:ANTHROPIC_MODEL = $values.model
$env:ANTHROPIC_BASE_URL = $values.baseUrl
$env:ANTHROPIC_AUTH_TOKEN = $values.apiKey
$env:ANTHROPIC_API_KEY = $values.apiKey
$env:CLAUDE_CODE_API_KEY = $values.apiKey
$env:ANTHROPIC_DEFAULT_OPUS_MODEL = $values.model
$env:ANTHROPIC_DEFAULT_SONNET_MODEL = $values.model
$env:ANTHROPIC_DEFAULT_HAIKU_MODEL = $values.model
$env:CLAUDE_CODE_SUBAGENT_MODEL = $values.model
if ($values.relayCommand) { $env:WDSAVS_CC_RELAY_COMMAND = $values.relayCommand }
if ($values.relayCommand -eq 'claude') {
    $bundledClaude = Join-Path $bundle 'bin/claude.exe'
    if (Test-Path -LiteralPath $bundledClaude -PathType Leaf) { $env:WDSAVS_CC_RELAY_COMMAND = $bundledClaude }
}
if ($relayArgs.Count -gt 0) {
    $env:WDSAVS_CC_RELAY_ARGS = $relayArgs -join ' '
} elseif ($values.relayCommand -eq 'claude') {
    $env:WDSAVS_CC_RELAY_ARGS = "--bare --print --model $($values.model) --effort low"
}

if (Test-Path -LiteralPath $pidFile) {
    $oldPid = Get-Content -LiteralPath $pidFile -ErrorAction SilentlyContinue
    if ($oldPid) { Stop-Process -Id $oldPid -Force -ErrorAction SilentlyContinue }
}

$arguments = @(
    '-Dloader.main=com.webank.wedatasphere.wdsavs.aiagent.remote.RemoteCcRelayServer',
    '-cp',
    $appJar,
    'org.springframework.boot.loader.launch.PropertiesLauncher'
) + @($RelayArguments)
$process = Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $bundle `
    -RedirectStandardOutput $logFile -RedirectStandardError $errorFile -WindowStyle Hidden -PassThru
Set-Content -LiteralPath $pidFile -Value $process.Id

& $java '-Dloader.main=com.webank.wedatasphere.wdsavs.aiagent.remote.RelayHealthProbe' '-cp' $appJar `
    'org.springframework.boot.loader.launch.PropertiesLauncher' "http://127.0.0.1:$relayPort/health" ([string]$startupTimeout)
if ($LASTEXITCODE -ne 0) {
    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    $tail = if (Test-Path -LiteralPath $errorFile) { Get-Content -LiteralPath $errorFile -Tail 80 } else { @() }
    throw "Relay health check failed during startup. $($tail -join [Environment]::NewLine)"
}
& $java '-Dloader.main=com.webank.wedatasphere.wdsavs.aiagent.remote.RelayHealthProbe' '-cp' $appJar `
    'org.springframework.boot.loader.launch.PropertiesLauncher' "http://127.0.0.1:$relayPort/ai-readiness" ([string]$startupTimeout) 'READY'
if ($LASTEXITCODE -ne 0) {
    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    $tail = if (Test-Path -LiteralPath $errorFile) { Get-Content -LiteralPath $errorFile -Tail 80 } else { @() }
    throw "Relay AI readiness check failed: model configuration is missing or incomplete. $($tail -join [Environment]::NewLine)"
}
if (-not (Get-Process -Id $process.Id -ErrorAction SilentlyContinue)) {
    $tail = if (Test-Path -LiteralPath $errorFile) { Get-Content -LiteralPath $errorFile -Tail 80 } else { @() }
    throw "Relay process exited during startup. $($tail -join [Environment]::NewLine)"
}

Write-Output ('CCRELAY_RELAY_STARTED|pid=' + $process.Id + '|port=' + $relayPort)
