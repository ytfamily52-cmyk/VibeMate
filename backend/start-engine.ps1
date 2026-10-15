# VibeMate - 18+ Private engine (backend) ko ek command se start / stop karta hai.
#
#   powershell -ExecutionPolicy Bypass -File backend\start-engine.ps1        # start
#   powershell -ExecutionPolicy Bypass -File backend\start-engine.ps1 -Stop  # stop
#
# Ye script .venv ki python se `python -m app.main` chalata hai (jo .env padhta
# hai), phir /health se confirm karta hai ki private_ai_mode = openrouter hai,
# aur phone ke liye sahi URL + firewall hint print karta hai.
#
# NOTE: Private (18+) room sirf tab chalta hai jab YE process chal raha ho.

param(
    [switch]$Stop,
    [int]$Port = 8000
)

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

function Get-LanIp {
    $ip = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object { $_.IPAddress -match '^192\.168\.' -or $_.IPAddress -match '^10\.' } |
        Select-Object -First 1 -ExpandProperty IPAddress
    if ($ip) { return $ip }
    return "127.0.0.1"
}

function Get-ListeningPid([int]$p) {
    (Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty OwningProcess)
}

# ---------------------------------------------------------------- STOP MODE ---
if ($Stop) {
    $enginePid = Get-ListeningPid $Port
    if (-not $enginePid) {
        Write-Host "[stop] Port $Port pe kuch nahi chal raha - engine already band hai." -ForegroundColor Yellow
        exit 0
    }
    try {
        $procName = (Get-Process -Id $enginePid).ProcessName
        Stop-Process -Id $enginePid -Force
        Write-Host "[stop] Engine band kar diya (PID $enginePid, $procName)." -ForegroundColor Green
    } catch {
        Write-Host "[stop] PID $enginePid band nahi kar paya: $_" -ForegroundColor Red
        exit 1
    }
    exit 0
}

# --------------------------------------------------------------- START MODE ---
$python = Join-Path $here ".venv\Scripts\python.exe"
if (-not (Test-Path $python)) {
    Write-Host "[error] Virtual env nahi mila: $python" -ForegroundColor Red
    Write-Host "        Pehle banao:  cd backend;  python -m venv .venv;  .\.venv\Scripts\pip install -r requirements.txt" -ForegroundColor Yellow
    exit 1
}

# Agar already chal raha hai to naya duplicate na banaye - sirf health dikhao.
$existing = Get-ListeningPid $Port
if ($existing) {
    Write-Host "[info] Engine pehle se port $Port pe chal raha hai (PID $existing)." -ForegroundColor Yellow
} else {
    Write-Host "[start] Backend shuru kar raha hoon (.venv\Scripts\python.exe -m app.main) ..." -ForegroundColor Cyan
    $logOut = Join-Path $here "engine.log"
    $logErr = Join-Path $here "engine.err.log"
    Start-Process -FilePath $python -ArgumentList "-m", "app.main" `
        -WorkingDirectory $here -WindowStyle Hidden `
        -RedirectStandardOutput $logOut -RedirectStandardError $logErr | Out-Null
}

# /health ka intezaar (max ~30 sec)
$health = $null
for ($i = 1; $i -le 30; $i++) {
    Start-Sleep -Seconds 1
    try {
        $health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/health" -TimeoutSec 3
        break
    } catch { }
}

$lanIp = Get-LanIp
$url = "http://${lanIp}:$Port"

Write-Host ""
if (-not $health) {
    Write-Host "[FAIL] Engine 30 sec me up nahi aaya. Log dekho: backend\engine.err.log" -ForegroundColor Red
    exit 1
}

Write-Host "==========================================================" -ForegroundColor Green
Write-Host " VibeMate 18+ Engine: RUNNING" -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green
Write-Host ("  status           : " + $health.status)
Write-Host ("  private_ai_mode  : " + $health.private_ai_mode) -ForegroundColor Cyan
Write-Host ("  vision_reader    : " + $health.vision_reader)
Write-Host ("  store            : " + $health.store)
if ($health.private_models) {
    Write-Host "  private_models   :"
    foreach ($p in $health.private_models.PSObject.Properties) {
        Write-Host ("    - " + $p.Name + " -> " + ($p.Value -join " , "))
    }
}
Write-Host ""
if ($health.private_ai_mode -ne "openrouter") {
    Write-Host "[WARN] private_ai_mode = $($health.private_ai_mode) - OPENROUTER_API_KEY backend\.env me set karo," -ForegroundColor Yellow
    Write-Host "       warna 18+ room adult reply nahi dega." -ForegroundColor Yellow
}
if ($health.vision_reader -ne "on") {
    Write-Host "[WARN] vision_reader OFF - private room me photo read nahi hogi." -ForegroundColor Yellow
}

Write-Host ""
Write-Host "Phone ke liye backend URL : $url" -ForegroundColor Cyan
Write-Host "AppConfig.kt me hona chahiye: const val BACKEND_URL = `"$url`"" -ForegroundColor Cyan
Write-Host ""
Write-Host "Zaroori checklist:" -ForegroundColor White
Write-Host "  1. Phone aur PC SAME Wi-Fi pe ho."
Write-Host "  2. PC ka IP badle to AppConfig.kt + network_security_config.xml update karo."
Write-Host "  3. Firewall port $Port allow kare (ek baar, admin PowerShell):"
Write-Host "       New-NetFirewallRule -DisplayName 'VibeMate backend $Port' -Direction Inbound -Protocol TCP -LocalPort $Port -Action Allow -Profile Private" -ForegroundColor DarkGray
Write-Host "  4. Phone browser me test: $url/health"
Write-Host ""
Write-Host "Band karne ke liye: powershell -ExecutionPolicy Bypass -File backend\start-engine.ps1 -Stop" -ForegroundColor Yellow
