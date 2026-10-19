<#
.SYNOPSIS
Collect two separately executed 5-minute download benchmarks.
Run once for ClipFetch and once for qBittorrent, same public Torrent,
hardware/network/selected files, separate clean save directories.
The script NEVER starts/stops torrent tasks or changes client settings.
.EXAMPLE
$auth=@{Authorization="Bearer <token>"}
.\measure-5min.ps1 -Engine ClipFetch -Endpoint 'http://localhost:8082/api/magnet/native/worker-jobs/<job-uuid>/metrics' -Headers $auth
.EXAMPLE
$cookie=@{Cookie="SID=<qbt-web-ui-session>"}
.\measure-5min.ps1 -Engine qBittorrent -Endpoint 'http://localhost:8080/api/v2/torrents/info?hashes=<torrent-hash>' -Headers $cookie
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory=$true)][ValidateSet('ClipFetch','qBittorrent')][string]$Engine,
  [Parameter(Mandatory=$true)][string]$Endpoint,
  [hashtable]$Headers=@{},
  [ValidateRange(1,10)][int]$PollSeconds=5,
  [ValidateRange(300,300)][int]$Seconds=300,
  [string]$OutputDirectory=(Join-Path $PSScriptRoot 'results')
)
$ErrorActionPreference='Stop'
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$filename=Join-Path $OutputDirectory ($Engine+'-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'.csv')
$rows=[System.Collections.Generic.List[object]]::new()
$deadline=(Get-Date).ToUniversalTime().AddSeconds($Seconds)
while((Get-Date).ToUniversalTime() -lt $deadline){
  $stamp=(Get-Date).ToUniversalTime()
  $response=Invoke-RestMethod -Uri $Endpoint -Headers $Headers -TimeoutSec 8 -Method GET
  $datum=$response
  if($Engine -eq 'ClipFetch'){
    if($null -ne $response.data){$datum=$response.data}
    if($null -ne $response.result){$datum=$response.result}
    $queue=$datum.peerRequestQueue
    $row=[pscustomobject]@{
      utc=$stamp.ToString('o');engine=$Engine;state=[string]$datum.state
      payload=[double]$datum.receivedPayloadBytes
      durable=[double]$datum.verifiedBytes
      instantaneous=[double]$datum.networkBytesPerSecond
      queueInFlight=[int]$queue.outstandingBlocks
      queueCapacity=[int]$queue.capacityBlocks
      queueOccupancy=[int]$queue.occupancyPercent
      rttP95ms=[double]$queue.rttP95Millis
      openedSockets=[double]$datum.openedPeerSockets
      reusedSockets=[double]$datum.reusedPeerSockets
    }
  }else{
    $torrent=@($datum)[0]
    if($null -eq $torrent){throw 'qBittorrent returned an empty torrent list'}
    $row=[pscustomobject]@{
      utc=$stamp.ToString('o');engine=$Engine;state=[string]$torrent.state
      payload=[double]$torrent.downloaded
      durable=[double]$torrent.completed
      instantaneous=[double]$torrent.dlspeed
      queueInFlight='';queueCapacity='';queueOccupancy='';rttP95ms=''
      openedSockets='';reusedSockets=''
    }
  }
  $rows.Add($row)
  $secondsLeft=($deadline-(Get-Date).ToUniversalTime()).TotalSeconds
  if($secondsLeft -le 0){break}
  Start-Sleep -Milliseconds ([int]([math]::Min($PollSeconds,$secondsLeft)*1000))
}
$rows | Export-Csv -Path $filename -NoTypeInformation -Encoding UTF8
if($rows.Count -lt 2){throw 'Insufficient samples to compute a 5-minute rate'}
$first=$rows[0];$last=$rows[$rows.Count-1]
$actual=([DateTime]::Parse($last.utc)-[DateTime]::Parse($first.utc)).TotalSeconds
if($actual -le 0){throw 'Nonpositive measurement window'}
$payloadDelta=[math]::Max(0,$last.payload-$first.payload)
$verifiedDelta=[math]::Max(0,$last.durable-$first.durable)
[pscustomobject]@{
 engine=$Engine; samples=$rows.Count; observedSeconds=[math]::Round($actual,1)
 payloadBytes=$payloadDelta; durableBytes=$verifiedDelta
 payloadKiBps=[math]::Round($payloadDelta/$actual/1024,2)
 durableKiBps=[math]::Round($verifiedDelta/$actual/1024,2)
 resultCsv=$filename
} | Format-List
