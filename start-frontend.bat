@echo off
chcp 65001 >nul
cd /d "%~dp0frontend"
echo ============================================
echo  MediQuery Frontend (Vite Dev)
echo ============================================

if not exist "node_modules" (
    echo [INFO] Installing dependencies...
    call npm install
)

echo Frontend: http://localhost:5173
echo Requires backend on :8080
echo.
call npm run dev
pause
