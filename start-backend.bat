@echo off
chcp 65001 >nul
cd /d "%~dp0backend"
echo ============================================
echo  MediQuery Backend
echo ============================================

REM Pick the newest jar in target (handles rebuilt or renamed jars)
set "JAR="
for /f "delims=" %%f in ('dir /b /o-d "target\mediquery-backend*.jar" 2^>nul') do if not defined JAR set "JAR=target\%%f"

if not defined JAR (
    echo [ERROR] no jar found in backend target directory
    echo         Build it first:  mvn -DskipTests package
    pause
    exit /b 1
)

if not exist "E:\JavaAIProject\_jtmp" mkdir "E:\JavaAIProject\_jtmp"

REM Local MySQL=3306 Redis=6379 (.env defaults 3307/6380 are Docker ports)
set "SPRING_DATASOURCE_URL=jdbc:mysql://127.0.0.1:3306/mediquery?serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=utf-8&useSSL=false&allowPublicKeyRetrieval=true"
set "SPRING_DATASOURCE_USERNAME=root"
set "REDIS_PORT=6379"
set "JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=E:/JavaAIProject/_jtmp"
REM qwen-turbo free quota on this account is exhausted (HTTP 429); qwen-plus works
set "AI_DASHSCOPE_MODEL=qwen-plus"

echo Backend : http://localhost:8080
echo Swagger : http://localhost:8080/swagger-ui.html
echo Jar     : %JAR%
echo.
java -jar %JAR%
pause
