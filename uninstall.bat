@echo off
chcp 65001 >nul
title ClipFetch - 移除 Java 环境变量

REM ===== 需要管理员权限（操作系统环境变量）=====
net session >nul 2>&1
if errorlevel 1 (
    echo [错误] 请以管理员身份运行此脚本
    echo 右键此脚本 -^> 以管理员身份运行
    pause
    exit /b 1
)

set "JAVA_HOME_DIR=D:\develop\java\jdk21.0.11_10"

echo 正在移除系统环境变量...

REM ===== 移除 JAVA_HOME =====
reg delete "HKLM\SYSTEM\CurrentControlSet\Control\Session Manager\Environment" /v JAVA_HOME /f >nul 2>&1
if errorlevel 1 (
    echo   JAVA_HOME 不存在或已移除
) else (
    echo   JAVA_HOME 已移除
)

REM ===== 从系统 Path 中移除 JDK bin 目录 =====
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$bin = '%JAVA_HOME_DIR%\bin';" ^
  "$path = [Environment]::GetEnvironmentVariable('Path', 'Machine');" ^
  "$items = $path -split ';' | Where-Object { $_ -and ($_ -ne $bin) };" ^
  "[Environment]::SetEnvironmentVariable('Path', ($items -join ';'), 'Machine');" ^
  "Write-Host '  Path 已清理:' $bin"

if errorlevel 1 (
    echo [错误] Path 清理失败
    pause
    exit /b 1
)

echo.
echo [完成] Java 环境变量已从系统环境变量移除
echo 注意：已打开的命令行窗口需要重新打开才能生效
pause
