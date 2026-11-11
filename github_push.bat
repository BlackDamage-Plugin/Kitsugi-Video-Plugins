@echo off
echo =======================================================
echo        Kitsugi Plugins GitHub Sync Tool v2
echo =======================================================
echo.

cd /d "%~dp0"

echo [1/4] Checking modified plugins...
git status --short
echo.

echo [2/4] Auto-bumping versions of changed plugins...
python auto_bump.py
echo.

echo -------------------------------------------------------
git status --short
echo -------------------------------------------------------
echo.

set /p CONFIRM="Push changes to GitHub? (Y/N): "
if /i not "%CONFIRM%"=="Y" (
    echo Cancelled.
    pause
    exit /b 0
)

set /p MSG="Commit message (blank = default): "
if "%MSG%"=="" set MSG=Plugin Update

echo.
echo [3/4] Adding and committing...
git add .
git commit -m "%MSG%"
echo.

echo [4/4] Pushing to GitHub main branch...
git push origin main

echo.
echo =======================================================
echo [DONE] Plugins successfully pushed to GitHub!
echo       GitHub Actions will auto-build and deploy.
echo =======================================================
echo.
pause