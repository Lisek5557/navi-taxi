@echo off
cd /d "%~dp0"
echo Postep budowania mapy (Ctrl+C aby zamknac). Gotowe, gdy pojawi sie "valhalla_service" / "Starting".
echo.
docker compose logs -f --tail 40 valhalla
pause
