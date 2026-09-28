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

"Total events parsed: $($records.Count)"
"Unique threads seen: $(($records | Select-Object -ExpandProperty Thread -Unique).Count)"
""
$records | Select-Object -ExpandProperty Thread -Unique | Sort-Object
""
$records | Group-Object Thread | ForEach-Object {
    [PSCustomObject]@{
        Thread = $_.Name
        Samples = $_.Count
        MinMB = [math]::Round((($_.Group.Bytes | Measure-Object -Minimum).Minimum)/1MB, 2)
        MaxMB = [math]::Round((($_.Group.Bytes | Measure-Object -Maximum).Maximum)/1MB, 2)
    }
} | Sort-Object MaxMB -Descending | Format-Table -AutoSize
