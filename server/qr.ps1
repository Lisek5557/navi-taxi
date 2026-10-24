# Otwiera strone z kodem QR do konfiguracji telefonu
Set-Location -Path $PSScriptRoot
$token = ((Get-Content ".env") | Where-Object { $_ -match '^API_TOKEN=' }) -replace '^API_TOKEN=', ''
$lan = (Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object { $_.IPAddress -match '^(192\.168|10\.|172\.(1[6-9]|2\d|3[01]))' -and $_.PrefixOrigin -ne 'WellKnown' } |
    Select-Object -First 1).IPAddress
$setup = "http://localhost:8080/setup?k=$token"
if ($lan) { $setup += "&lan=" + [uri]::EscapeDataString("http://${lan}:8080") }
Start-Process $setup
