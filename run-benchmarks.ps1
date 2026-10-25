# Builds the Ledger service (running its tests), builds the JMH module, and runs the suite.
# Needs: JDK 25, Maven, Docker running (the service's integration tests use Testcontainers).
# Usage:  powershell -ExecutionPolicy Bypass -File run-benchmarks.ps1
# A full run takes about 3 minutes (about 2 to 3 of them are the benchmarks themselves).
# Output goes to benchmarks\results-latest.json so a casual run cannot overwrite
# benchmarks\results.json, the committed evidence for docs\benchmarks.md.
param(
    [string]$Output = "results-latest.json"
)
$ErrorActionPreference = "Continue"   # native tools write warnings to stderr; exit codes are checked explicitly below

docker info *> $null
if ($LASTEXITCODE -ne 0) { throw "Docker is not running; the service's integration tests need it." }

Write-Host "[1/3] Building and installing the service (tests run)..."
mvn clean install
if ($LASTEXITCODE -ne 0) { throw "Service build failed." }

Write-Host "[2/3] Building the benchmarks module..."
mvn -f benchmarks\pom.xml clean package
if ($LASTEXITCODE -ne 0) { throw "Benchmarks build failed." }

Write-Host "[3/3] Running LedgerBenchmarks (-prof gc)..."
Push-Location benchmarks
try {
    java -jar target\benchmarks.jar LedgerBenchmarks -prof gc -rf json -rff $Output
    if ($LASTEXITCODE -ne 0) { throw "Benchmark run failed." }
}
finally { Pop-Location }

Write-Host "Done. Results: benchmarks\$Output"


