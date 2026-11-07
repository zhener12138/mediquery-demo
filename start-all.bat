@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

echo ============================================
echo  MediQuery - start backend + frontend
echo ============================================
echo.

REM ---------- 前置检查 ----------
where java >nul 2>nul || (echo [ERROR] java not found in PATH & pause & exit /b 1)
where node >nul 2>nul || (echo [ERROR] node not found in PATH & pause & exit /b 1)

if not exist "backend\.env" (
    echo [ERROR] backend\.env is missing.
    echo         Copy backend\.env.example and fill in JWT_SECRET / API keys.
    pause
    exit /b 1
)

set "JAR="
for /f "delims=" %%f in ('dir /b /o-d "backend\target\mediquery-backend*.jar" 2^>nul') do if not defined JAR set "JAR=backend\target\%%f"
if not defined JAR (
    echo [ERROR] no jar in backend\target
    echo         Build it first:  cd backend ^&^& mvn -DskipTests package
    pause
    exit /b 1
)

if not exist "frontend\node_modules" (
    echo [INFO] frontend deps missing, running npm install...
    pushd frontend
    call npm install
    popd
)

if not exist "E:\JavaAIProject\_jtmp" mkdir "E:\JavaAIProject\_jtmp"

REM ---------- 后端 ----------
REM 本地开发库是 3306 / 6379；.env 里的默认值 3307 / 6380 是 docker-compose 的端口
set "SPRING_DATASOURCE_URL=jdbc:mysql://127.0.0.1:3306/mediquery?serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=utf-8&useSSL=false&allowPublicKeyRetrieval=true"
set "SPRING_DATASOURCE_USERNAME=root"
set "REDIS_PORT=6379"
set "JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=E:/JavaAIProject/_jtmp"
REM qwen-turbo 免费额度已用尽（HTTP 429），qwen-plus 可用
set "AI_DASHSCOPE_MODEL=qwen-plus"

echo [1/2] backend  : http://localhost:8080   (%JAR%)
start "MediQuery Backend" cmd /c java -jar "%JAR%"

REM 等后端把端口吃住再起前端，避免首屏请求打空
echo        waiting for backend...
set /a WAIT=0
:WAIT_BACKEND
timeout /t 2 /nobreak >nul
curl -s -o nul http://localhost:8080/actuator/health 2>nul && goto BACKEND_UP
set /a WAIT+=2
if %WAIT% LSS 60 goto WAIT_BACKEND
echo [WARN] backend did not answer in 60s, check the backend window for errors.

:BACKEND_UP
echo [2/2] frontend : http://localhost:5173
start "MediQuery Frontend" cmd /c cd /d "%~dp0frontend" ^&^& npm run dev

echo.
echo ============================================
echo  Frontend : http://localhost:5173
echo  Backend  : http://localhost:8080
echo  Swagger  : http://localhost:8080/swagger-ui.html
echo ============================================
echo  Two windows opened. Close them to stop.
echo.
pause
