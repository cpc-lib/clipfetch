@echo off
chcp 65001 >nul
title ClipFetch Backend

REM ===== 进入脚本所在目录（.env / data / downloads 等相对路径以此目录为基准）=====
cd /d "%~dp0"

set "JAR=app.jar"

REM ===== 校验 jar 存在 =====
if not exist "%JAR%" (
    echo [错误] 未找到 %JAR%
    echo 请将打包产物 backend\target\clipfetch.jar 复制为本目录下的 app.jar
    pause
    exit /b 1
)

REM ===== 提示 .env 缺失（可选，缺失时使用 application.yml 内置默认配置）=====
if not exist ".env" (
    echo [警告] 未找到 .env，将使用内置默认配置
)

REM ===== 从 .env 读取 SERVER_PORT 用于显示（默认 8080）=====
set "PORT=8080"
if exist ".env" (
    for /f "usebackq tokens=1,* delims==" %%A in (".env") do (
        if /i "%%A"=="SERVER_PORT" set "PORT=%%B"
    )
)

REM ===== 查找 Java：优先 JAVA_HOME，其次 PATH =====
set "JAVA_EXE="
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
)
if not defined JAVA_EXE (
    where java >nul 2>&1
    if not errorlevel 1 set "JAVA_EXE=java"
)
if not defined JAVA_EXE (
    echo [错误] 未找到 Java，请先运行 install.bat 配置环境变量
    pause
    exit /b 1
)

echo 使用 Java: %JAVA_EXE%
echo 启动 ClipFetch 后端（端口 %PORT%）...
echo.

"%JAVA_EXE%" -Dfile.encoding=UTF-8 -jar "%JAR%"

pause
