@echo off
chcp 65001 >nul
cd /d "%~dp0backend"
echo ============================================
echo  MediQuery Backend
echo ============================================

if not exist "target\mediquery-backend-1.0.0.jar" (
    echo [ERROR] jar not found in backend\target
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

echo Backend : http://localhost:8080
echo Swagger : http://localhost:8080/swagger-ui.html
echo.
java -jar target\mediquery-backend-1.0.0.jar
pause
