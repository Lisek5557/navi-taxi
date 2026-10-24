# TaxiNav - uruchomienie serwera (PowerShell). Uruchom: prawy klik -> "Run with PowerShell"
# albo w terminalu:  powershell -ExecutionPolicy Bypass -File .\start.ps1
$ErrorActionPreference = "Stop"
Set-Location -Path $PSScriptRoot

Write-Host "== TaxiNav serwer ==" -ForegroundColor Cyan

# 1. Docker
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Host "Brak Dockera. Zainstaluj Docker Desktop: https://www.docker.com/products/docker-desktop/" -ForegroundColor Red
    Write-Host "albo: winget install -e --id Docker.DockerDesktop" -ForegroundColor Yellow
    Read-Host "Enter aby zamknac"; exit 1
}
docker info *> $null
if ($LASTEXITCODE -ne 0) {
    Write-Host "Docker Desktop nie dziala - uruchamiam i czekam..." -ForegroundColor Yellow
    $dd = "$Env:ProgramFiles\Docker\Docker\Docker Desktop.exe"
    if (Test-Path $dd) { Start-Process $dd }
    for ($i = 0; $i -lt 60; $i++) { Start-Sleep 5; docker info *> $null; if ($LASTEXITCODE -eq 0) { break } }
    if ($LASTEXITCODE -ne 0) { Write-Host "Docker nie wystartowal." -ForegroundColor Red; Read-Host "Enter"; exit 1 }
}

# 2. RAM (budowanie kafli Polski potrzebuje ok. 6-8 GB dla Dockera)
$ramGb = [math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB)
Write-Host "RAM komputera: $ramGb GB"
if ($ramGb -lt 12) { Write-Host "Uwaga: malo RAM. W Docker Desktop -> Settings -> Resources daj min. 6 GB." -ForegroundColor Yellow }
$freeGb = [math]::Round((Get-PSDrive (Get-Location).Drive.Name).Free / 1GB)
Write-Host "Wolne miejsce na dysku: $freeGb GB (potrzeba ok. 15 GB)"

# 3. .env z losowym kluczem API
if (-not (Test-Path ".env")) {
    $token = -join ((48..57) + (97..122) | Get-Random -Count 24 | ForEach-Object { [char]$_ })
    (Get-Content ".env.example") -replace '^API_TOKEN=$', "API_TOKEN=$token" | Set-Content ".env" -Encoding ascii
    Write-Host "Utworzono .env z nowym kluczem API." -ForegroundColor Green
    Write-Host "Wpisz do .env TOMTOM_API_KEY (darmowy z developer.tomtom.com), zeby widziec korki." -ForegroundColor Yellow
}
if (-not ((Get-Content ".env") -match '^ADMIN_TOKEN=.+')) {
    $adm = -join ((48..57) + (97..122) | Get-Random -Count 28 | ForEach-Object { [char]$_ })
    $lines = @(Get-Content ".env" | Where-Object { $_ -notmatch '^ADMIN_TOKEN=' }) + "ADMIN_TOKEN=$adm"
    Set-Content -Path ".env" -Value $lines -Encoding ascii
    Write-Host "Dodano ADMIN_TOKEN (panel admina: admin.bat)." -ForegroundColor Green
}
$token = ((Get-Content ".env") | Where-Object { $_ -match '^API_TOKEN=' }) -replace '^API_TOKEN=', ''

# 4. Start
New-Item -ItemType Directory -Force -Path "valhalla_data" | Out-Null
docker compose up -d --build
if ($LASTEXITCODE -ne 0) { Write-Host "docker compose nie powiodl sie" -ForegroundColor Red; Read-Host "Enter"; exit 1 }

# 5. Adres tunelu
Write-Host "Czekam na adres tunelu Cloudflare..."
$url = $null
for ($i = 0; $i -lt 30 -and -not $url; $i++) {
    Start-Sleep 2
    $logs = docker compose logs tunnel 2>&1 | Out-String
    $m = [regex]::Matches($logs, 'https://[a-z0-9-]+\.trycloudflare\.com')
    if ($m.Count -gt 0) { $url = $m[$m.Count - 1].Value }
}
$lan = (Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object { $_.IPAddress -match '^(192\.168|10\.|172\.(1[6-9]|2\d|3[01]))' -and $_.PrefixOrigin -ne 'WellKnown' } |
    Select-Object -First 1).IPAddress

Write-Host ""
Write-Host "================ WPISZ W APLIKACJI (Ustawienia) ================" -ForegroundColor Cyan
if ($url) { Write-Host "Adres serwera (internet): $url" -ForegroundColor Green }
else { Write-Host "Tunel jeszcze nie gotowy - sprawdz pozniej: .\tunnel-url.ps1" -ForegroundColor Yellow }
if ($lan) { Write-Host "Adres serwera (Wi-Fi):    http://${lan}:8080" }
Write-Host "Klucz API:                $token" -ForegroundColor Green
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Pierwsze uruchomienie: Valhalla pobiera mape Polski (~1.9 GB) i buduje kafle (30-90 min)."
Write-Host "Postep: docker compose logs -f valhalla     Stan: .\status.ps1"

# 6. Strona z kodem QR do zeskanowania telefonem
$setup = "http://localhost:8080/setup?k=$token"
if ($lan) { $setup += "&lan=" + [uri]::EscapeDataString("http://${lan}:8080") }
for ($i = 0; $i -lt 15; $i++) {
    try { Invoke-WebRequest -UseBasicParsing -Uri "http://localhost:8080/health" -TimeoutSec 2 | Out-Null; break } catch { Start-Sleep 2 }
}
Write-Host "Otwieram strone z kodem QR (qr.bat otwiera ja ponownie)..." -ForegroundColor Cyan
Start-Process $setup
Read-Host "Enter aby zamknac"
