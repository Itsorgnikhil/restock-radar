@echo off
echo ==============================================
echo       Stopping Restock Radar Bot
echo ==============================================

:: Find process listening on port 8080 and kill it
for /f "tokens=5" %%a in ('netstat -aon ^| findstr ":8080" ^| findstr "LISTENING"') do (
    echo Stopping PID %%a...
    taskkill /f /pid %%a >nul 2>&1
)

echo.
echo Restock Radar has been stopped.
echo.
timeout /t 3
