# Stan serwera TaxiNav
Set-Location -Path $PSScriptRoot
docker compose ps
try {
    $h = Invoke-RestMethod -Uri "http://localhost:8080/health" -TimeoutSec 5
    Write-Host ("API: OK | Valhalla gotowa: {0} | Korki TomTom: {1}" -f $h.valhalla, $h.traffic) -ForegroundColor Green
    if (-not $h.valhalla) { Write-Host "Valhalla jeszcze buduje kafle - zobacz: docker compose logs --tail 20 valhalla" -ForegroundColor Yellow }
} catch { Write-Host "API nie odpowiada na http://localhost:8080" -ForegroundColor Red }
