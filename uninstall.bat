@echo off
chcp 65001 >nul
title ClipFetch - 移除环境变量

REM ===== 需要管理员权限（操作系统环境变量）=====
net session >nul 2>&1
if errorlevel 1 (
    echo [错误] 请以管理员身份运行此脚本
    echo 右键此脚本 -^> 以管理员身份运行
    pause
    exit /b 1
)

echo 正在移除系统环境变量...

REM ===== 移除 JAVA_HOME =====
reg delete "HKLM\SYSTEM\CurrentControlSet\Control\Session Manager\Environment" /v JAVA_HOME /f >nul 2>&1
if errorlevel 1 (
    echo   JAVA_HOME 不存在或已移除
) else (
    echo   JAVA_HOME 已移除
)

REM ===== 移除 CLASSPATH =====
reg delete "HKLM\SYSTEM\CurrentControlSet\Control\Session Manager\Environment" /v CLASSPATH /f >nul 2>&1
if errorlevel 1 (
    echo   CLASSPATH 不存在或已移除
) else (
    echo   CLASSPATH 已移除
)

REM ===== 从系统 Path 中移除 %%JAVA_HOME%%\bin 和 %%JAVA_HOME%%\jre\bin（注册表操作，保留 REG_EXPAND_SZ 类型）=====
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$bins = @('%%JAVA_HOME%%\bin', '%%JAVA_HOME%%\jre\bin');" ^
  "$key = [Microsoft.Win32.Registry]::LocalMachine.OpenSubKey('SYSTEM\CurrentControlSet\Control\Session Manager\Environment', $true);" ^
  "$path = $key.GetValue('Path', '', 'DoNotExpandEnvironmentNames');" ^
  "$items = $path -split ';' | Where-Object { $_ -and ($bins -notcontains $_) };" ^
  "$key.SetValue('Path', ($items -join ';'), 'ExpandString');" ^
  "$key.Close();" ^
  "foreach ($b in $bins) { Write-Host '  Path 已清理:' $b }"

if errorlevel 1 (
    echo [错误] Path 清理失败
    pause
    exit /b 1
)

REM ===== 移除 FFMPEG / DENO / ARIA2C =====
for %%V in (FFMPEG DENO ARIA2C) do (
    reg delete "HKLM\SYSTEM\CurrentControlSet\Control\Session Manager\Environment" /v %%V /f >nul 2>&1
    if errorlevel 1 (
        echo   %%V 不存在或已移除
    ) else (
        echo   %%V 已移除
    )
)

echo.
echo [完成] 环境变量已从系统环境变量移除
echo 注意：已打开的命令行窗口需要重新打开才能生效
pause
