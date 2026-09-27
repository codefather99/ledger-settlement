$lines = & "C:\Program Files\Java\jdk-25\bin\jfr.exe" print --events jdk.ThreadAllocationStatistics gc-baseline.jfr

$records = @()
$thread = $null
foreach ($line in $lines) {
    if ($line -match 'thread = "(.*?)"') { $thread = $matches[1] }
    if ($line -match 'allocated = ([\d.]+)\s*(bytes|kB|KB|MB|GB)?') {
        $val = [double]$matches[1]
        $unit = $matches[2]
        $bytes = switch ($unit) {
            "GB" { $val * 1GB }
            "MB" { $val * 1MB }
            "kB" { $val * 1KB }
            "KB" { $val * 1KB }
            default { $val }
        }
        $records += [PSCustomObject]@{ Thread = $thread; Bytes = $bytes }
    }
}

$totalDelta = ($records | Group-Object Thread | ForEach-Object {
    $vals = $_.Group.Bytes
    ($vals | Measure-Object -Maximum).Maximum - ($vals | Measure-Object -Minimum).Minimum
} | Measure-Object -Sum).Sum

"Total allocated across all threads: {0:N0} bytes ({1:N1} MB)" -f $totalDelta, ($totalDelta / 1MB)
"Over 600s -> {0:N2} MB/s" -f ($totalDelta / 1MB / 600)
