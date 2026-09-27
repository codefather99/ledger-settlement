$out = & "C:\Program Files\Java\jdk-25\bin\jfr.exe" print --events jdk.ObjectAllocationSample gc-tuned.jfr
$pairs = New-Object System.Collections.Generic.List[PSCustomObject]
$cls = $null
foreach ($line in $out) {
    $t = $line.Trim()
    if ($t -match "^objectClass = (.+)$") { $cls = ($matches[1] -replace ' \(.*', '') }
    elseif ($t -match "^weight = ([\d.]+)\s*(bytes|kB|KB|MB|GB)?") {
        $val = [double]$matches[1]
        $unit = $matches[2]
        $bytes = 0.0
        if ($unit -eq "GB") { $bytes = $val * 1GB }
        elseif ($unit -eq "MB") { $bytes = $val * 1MB }
        elseif ($unit -eq "kB" -or $unit -eq "KB") { $bytes = $val * 1KB }
        else { $bytes = $val }
        $pairs.Add([PSCustomObject]@{ Class = $cls; Bytes = $bytes })
    }
}
$pairs | Group-Object Class | ForEach-Object {
    [PSCustomObject]@{ Class = $_.Name; TotalMB = [math]::Round((($_.Group | Measure-Object Bytes -Sum).Sum)/1MB, 2) }
} | Sort-Object TotalMB -Descending | Select-Object -First 10 | Format-Table -AutoSize
