$out = & "C:\Program Files\Java\jdk-25\bin\jfr.exe" print --events jdk.ObjectAllocationSample gc-baseline.jfr
$total = 0.0
foreach ($line in $out) {
    $t = $line.Trim()
    if ($t -match "^weight = ([\d.]+)\s*(bytes|kB|KB|MB|GB)?") {
        $val = [double]$matches[1]
        $unit = $matches[2]
        if ($unit -eq "GB") { $total += $val * 1GB }
        elseif ($unit -eq "MB") { $total += $val * 1MB }
        elseif ($unit -eq "kB" -or $unit -eq "KB") { $total += $val * 1KB }
        else { $total += $val }
    }
}
"Total: {0:N0} bytes ({1:N1} MB)" -f $total, ($total/1MB)
"Rate: {0:N2} MB/s over 600s" -f ($total/1MB/600)
