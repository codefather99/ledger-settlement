$results = Get-Content results.json -Raw | ConvertFrom-Json
foreach ($b in $results) {
    $name = $b.benchmark.Split('.')[-1]
    $p = $b.primaryMetric
    $alloc = ($b.secondaryMetrics.PSObject.Properties | Where-Object { $_.Name -like '*gc.alloc.rate.norm' } | Select-Object -First 1).Value

    $timeForks = @()
    foreach ($fork in $p.rawData) { $timeForks += [math]::Round(($fork | Measure-Object -Average).Average, 5) }
    $allocForks = @()
    foreach ($fork in $alloc.rawData) { $allocForks += [math]::Round(($fork | Measure-Object -Average).Average, 3) }

    "== $name"
    "  time:  {0} +/- {1} {2}   CI [{3}, {4}]" -f $p.score, $p.scoreError, $p.scoreUnit, $p.scoreConfidence[0], $p.scoreConfidence[1]
    "  time per fork (mean): " + ($timeForks -join ', ')
    "  alloc: {0} +/- {1} {2}" -f $alloc.score, $alloc.scoreError, $alloc.scoreUnit
    "  alloc per fork (mean): " + ($allocForks -join ', ')
    "  forks={0} warmup={1} measurement={2}" -f $b.forks, $b.warmupIterations, $b.measurementIterations
}