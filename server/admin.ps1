# Otwiera panel admina TaxiNav (kierowcy, zatwierdzanie ograniczen predkosci)
Set-Location -Path $PSScriptRoot
$token = ((Get-Content ".env") | Where-Object { $_ -match '^ADMIN_TOKEN=' }) -replace '^ADMIN_TOKEN=', ''
if (-not $token) { Write-Host "Brak ADMIN_TOKEN w .env - uruchom najpierw start.bat" -ForegroundColor Yellow; Read-Host "Enter"; exit 1 }
Start-Process "http://localhost:8080/admin?k=$token"
