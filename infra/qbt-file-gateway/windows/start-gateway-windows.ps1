# Run: powershell -NoProfile -ExecutionPolicy Bypass -File .\start-gateway-windows.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$config = Join-Path $root 'gateway-windows.conf'
if (-not (Test-Path -LiteralPath $config)) { throw "Missing config: $config" }
$settings = @{}
foreach ($line in Get-Content -LiteralPath $config -Encoding UTF8) {
    $line = $line.Trim()
    if (-not $line -or $line.StartsWith('#')) { continue }
    $parts = $line.Split('=', 2)
    if ($parts.Count -ne 2) { throw "Invalid config line: $line" }
    $key = $parts[0].Trim()
    if (-not $key.StartsWith('QBT_FILE_')) { throw "Unexpected config key: $key" }
    $settings[$key] = $parts[1].Trim()
}
foreach ($required in @('QBT_FILE_ROOT', 'QBT_FILE_GATEWAY_SECRET')) {
    if (-not $settings.ContainsKey($required)) { throw "Missing key: $required" }
}
if ($settings['QBT_FILE_GATEWAY_SECRET'].Length -lt 32 -or
    $settings['QBT_FILE_GATEWAY_SECRET'].StartsWith('CHANGE_ME')) {
    throw 'Set a random secret at least 32 characters long before starting.'
}
if (-not (Test-Path -LiteralPath $settings['QBT_FILE_ROOT'] -PathType Container)) {
    throw "qBittorrent download folder not found: $($settings['QBT_FILE_ROOT'])"
}
foreach ($key in $settings.Keys) {
    [Environment]::SetEnvironmentVariable($key, $settings[$key], 'Process')
}
Write-Host "Starting ClipFetch file gateway at $($settings['QBT_FILE_GATEWAY_BIND']):$($settings['QBT_FILE_GATEWAY_PORT'])"
Write-Host 'Stop with Ctrl+C.'
Push-Location $root
try {
    python -u (Join-Path $root 'gateway.py')
    if ($LASTEXITCODE -ne 0) { throw "Gateway exited with code $LASTEXITCODE" }
} finally { Pop-Location }
