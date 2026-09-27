$evts = & "C:\Program Files\Java\jdk-25\bin\jfr.exe" print --events jdk.ObjectAllocationSample gc-baseline.jfr

$records = New-Object System.Collections.Generic.List[PSCustomObject]
$ts = $null

foreach ($line in $evts) {
    if ($line -match "startTime = (\d\d):(\d\d):(\d\d)") {
        $ts = [int]$matches[1]*3600 + [int]$matches[2]*60 + [int]$matches[3]
    }
    if ($line -match "weight = ([\d.]+)\s*(bytes|kB|KB|MB|GB)?") {
        $val = [double]$matches[1]
        $unit = $matches[2]
        $bytes = 0.0
        if ($unit -eq "GB") { $bytes = $val * 1GB }
        elseif ($unit -eq "MB") { $bytes = $val * 1MB }
        elseif ($unit -eq "kB" -or $unit -eq "KB") { $bytes = $val * 1KB }
        else { $bytes = $val }
        $records.Add([PSCustomObject]@{ Time = $ts; Bytes = [double]$bytes })
    }
}

$start = ($records | Measure-Object -Property Time -Minimum).Minimum

$buckets = @{}
foreach ($r in $records) {
    $b = [math]::Floor(($r.Time - $start) / 60)
    if (-not $buckets.ContainsKey($b)) { $buckets[$b] = 0.0 }
    $buckets[$b] += $r.Bytes
}

$buckets.Keys | Sort-Object { [int]$_ } | ForEach-Object {
    "Minute {0}: {1:N1} MB" -f $_, ($buckets[$_] / 1MB)
}
