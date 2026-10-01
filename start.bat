@echo off
chcp 65001 >nul
title ClipFetch Backend

REM ===== 进入后端目录（.env / data / downloads 等相对路径以此目录为基准）=====
cd /d "%~dp0backend"

set "JAR=target\clipfetch.jar"

REM ===== 校验 jar 存在 =====
if not exist "%JAR%" (
    echo [错误] 未找到 %JAR%
    echo 请先执行打包：cd backend ^&^& mvn clean package -DskipTests
    pause
    exit /b 1
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
echo 启动 ClipFetch 后端（端口 8080）...
echo.

"%JAVA_EXE%" -Dfile.encoding=UTF-8 -jar "%JAR%"

pause
