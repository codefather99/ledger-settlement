# Cold-start run: start the jar fresh, attach Flight Recorder before any request, run the
# 200-user load profile, stop the recording, count pinned events, stop the service.
# Usage: powershell -ExecutionPolicy Bypass -File perf\cold-run.ps1 -Jar <path-to-jar> -Label <name>
param(
  [Parameter(Mandatory)][string]$Jar,
  [Parameter(Mandatory)][string]$Label
)
$ErrorActionPreference = "Stop"
$jcmd = "C:\Program Files\Java\jdk-25\bin\jcmd.exe"
$jfr  = "C:\Program Files\Java\jdk-25\bin\jfr.exe"
$java = "C:\Program Files\Java\jdk-25\bin\java.exe"   # the real JVM, not the PATH launcher stub
$out  = "perf\results"
New-Item -ItemType Directory -Force $out | Out-Null

if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) {
  throw "port 8080 is already in use - stop the running service first"
}

$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:5434/ledger"
$env:SPRING_DATASOURCE_USERNAME = "ledger"
$env:SPRING_DATASOURCE_PASSWORD = "ledger"

Start-Process $java -ArgumentList "-jar", $Jar -WindowStyle Hidden `
  -RedirectStandardOutput "$out\$Label-service.log" -RedirectStandardError "$out\$Label-service.err" | Out-Null
$started = $false
for ($i = 0; $i -lt 90; $i++) {
  Start-Sleep -Seconds 1
  if (Select-String -Path "$out\$Label-service.log" -Pattern "Started LedgerApplication" -Quiet) { $started = $true; break }
}
if (-not $started) { throw "service did not start - see $out\$Label-service.log" }

$svcPid = (Get-NetTCPConnection -LocalPort 8080 -State Listen).OwningProcess | Select-Object -First 1
"Service PID: $svcPid"
try {
  & $jcmd $svcPid JFR.start name=$Label settings=profile filename="$out\$Label.jfr"
  k6 run --summary-export="$out\$Label.json" perf\threads-load.js 2> "$out\$Label.err" |
    Tee-Object -FilePath "$out\$Label.txt" | Out-Null
  & $jcmd $svcPid JFR.stop name=$Label
} finally {
  Stop-Process -Id $svcPid -Force
}

if (-not (Test-Path "$out\$Label.jfr")) { throw "no recording was written - this run is invalid" }
"== $Label"
"Pinned events: " + (& $jfr print --events jdk.VirtualThreadPinned "$out\$Label.jfr" | Select-String "jdk.VirtualThreadPinned" | Measure-Object).Count
Select-String -Path "$out\$Label.txt" -Pattern "phase:steady \}" | ForEach-Object { $_.Line.Trim() }
"Refused connections: " + (Select-String -Path "$out\$Label.err" -Pattern "actively refused" | Measure-Object).Count