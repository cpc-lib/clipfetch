#requires -Version 5.1
<#
.SYNOPSIS
    Persist an HTTP(S) proxy for Node.js 24 on Windows.
.EXAMPLE
    .\node-proxy.ps1
.EXAMPLE
    .\node-proxy.ps1 -Mode Status
.EXAMPLE
    .\node-proxy.ps1 -Mode Disable
.EXAMPLE
    .\node-proxy.ps1 -ProxyUrl "http://127.0.0.1:10808"
#>
[CmdletBinding()]
param(
    [ValidateSet('Enable', 'Disable', 'Status')]
    [string]$Mode = 'Enable',

    [string]$ProxyUrl = 'http://127.0.0.1:10808',

    [string]$NoProxy = 'localhost,127.0.0.1,::1'
)

$ErrorActionPreference = 'Stop'
$variableNames = @('HTTP_PROXY', 'HTTPS_PROXY', 'NO_PROXY', 'NODE_USE_ENV_PROXY')

function Set-UserAndProcessEnv {
    param([string]$Name, [AllowNull()][string]$Value)
    # User scope persists for future processes; Process scope takes effect now.
    [Environment]::SetEnvironmentVariable($Name, $Value, 'User')
    [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
}

function Notify-EnvironmentChange {
    # Ask Explorer and other interested applications to reload user environment.
    # Already running Node.js processes will still need to be restarted.
    try {
        if (-not ('NodeProxyEnvironmentNotifier' -as [type])) {
            Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class NodeProxyEnvironmentNotifier {
    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    public static extern IntPtr SendMessageTimeout(
        IntPtr hWnd, uint msg, IntPtr wParam, string lParam,
        uint flags, uint timeout, out IntPtr result);
}
'@
        }
        $result = [IntPtr]::Zero
        [void][NodeProxyEnvironmentNotifier]::SendMessageTimeout(
            [IntPtr]65535, 0x001A, [IntPtr]::Zero, 'Environment',
            0x0002, 2000, [ref]$result)
    }
    catch {
        Write-Warning "无法广播环境变量更新，请重新打开终端或重新登录 Windows：$($_.Exception.Message)"
    }
}

function Show-Status {
    Write-Host "`n当前用户的持久代理环境变量：" -ForegroundColor Cyan
    foreach ($name in $variableNames) {
        $value = [Environment]::GetEnvironmentVariable($name, 'User')
        if ([string]::IsNullOrEmpty($value)) { $value = '(未设置)' }
        Write-Host ('  {0} = {1}' -f $name, $value)
    }
    Write-Host "`n当前 PowerShell 进程的代理环境变量：" -ForegroundColor Cyan
    foreach ($name in $variableNames) {
        $value = [Environment]::GetEnvironmentVariable($name, 'Process')
        if ([string]::IsNullOrEmpty($value)) { $value = '(未设置)' }
        Write-Host ('  {0} = {1}' -f $name, $value)
    }
    $node = Get-Command node -ErrorAction SilentlyContinue
    if ($node) {
        $version = (& node --version 2>&1 | Out-String).Trim()
        Write-Host "`nNode.js 版本：$version" -ForegroundColor Cyan
        try {
            $versionNumber = [version]($version.TrimStart('v'))
            if (($versionNumber.Major -eq 24 -and $versionNumber -lt [version]'24.5.0') -or
                ($versionNumber.Major -lt 24 -and -not ($versionNumber.Major -eq 22 -and $versionNumber -ge [version]'22.21.0'))) {
                Write-Warning '该 Node.js 版本可能不支持内置代理，建议升级到 Node.js 24.5.0 或更高版本。'
            }
        }
        catch { }
    }
    else {
        Write-Warning '没有在 PATH 中找到 node.exe。'
    }
}

switch ($Mode) {
    'Enable' {
        $uri = $null
        if (-not [Uri]::TryCreate($ProxyUrl, [UriKind]::Absolute, [ref]$uri) -or
            $uri.Scheme -notin @('http', 'https') -or
            $uri.Port -le 0) {
            throw '代理地址必须是有效的 HTTP 或 HTTPS URL，例如 http://127.0.0.1:10808。'
        }
        foreach ($kv in @(
            @{ Name = 'HTTP_PROXY'; Value = $ProxyUrl },
            @{ Name = 'HTTPS_PROXY'; Value = $ProxyUrl },
            @{ Name = 'NO_PROXY'; Value = $NoProxy },
            @{ Name = 'NODE_USE_ENV_PROXY'; Value = '1' }
        )) {
            Set-UserAndProcessEnv -Name $kv.Name -Value $kv.Value
        }
        Notify-EnvironmentChange
        Write-Host "`n已启用并持久保存 Node.js 代理：$ProxyUrl" -ForegroundColor Green

        # Quick TCP-only availability check; does not prove HTTP proxy health.
        $tcpClient = New-Object System.Net.Sockets.TcpClient
        try {
            $asyncResult = $tcpClient.BeginConnect($uri.Host, $uri.Port, $null, $null)
            if ($asyncResult.AsyncWaitHandle.WaitOne(1500)) {
                $tcpClient.EndConnect($asyncResult)
                Write-Host '代理 TCP 端口可以连接。' -ForegroundColor Green
            }
            else {
                Write-Warning '代理 TCP 端口连接超时，请确认本地代理软件已启动。'
            }
        }
        catch {
            Write-Warning "代理 TCP 端口暂时无法连接：$($_.Exception.Message)"
        }
        finally {
            $tcpClient.Close()
        }
        Show-Status
        Write-Host "`n提示：新启动的 PowerShell/CMD/Node.js 将读取持久设置。" -ForegroundColor Yellow
        Write-Host '请重启已打开的 VS Code、终端或 Node.js 服务，以避免继承旧环境变量。' -ForegroundColor Yellow
    }
    'Disable' {
        foreach ($name in $variableNames) {
            Set-UserAndProcessEnv -Name $name -Value $null
        }
        Notify-EnvironmentChange
        Write-Host "`n已移除本脚本管理的四个用户级代理环境变量。" -ForegroundColor Green
        Write-Host '请重启旧终端和 Node.js 进程以完全生效。' -ForegroundColor Yellow
        Show-Status
    }
    'Status' {
        Show-Status
    }
}
