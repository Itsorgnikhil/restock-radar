@echo off
cd /d "%~dp0"
echo ==============================================
echo       Starting Restock Radar Bot
echo ==============================================

:: Load environment variables from .env
for /f "usebackq tokens=1,2 delims==" %%a in (".env") do (
    if not "%%a"=="" if not "%%b"=="" (
        set "%%a=%%b"
    )
)

:: Run silently in the background without keeping a console window open
start "" javaw -jar target\restock-radar-1.0.0.jar

echo.
echo Restock Radar is now running in the background!
echo You can safely close this window.
echo.
timeout /t 5
