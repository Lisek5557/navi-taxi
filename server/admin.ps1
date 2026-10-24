# Otwiera panel admina TaxiNav (kierowcy, zatwierdzanie ograniczen predkosci)
Set-Location -Path $PSScriptRoot
$token = ((Get-Content ".env") | Where-Object { $_ -match '^API_TOKEN=' }) -replace '^API_TOKEN=', ''
Start-Process "http://localhost:8080/admin?k=$token"
