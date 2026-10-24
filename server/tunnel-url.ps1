# Pokazuje aktualny adres tunelu (quick tunnel zmienia sie po kazdym restarcie).
Set-Location -Path $PSScriptRoot
$logs = docker compose logs tunnel 2>&1 | Out-String
$m = [regex]::Matches($logs, 'https://[a-z0-9-]+\.trycloudflare\.com')
if ($m.Count -gt 0) { Write-Host $m[$m.Count - 1].Value -ForegroundColor Green } else { Write-Host "Brak adresu - czy tunel dziala? docker compose ps" -ForegroundColor Yellow }
