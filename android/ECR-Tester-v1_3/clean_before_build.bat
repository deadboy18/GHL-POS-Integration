@echo off
echo ============================================
echo  GHL POS - Cleaning desktop.ini files...
echo ============================================
echo.

cd /d "%~dp0"

for /r %%f in (desktop.ini) do (
    if exist "%%f" (
        attrib -s -h "%%f"
        del "%%f"
        echo Deleted: %%f
    )
)

for /r %%f in (Thumbs.db) do (
    if exist "%%f" (
        attrib -s -h "%%f"
        del "%%f"
        echo Deleted: %%f
    )
)

echo.
echo Done! You can now build in Android Studio.
echo Press any key to close...
pause >nul
