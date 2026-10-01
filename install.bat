@echo off
chcp 65001 >nul
title ClipFetch - 安装环境变量

REM ===== 需要管理员权限（写入系统环境变量）=====
net session >nul 2>&1
if errorlevel 1 (
    echo [错误] 请以管理员身份运行此脚本
    echo 右键此脚本 -^> 以管理员身份运行
    pause
    exit /b 1
)

set "JAVA_HOME_DIR=D:\release\clipfetch\deploy\jdk21"
set "FFMPEG_DIR=D:\release\clipfetch\deploy\ffmpeg\bin"
set "DENO_EXE=D:\release\clipfetch\deploy\deno\deno.exe"
set "ARIA2C_EXE=D:\release\clipfetch\deploy\aria2\aria2c.exe"
set "YTDLP_EXE=D:\release\clipfetch\deploy\yt-dlp"
set "NODE_PATH_DIR=D:\develop\nodejs"
set "NODE_VERSION_DIR=%NODE_PATH_DIR%\v24.21.0"

REM ===== 校验 JDK 目录存在 =====
if not exist "%JAVA_HOME_DIR%\bin\java.exe" (
    echo [错误] 未找到 Java: %JAVA_HOME_DIR%\bin\java.exe
    echo 请确认 JDK 已解压到该目录
    pause
    exit /b 1
)

REM ===== 校验工具路径存在 =====
if not exist "%YTDLP_EXE%\yt-dlp.exe" (
    echo [警告] 未找到 yt-dlp: %YTDLP_EXE%\yt-dlp.exe
)
if not exist "%FFMPEG_DIR%\ffmpeg.exe" (
    echo [警告] 未找到 ffmpeg: %FFMPEG_DIR%\ffmpeg.exe
)
if not exist "%DENO_EXE%" (
    echo [警告] 未找到 deno: %DENO_EXE%
)
if not exist "%ARIA2C_EXE%" (
    echo [警告] 未找到 aria2c: %ARIA2C_EXE%
)
if not exist "%NODE_VERSION_DIR%\node.exe" (
    echo [警告] 未找到 node: %NODE_VERSION_DIR%\node.exe
)

echo 正在写入系统环境变量...
echo   JAVA_HOME = %JAVA_HOME_DIR%
echo   CLASSPATH = .;%%JAVA_HOME%%\lib\dt.jar;%%JAVA_HOME%%\lib\tools.jar
echo   YTDLP = %YTDLP_EXE%
echo   FFMPEG = %FFMPEG_DIR%
echo   DENO = %DENO_EXE%
echo   ARIA2C = %ARIA2C_EXE%
echo   NODE_PATH = %NODE_PATH_DIR%
echo   NODE_HOME = %%NODE_PATH%%\v24.21.0

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

REM ===== 设置 NODE_PATH / NODE_HOME（系统级；NODE_HOME 用 REG_EXPAND_SZ 使 %%NODE_PATH%% 运行时可展开）=====
setx /M NODE_PATH "%NODE_PATH_DIR%" >nul
if errorlevel 1 (
    echo [错误] NODE_PATH 写入失败
    pause
    exit /b 1
)
reg add "HKLM\SYSTEM\CurrentControlSet\Control\Session Manager\Environment" /v NODE_HOME /t REG_EXPAND_SZ /d "%%NODE_PATH%%\v24.21.0" /f >nul
if errorlevel 1 (
    echo [错误] NODE_HOME 写入失败
    pause
    exit /b 1
)

REM ===== 将 Java/Node 相关目录加入系统 Path（注册表操作，保留 REG_EXPAND_SZ 类型使变量可展开）=====
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$bins = @('%%JAVA_HOME%%\bin', '%%JAVA_HOME%%\jre\bin', '%%NODE_PATH%%\node_cache', '%%NODE_PATH%%\node_global', '%%NODE_PATH%%\v24.21.0');" ^
  "$key = [Microsoft.Win32.Registry]::LocalMachine.OpenSubKey('SYSTEM\CurrentControlSet\Control\Session Manager\Environment', $true);" ^
  "$path = $key.GetValue('Path', '', 'DoNotExpandEnvironmentNames');" ^
  "$items = $path.TrimEnd(';') -split ';' | Where-Object { $_ };" ^
  "foreach ($b in $bins) { if ($items -notcontains $b) { $items += $b; Write-Host '  Path 已追加:' $b } else { Write-Host '  Path 已存在，跳过:' $b } }" ^
  "$key.SetValue('Path', ($items -join ';'), 'ExpandString');" ^
  "$key.Close();"

if errorlevel 1 (
    echo [错误] Path 写入失败
    pause
    exit /b 1
)

REM ===== 设置 YTDLP / FFMPEG / DENO / ARIA2C（系统级，供 backend\.env 通过 %%VAR%% 引用）=====
setx /M YTDLP "%YTDLP_EXE%" >nul
if errorlevel 1 (
    echo [错误] YTDLP 写入失败
    pause
    exit /b 1
)
setx /M FFMPEG "%FFMPEG_DIR%" >nul
if errorlevel 1 (
    echo [错误] FFMPEG 写入失败
    pause
    exit /b 1
)
setx /M DENO "%DENO_EXE%" >nul
if errorlevel 1 (
    echo [错误] DENO 写入失败
    pause
    exit /b 1
)
setx /M ARIA2C "%ARIA2C_EXE%" >nul
if errorlevel 1 (
    echo [错误] ARIA2C 写入失败
    pause
    exit /b 1
)

echo.
echo [完成] 环境变量已写入系统环境变量
echo 注意：已打开的命令行窗口需要重新打开才能生效
pause
