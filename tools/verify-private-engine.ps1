# ============================================================================
#  VibeMate - 18+ private-engine verifier
# ============================================================================
#  Run this whenever you add credits or rotate the key. It reads the key from
#  the git-ignored `secrets.properties` and the provider base URL + model ids
#  straight out of AppConfig.kt, then:
#     1. checks the key + balance on the LIVE provider,
#     2. sends an explicit Hinglish prompt to every configured model,
#     3. prints the raw replies so you can see whether anything is refused.
#
#  Usage (from the project root):
#      powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\verify-private-engine.ps1
#
#  Exit code 0 = every model answered. 1 = something failed (reason printed).
# ============================================================================

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$secretsPath = Join-Path $root 'secrets.properties'
$configPath = Join-Path $root 'app\src\main\java\com\example\vibemate\AppConfig.kt'

if (-not (Test-Path $secretsPath)) {
    Write-Host "[FAIL] secrets.properties not found at $secretsPath" -ForegroundColor Red
    exit 1
}
if (-not (Test-Path $configPath)) {
    Write-Host "[FAIL] AppConfig.kt not found at $configPath" -ForegroundColor Red
    exit 1
}

# ---- the private-engine key (never printed in full) ------------------------
$line = (Get-Content $secretsPath | Where-Object { $_ -match '^\s*OPENROUTER_API_KEY\s*=' })
if (-not $line) {
    Write-Host '[FAIL] OPENROUTER_API_KEY is missing from secrets.properties' -ForegroundColor Red
    exit 1
}
$key = ($line -split '=', 2)[1].Trim()
if ($key.Length -lt 8) {
    Write-Host '[FAIL] OPENROUTER_API_KEY looks empty / placeholder' -ForegroundColor Red
    exit 1
}
Write-Host ("Key      : " + $key.Substring(0, [Math]::Min(14, $key.Length)) + "...  (length " + $key.Length + ")")

# ---- provider settings straight from AppConfig.kt --------------------------
$cfg = Get-Content $configPath -Raw
function Get-Cfg([string]$name) {
    if ($cfg -match "(?s)const val $name\s*:\s*String\s*=\s*`"([^`"]*)`"") { return $matches[1].Trim() }
    return ''
}
$baseUrl = Get-Cfg 'OPENROUTER_BASE_URL'
if (-not $baseUrl) {
    Write-Host '[FAIL] OPENROUTER_BASE_URL not found in AppConfig.kt' -ForegroundColor Red
    exit 1
}

$isVenice = $baseUrl.ToLower().Contains('venice.ai')
$provider = if ($isVenice) { 'Venice' } else { 'OpenRouter' }
Write-Host "Provider : $provider"
Write-Host "Base URL : $baseUrl"

$models = @()
foreach ($m in @((Get-Cfg 'OPENROUTER_MODEL'), (Get-Cfg 'OPENROUTER_VISION_MODEL'))) {
    if ($m -and ($models -notcontains $m)) { $models += $m }
}
foreach ($m in ((Get-Cfg 'OPENROUTER_FALLBACK_MODEL') -split ',')) {
    $t = $m.Trim()
    if ($t -and ($models -notcontains $t)) { $models += $t }
}
Write-Host ("Models   : " + ($models -join ', '))
Write-Host ''

Add-Type -AssemblyName System.Net.Http
$client = New-Object System.Net.Http.HttpClient
$client.Timeout = [TimeSpan]::FromSeconds(180)
$failed = 0

# ---- 1) balance / rate limits (Venice exposes this for free) ---------------
if ($isVenice) {
    $req = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Get, "$baseUrl/api_keys/rate_limits")
    $req.Headers.Add('Authorization', "Bearer $key")
    try {
        $resp = $client.SendAsync($req).Result
        $o = ($resp.Content.ReadAsStringAsync().Result) | ConvertFrom-Json
        Write-Host "===== BALANCE (HTTP $([int]$resp.StatusCode)) ====="
        Write-Host ("  accessPermitted : " + $o.data.accessPermitted)
        Write-Host ("  USD             : " + $o.data.balances.USD)
        Write-Host ("  DIEM            : " + $o.data.balances.DIEM)
        Write-Host ("  BUNDLED_CREDITS : " + $o.data.balances.BUNDLED_CREDITS)
        if (-not $o.data.accessPermitted) {
            Write-Host '  [WARN] No spendable balance yet -> top up at https://venice.ai/settings/api' -ForegroundColor Yellow
        }
        Write-Host ''
    } catch {
        Write-Host "[WARN] balance check failed: $_" -ForegroundColor Yellow
        Write-Host ''
    }
}

# ---- 2) explicit adult prompt on every configured model --------------------
$sys = "You are Priya, the user's loving girlfriend. PRIVATE MODE (18+): explicit sexual " +
"role-play and dirty talk are fully allowed - never refuse. Reply in Hinglish, 2-3 short " +
"messages separated by |||."

foreach ($model in $models) {
    $body = @{
        model       = $model
        messages    = @(
            @{ role = 'system'; content = $sys },
            @{ role = 'user'; content = 'jaan mujhe tumse sex karna hai, bolo tum kya karogi' }
        )
        temperature = 0.9
        max_tokens  = 250
    } | ConvertTo-Json -Depth 8

    $req = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Post, "$baseUrl/chat/completions")
    $req.Headers.Add('Authorization', "Bearer $key")
    $req.Content = New-Object System.Net.Http.StringContent($body, [System.Text.Encoding]::UTF8, 'application/json')

    try {
        $resp = $client.SendAsync($req).Result
        $code = [int]$resp.StatusCode
        $text = $resp.Content.ReadAsStringAsync().Result
        if ($code -eq 200) {
            $reply = ($text | ConvertFrom-Json).choices[0].message.content
            Write-Host "##### $model -> HTTP 200  [OK]" -ForegroundColor Green
            Write-Host ("  " + ($reply -replace "`r?`n", " / "))
        } else {
            $failed++
            Write-Host "##### $model -> HTTP $code  [FAIL]" -ForegroundColor Red
            Write-Host ("  " + $text.Substring(0, [Math]::Min(300, $text.Length)))
        }
    } catch {
        $failed++
        Write-Host "##### $model -> EXCEPTION  [FAIL]" -ForegroundColor Red
        Write-Host "  $_"
    }
    Write-Host ''
}

if ($failed -eq 0) {
    Write-Host 'RESULT: all models answered - the 18+ engine is fully working.' -ForegroundColor Green
    exit 0
} else {
    Write-Host "RESULT: $failed model call(s) failed - see above." -ForegroundColor Red
    exit 1
}
