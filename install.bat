@echo off
chcp 65001 >nul
title ClipFetch - 安装 Java 环境变量

REM ===== 需要管理员权限（写入系统环境变量）=====
net session >nul 2>&1
if errorlevel 1 (
    echo [错误] 请以管理员身份运行此脚本
    echo 右键此脚本 -^> 以管理员身份运行
    pause
    exit /b 1
)

set "JAVA_HOME_DIR=D:\develop\java\jdk21.0.11_10"

REM ===== 校验 JDK 目录存在 =====
if not exist "%JAVA_HOME_DIR%\bin\java.exe" (
    echo [错误] 未找到 Java: %JAVA_HOME_DIR%\bin\java.exe
    echo 请确认 JDK 已解压到该目录
    pause
    exit /b 1
)

echo 正在写入系统环境变量...
echo   JAVA_HOME = %JAVA_HOME_DIR%
echo   CLASSPATH = .;%%JAVA_HOME%%\lib\dt.jar;%%JAVA_HOME%%\lib\tools.jar

REM ===== 设置 JAVA_HOME（系统级）=====
setx /M JAVA_HOME "%JAVA_HOME_DIR%" >nul
if errorlevel 1 (
    echo [错误] JAVA_HOME 写入失败
    pause
    exit /b 1
)

REM ===== 设置 CLASSPATH（系统级，REG_EXPAND_SZ 类型使 %%JAVA_HOME%% 运行时可展开）=====
reg add "HKLM\SYSTEM\CurrentControlSet\Control\Session Manager\Environment" /v CLASSPATH /t REG_EXPAND_SZ /d ".;%%JAVA_HOME%%\lib\dt.jar;%%JAVA_HOME%%\lib\tools.jar" /f >nul
if errorlevel 1 (
    echo [错误] CLASSPATH 写入失败
    pause
    exit /b 1
)

REM ===== 将 JDK bin / jre\bin 目录加入系统 Path（PowerShell 操作，避免 setx 截断 Path）=====
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$bins = @('%JAVA_HOME_DIR%\bin', '%JAVA_HOME_DIR%\jre\bin');" ^
  "$path = [Environment]::GetEnvironmentVariable('Path', 'Machine');" ^
  "$items = $path.TrimEnd(';') -split ';' | Where-Object { $_ };" ^
  "foreach ($b in $bins) { if ($items -notcontains $b) { $items += $b; Write-Host '  Path 已追加:' $b } else { Write-Host '  Path 已存在，跳过:' $b } }" ^
  "[Environment]::SetEnvironmentVariable('Path', ($items -join ';'), 'Machine');"

if errorlevel 1 (
    echo [错误] Path 写入失败
    pause
    exit /b 1
)

echo.
echo [完成] Java 环境变量已写入系统环境变量
echo 注意：已打开的命令行窗口需要重新打开才能生效
pause
