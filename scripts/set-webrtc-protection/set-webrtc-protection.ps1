#requires -version 5.1
<#
.SYNOPSIS
  WebRTC privacy protection for Google Chrome and Microsoft Edge on Windows.
.DESCRIPTION
  Install: back up the current HKCU browser policies, set restrictive WebRTC
           policies, and block browser UDP at Windows Firewall.
  Check:   inspect registry policy values and application firewall rules.
  Remove:  restore previous HKCU policy values and remove only our firewall rules.

  This is browser-specific protection; it does not configure v2rayN routing,
  DNS, IPv6, other browsers, or other applications.
.EXAMPLE
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Set-WebRTC-Protection.ps1 -Action Install
.EXAMPLE
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Set-WebRTC-Protection.ps1 -Action Check
.EXAMPLE
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Set-WebRTC-Protection.ps1 -Action Remove
#>
[CmdletBinding()]
param(
    [ValidateSet('Install', 'Check', 'Remove')]
    [string]$Action = 'Install'
)

$ErrorActionPreference = 'Stop'
$rulePrefix = 'WebRtcGuard-UDP-'
$stateDir = Join-Path $env:APPDATA 'WebRtcGuard'
$backupFile = Join-Path $stateDir 'registry-backup.json'
$handling = 'disable_non_proxied_udp'
$urlHandling = '[{"url":"*","handling":"disable_non_proxied_udp"}]'

$targets = @(
    [pscustomobject]@{
        Browser = 'Chrome'
        Exe = 'chrome.exe'
        RegistryPath = 'HKCU:\Software\Policies\Google\Chrome'
        RegistryMachinePath = 'HKLM:\SOFTWARE\Policies\Google\Chrome'
        Values = @('WebRtcIPHandling', 'WebRtcIPHandlingUrl')
    },
    [pscustomobject]@{
        Browser = 'Edge'
        Exe = 'msedge.exe'
        RegistryPath = 'HKCU:\Software\Policies\Microsoft\Edge'
        RegistryMachinePath = 'HKLM:\SOFTWARE\Policies\Microsoft\Edge'
        Values = @('WebRtcLocalhostIpHandling', 'WebRtcIPHandlingUrl')
    }
)

function Get-DesiredValue([string]$name) {
    if ($name -eq 'WebRtcIPHandlingUrl') { return $urlHandling }
    return $handling
}

function Get-PolicyValue([string]$registryPath, [string]$name) {
    $key = Get-Item -LiteralPath $registryPath -ErrorAction SilentlyContinue
    if (($null -ne $key) -and ($key.GetValueNames() -contains $name)) {
        return [pscustomobject]@{ Present = $true; Value = [string]$key.GetValue($name) }
    }
    return [pscustomobject]@{ Present = $false; Value = $null }
}

function Test-Administrator {
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($id)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Get-BrowserPaths([string]$exeName) {
    $candidates = New-Object 'System.Collections.Generic.List[string]'
    $browser = if ($exeName -eq 'chrome.exe') { 'Chrome' } else { 'Edge' }
    $vendor = if ($browser -eq 'Chrome') { 'Google\Chrome\Application' } else { 'Microsoft\Edge\Application' }

    # Already-running browser (also detects nonstandard installations).
    Get-Process -Name ([IO.Path]::GetFileNameWithoutExtension($exeName)) -ErrorAction SilentlyContinue | ForEach-Object {
        try { if ($_.Path) { [void]$candidates.Add($_.Path) } } catch { }
    }

    # App Paths registrations.
    foreach ($root in @('HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths',
                         'HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths')) {
        $reg = Get-Item -LiteralPath (Join-Path $root $exeName) -ErrorAction SilentlyContinue
        if ($null -ne $reg) {
            $value = [string]$reg.GetValue('')
            if ($value) { [void]$candidates.Add($value.Trim('"')) }
        }
    }

    # Standard per-machine and per-user install paths.
    foreach ($base in @($env:ProgramFiles, ${env:ProgramFiles(x86)}, $env:LOCALAPPDATA)) {
        if ($base) { [void]$candidates.Add((Join-Path (Join-Path $base $vendor) $exeName)) }
    }

    @($candidates | Where-Object { $_ -and (Test-Path -LiteralPath $_ -PathType Leaf) } |
      ForEach-Object { (Get-Item -LiteralPath $_).FullName } | Sort-Object -Unique)
}

function Get-ShortHash([string]$value) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes($value.ToLowerInvariant())
        $hash = [BitConverter]::ToString($sha.ComputeHash($bytes)).Replace('-', '')
        return $hash.Substring(0, 12)
    } finally { $sha.Dispose() }
}

function Install-Policies {
    if (-not (Test-Path -LiteralPath $backupFile -PathType Leaf)) {
        New-Item -Path $stateDir -ItemType Directory -Force | Out-Null
        $entries = @(
            foreach ($target in $targets) {
                foreach ($name in $target.Values) {
                    $old = Get-PolicyValue $target.RegistryPath $name
                    [pscustomobject]@{
                        Path = $target.RegistryPath
                        Name = $name
                        Present = [bool]$old.Present
                        Value = $old.Value
                    }
                }
            }
        )
        ConvertTo-Json -InputObject $entries -Depth 4 |
          Set-Content -LiteralPath $backupFile -Encoding UTF8
        Write-Host "Backup saved: $backupFile" -ForegroundColor Cyan
    } else {
        Write-Host "Existing backup preserved: $backupFile" -ForegroundColor Cyan
    }

    foreach ($target in $targets) {
        New-Item -Path $target.RegistryPath -Force | Out-Null
        foreach ($name in $target.Values) {
            $value = Get-DesiredValue $name
            New-ItemProperty -Path $target.RegistryPath -Name $name -Value $value `
                -PropertyType String -Force | Out-Null
            Write-Host "Policy set: $($target.Browser) / $name"
        }
    }
}

function Install-UDPFirewall {
    foreach ($target in $targets) {
        $paths = @(Get-BrowserPaths $target.Exe)
        if ($paths.Count -eq 0) {
            Write-Warning "Cannot find $($target.Exe). No firewall rule created for this browser."
            continue
        }
        foreach ($path in $paths) {
            $name = "$rulePrefix$($target.Browser)-$(Get-ShortHash $path)"
            $existing = Get-NetFirewallRule -Name $name -ErrorAction SilentlyContinue
            if ($null -ne $existing) {
                # Bring our existing rule back to an enabled state.
                $existing | Set-NetFirewallRule -Enabled True -Action Block -Profile Any
                Write-Host "Firewall rule already exists: $path"
            } else {
                New-NetFirewallRule -Name $name -DisplayName "WebRtcGuard - Block $($target.Browser) UDP" `
                    -Description "Browser-only outbound UDP block; managed by Set-WebRTC-Protection.ps1" `
                    -Direction Outbound -Action Block -Enabled True -Profile Any `
                    -Protocol UDP -Program $path | Out-Null
                Write-Host "Blocked browser UDP: $path" -ForegroundColor Green
            }
        }
    }
}

function Show-Status {
    Write-Host "`n--- Registry policy values (written != applied) ---" -ForegroundColor Cyan
    foreach ($target in $targets) {
        foreach ($name in $target.Values) {
            $expected = Get-DesiredValue $name
            $userValue = Get-PolicyValue $target.RegistryPath $name
            $machineValue = Get-PolicyValue $target.RegistryMachinePath $name
            $flag = if ($userValue.Present -and $userValue.Value -eq $expected) { 'EXPECTED' } else { 'MISSING/DIFFERENT' }
            Write-Host "$($target.Browser) $name [$flag]"
            Write-Host "  HKCU: $(if ($userValue.Present) { $userValue.Value } else { '(not set)' })"
            if ($machineValue.Present) {
                Write-Warning "  HKLM policy also exists: $($machineValue.Value) (check browser policy precedence)"
            }
        }
    }
    Write-Host "`n--- Outbound browser UDP firewall rules ---" -ForegroundColor Cyan
    $rules = @(Get-NetFirewallRule -Name "$rulePrefix*" -ErrorAction SilentlyContinue)
    if ($rules.Count -eq 0) { Write-Warning 'No WebRtcGuard firewall rules found.' }
    foreach ($r in $rules) {
        $appFilter = $r | Get-NetFirewallApplicationFilter
        Write-Host "$($r.Name): $($r.Enabled) / $($r.Action) / $($appFilter.Program)"
    }
    Write-Host "`n--- Firewall profile enablement ---" -ForegroundColor Cyan
    $profiles = @(Get-NetFirewallProfile)
    $profiles | Select-Object Name, Enabled | Format-Table -AutoSize
    $disabled = @($profiles | Where-Object { $_.Enabled -ne 'True' -and $_.Enabled -ne $true })
    if ($disabled.Count -gt 0) {
        Write-Warning ('Firewall is DISABLED for profile(s): ' + (($disabled | ForEach-Object Name) -join ', ') + '. Browser UDP block rules cannot protect traffic on disabled profiles.')
    }
    Write-Host 'Browser validation: chrome://policy and edge://policy (look for OK/no conflicts).'
    Write-Host 'Network validation: https://browserleaks.com/webrtc (compare against your real public IP).'
}

function Remove-Protection {
    if (Test-Path -LiteralPath $backupFile -PathType Leaf) {
        # PowerShell 5.1: wrapping ConvertFrom-Json in @(... | ...) can yield
        # a single nested Object[]. That makes $entry.Name an Object[] and
        # causes New-ItemProperty -Name to fail. Parse first, then iterate.
        $json = Get-Content -LiteralPath $backupFile -Raw -Encoding UTF8
        $entries = ConvertFrom-Json -InputObject $json
        foreach ($entry in $entries) {
            if ($entry -isnot [pscustomobject] -or $entry.Name -is [array] -or
                [string]::IsNullOrWhiteSpace([string]$entry.Name)) {
                throw 'Invalid registry backup entry: expected one policy name per object.'
            }
            if ($entry.Present) {
                New-Item -Path $entry.Path -Force | Out-Null
                New-ItemProperty -Path $entry.Path -Name $entry.Name -Value ([string]$entry.Value) `
                    -PropertyType String -Force | Out-Null
            } else {
                Remove-ItemProperty -Path $entry.Path -Name $entry.Name -ErrorAction SilentlyContinue
            }
        }
        Remove-Item -LiteralPath $backupFile -Force
        Write-Host 'Original HKCU policy values restored.' -ForegroundColor Green
    } else {
        Write-Warning 'No policy backup found; registry policies were left unchanged.'
    }
    Get-NetFirewallRule -Name "$rulePrefix*" -ErrorAction SilentlyContinue |
        Remove-NetFirewallRule
    Write-Host 'WebRtcGuard firewall rules removed.' -ForegroundColor Green
}

try {
    switch ($Action) {
        'Install' {
            if (-not (Test-Administrator)) {
                throw 'Run Windows PowerShell as Administrator for Install (needed to create outbound firewall rules).'
            }
            Install-Policies
            Install-UDPFirewall
            Show-Status
            Write-Host "`nClose ALL Chrome/Edge windows, relaunch, check browser policies, then run the leak test." -ForegroundColor Yellow
        }
        'Check' { Show-Status }
        'Remove' {
            if (-not (Test-Administrator)) { throw 'Run Windows PowerShell as Administrator for Remove.' }
            Remove-Protection
            Write-Host 'Close and reopen Chrome and Edge.' -ForegroundColor Yellow
        }
    }
} catch {
    # Show the ORIGINAL failure site, not just this catch block.
    [Console]::Error.WriteLine("WebRtcGuard failed: $($_.Exception.Message)")
    [Console]::Error.WriteLine("Location: $($_.InvocationInfo.PositionMessage)")
    [Console]::Error.WriteLine("Stack: $($_.ScriptStackTrace)")
    exit 1
}
