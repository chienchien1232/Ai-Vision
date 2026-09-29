param(
    [ValidateSet('Start', 'Restart', 'ConfigurePhone', 'Status', 'Probe', 'SetGeminiKey')][string]$Action = 'Start',
    [string]$Device = '',
    [string]$Adb = 'C:/Users/Admin/AppData/Local/Android/Sdk/platform-tools/adb.exe'
)
$ErrorActionPreference = 'Stop'
$stateDir = Join-Path $PSScriptRoot '.local-dev'
$tokenPath = Join-Path $stateDir 'app-token.dpapi'
$geminiKeyPath = Join-Path $stateDir 'gemini-key.dpapi'
$backendPidPath = Join-Path $stateDir 'backend.pid'

function Read-ProtectedValue([string]$SecretPath) {
    $secureToken = (Get-Content -LiteralPath $SecretPath -Raw).Trim() | ConvertTo-SecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureToken)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
        $secureToken.Dispose()
    }
}

function Read-AppToken {
    if (-not (Test-Path -LiteralPath $tokenPath)) { throw 'Start the local backend first' }
    return Read-ProtectedValue $tokenPath
}

function Read-GeminiKey {
    if (Test-Path -LiteralPath $geminiKeyPath) { return Read-ProtectedValue $geminiKeyPath }
    foreach ($scope in @('Process', 'User', 'Machine')) {
        $configuredKey = [Environment]::GetEnvironmentVariable('GEMINI_API_KEY', $scope)
        if (-not [string]::IsNullOrWhiteSpace($configuredKey)) { return $configuredKey.Trim() }
    }
    throw 'GEMINI_API_KEY is not configured; no value was logged'
}

if ($Action -eq 'SetGeminiKey') {
    # Interactive hidden input: no key in command-line arguments or source files.
    $secureKey = Read-Host 'Gemini API key (hidden)' -AsSecureString
    $keyPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureKey)
    try {
        $plainKey = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($keyPointer)
        if ($plainKey -notmatch '^[A-Za-z0-9._=+/-]{16,256}$') {
            throw 'Unexpected key characters/length; value was not logged or saved'
        }
        New-Item -ItemType Directory -Path $stateDir -Force | Out-Null
        $secureKey | ConvertFrom-SecureString | Set-Content -LiteralPath $geminiKeyPath -Encoding ASCII
        Write-Output 'Gemini key saved with Windows DPAPI; ignored by Git. Restart the backend to apply.'
    } finally {
        $plainKey = $null
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($keyPointer)
        $secureKey.Dispose()
    }
    return
}

# Resolve/decrypt before stopping the existing backend, so an invalid secret file
# cannot turn a healthy server off. The key is passed only in the child environment.
$launchGeminiKey = $null
if ($Action -in @('Start', 'Restart')) {
    $launchGeminiKey = Read-GeminiKey
}

if ($Action -eq 'Restart') {
    if (-not (Test-Path -LiteralPath $backendPidPath)) { throw 'No managed backend PID exists; use Start' }
    $managedId = [int](Get-Content -LiteralPath $backendPidPath)
    $managedProcess = Get-CimInstance Win32_Process -Filter ('ProcessId = ' + $managedId)
    $expectedScript = Join-Path $PSScriptRoot 'server.py'
    if ($managedProcess) {
        if (-not $managedProcess.CommandLine.Contains('"' + $expectedScript + '"') -or
            $managedProcess.Name -notmatch '^python(\.exe)?$') {
            throw 'PID no longer belongs to this backend; no process was stopped'
        }
        Stop-Process -Id $managedId -ErrorAction Stop
        for ($attempt = 0; $attempt -lt 20; $attempt++) {
            if (-not (Get-Process -Id $managedId -ErrorAction SilentlyContinue)) { break }
            Start-Sleep -Milliseconds 100
        }
    }
}

if ($Action -in @('Start', 'Restart')) {
    New-Item -ItemType Directory -Path $stateDir -Force | Out-Null
    if (-not (Test-Path -LiteralPath $tokenPath)) {
        $randomBytes = New-Object byte[] 32
        $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $rng.GetBytes($randomBytes) } finally { $rng.Dispose() }
        $generatedToken = [Convert]::ToBase64String($randomBytes)
        $generatedToken | ConvertTo-SecureString -AsPlainText -Force | ConvertFrom-SecureString |
            Set-Content -LiteralPath $tokenPath -Encoding ASCII
        $generatedToken = $null
    }
    $existingListener = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
    if ($existingListener) {
        if (-not (Test-Path -LiteralPath $backendPidPath) -or
            $existingListener.OwningProcess -ne [int](Get-Content -LiteralPath $backendPidPath)) {
            throw 'Port 8080 belongs to another process; it was not stopped'
        }
        Write-Output 'Existing local backend is listening; keeping its token'
    } else {
        $previousToken = $env:APP_TOKEN
        $previousGeminiKey = $env:GEMINI_API_KEY
        try {
            $env:APP_TOKEN = Read-AppToken
            $env:GEMINI_API_KEY = $launchGeminiKey
            $env:BACKEND_HOST = '127.0.0.1'
            $env:BACKEND_PORT = '8080'
            $env:ENABLE_LEGACY_CLOUD_AUDIO = '0'
            $pythonExe = (Get-Command python -ErrorAction Stop).Source
            $logStamp = Get-Date -Format 'yyyyMMdd-HHmmss'
            $backendProcess = Start-Process -FilePath $pythonExe -ArgumentList @('-u', ('"' + (Join-Path $PSScriptRoot 'server.py') + '"')) `
                -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -PassThru `
                -RedirectStandardOutput (Join-Path $stateDir ('stdout-' + $logStamp + '.log')) -RedirectStandardError (Join-Path $stateDir ('stderr-' + $logStamp + '.log'))
            $backendProcess.Id | Set-Content -LiteralPath $backendPidPath -Encoding ASCII
            Write-Output ('Started local backend PID ' + $backendProcess.Id)
        } finally {
            $env:APP_TOKEN = $previousToken
            $env:GEMINI_API_KEY = $previousGeminiKey
        }
    }
    $launchGeminiKey = $null
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        try { $health = Invoke-RestMethod 'http://127.0.0.1:8080/health' -TimeoutSec 2; break }
        catch { Start-Sleep -Milliseconds 250 }
    }
    if (-not $health) { throw 'Backend did not become healthy; inspect local logs without sharing credentials' }
    Write-Output 'Backend healthy on PC loopback; app token protected with Windows DPAPI'
}

if ($Action -eq 'ConfigurePhone') {
    if ($Device -notmatch '^[A-Za-z0-9_.:-]+$') { throw 'Provide the exact adb device serial' }
    & $Adb -s $Device reverse tcp:8080 tcp:8080
    if ($LASTEXITCODE -ne 0) { throw 'ADB reverse failed' }
    & $Adb -s $Device shell am force-stop com.example.ai_vision
    if ($LASTEXITCODE -ne 0) { throw 'Cannot stop the app for fresh configuration' }
    $configJson = @{backendUrl='http://127.0.0.1:8080'; backendToken=(Read-AppToken)} | ConvertTo-Json -Compress
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $Adb
    # Only JSON goes over stdin; no token in arguments, adb output, or shell history.
    $startInfo.Arguments = '-s ' + $Device + ' shell "run-as com.example.ai_vision sh -c ''umask 077; cat > files/dev-backend.json''"'
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $adbProcess = [Diagnostics.Process]::Start($startInfo)
    $adbProcess.StandardInput.Write($configJson)
    $adbProcess.StandardInput.Close()
    $null = $adbProcess.StandardOutput.ReadToEnd()
    $null = $adbProcess.StandardError.ReadToEnd()
    $adbProcess.WaitForExit()
    $configJson = $null
    if ($adbProcess.ExitCode -ne 0) { throw 'Private configuration transfer failed; no token was logged' }
    $adbProcess.Dispose()
    & $Adb -s $Device shell am start -W -n com.example.ai_vision/.MainActivity
    if ($LASTEXITCODE -ne 0) { throw 'Cannot open the app' }
    Write-Output 'Phone connected to backend through ADB; app imports and encrypts token on startup'
}

if ($Action -eq 'Status') {
    $health = Invoke-RestMethod 'http://127.0.0.1:8080/health' -TimeoutSec 5
    Write-Output ('Backend healthy: ' + [bool]$health)
    # Auth is tested without spending Gemini quota (blank question must return BAD_REQUEST).
    try {
        $null = Invoke-RestMethod 'http://127.0.0.1:8080/v1/answer' -Method Post -TimeoutSec 5 `
            -Headers @{Authorization=('Bearer ' + (Read-AppToken))} -ContentType 'application/json' `
            -Body (@{sessionId=[Guid]::NewGuid().ToString();question=''} | ConvertTo-Json -Compress)
        throw 'Expected request validation failure'
    } catch {
        if ($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 400) {
            Write-Output 'App token accepted; invalid question rejected (HTTP 400)'
        } else { throw 'Backend authentication check failed; credentials not logged' }
    }
}

if ($Action -eq 'Probe') {
    # Explicit opt-in: one real Gemini request; no automatic retry or secret output.
    $probeBody = @{sessionId=[Guid]::NewGuid().ToString();question='Why is the sky blue? Answer in one short sentence.'} | ConvertTo-Json -Compress
    try {
        $answer = Invoke-RestMethod 'http://127.0.0.1:8080/v1/answer' -Method Post -TimeoutSec 50 `
            -Headers @{Authorization=('Bearer ' + (Read-AppToken))} -ContentType 'application/json' -Body $probeBody
        if (-not $answer.answer) { throw 'Empty answer' }
        Write-Output ('Live Gemini answer PASS; response characters: ' + $answer.answer.Length)
    } catch {
        $status = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
        $safeError = try { $_.ErrorDetails.Message | ConvertFrom-Json } catch { $null }
        $upstreamStatus = if ($safeError.status -is [int] -or $safeError.status -is [long]) { $safeError.status } else { 0 }
        throw ('Live Gemini check failed (backend HTTP ' + $status + ', upstream HTTP ' + $upstreamStatus + '); no automatic retry; credentials not logged')
    }
}
