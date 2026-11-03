param([string]$Python = "$PSScriptRoot/../pymk-ml-training/.venv/Scripts/python.exe", [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot/..").Path
$run = [guid]::NewGuid().ToString('N').Substring(0, 10)
$project = "pymk-day38-$run"
$output = (New-Item -ItemType Directory -Path "$root/artifacts/day38-smoke/$run").FullName
$variables = @('PYMK_MLFLOW_DB_PASSWORD', 'PYMK_MLFLOW_ADMIN_PASSWORD', 'PYMK_MLFLOW_PORT', 'COMPOSE_PROFILES', 'MLFLOW_ENABLE_ASYNC_LOGGING')
$previous = @{}
foreach ($name in $variables) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$env:COMPOSE_PROFILES = $null
$env:MLFLOW_ENABLE_ASYNC_LOGGING = 'true'
$env:PYMK_MLFLOW_DB_PASSWORD = [guid]::NewGuid().ToString('N')
$env:PYMK_MLFLOW_ADMIN_PASSWORD = [guid]::NewGuid().ToString('N')
$socket = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
$socket.Start(); $port = $socket.LocalEndpoint.Port; $socket.Stop()
$env:PYMK_MLFLOW_PORT = [string]$port
$compose = @('compose', '-p', $project, '-f', "$root/infra/docker-compose.yml", '--profile', 'mlflow')
try {
    if (-not (Test-Path "$root/pymk-batch/target/pymk-batch.jar")) { throw 'Build the Java reactor first' }
    if (-not $SkipBuild) {
        & docker @compose build mlflow
        if ($LASTEXITCODE -ne 0) { throw 'MLflow image build failed' }
    }
    & docker @compose up -d --wait --wait-timeout 180 mlflow
    if ($LASTEXITCODE -ne 0) { throw 'MLflow startup failed' }
    & $Python "$root/pymk-ml-training/tests/day38_registry_probe.py" --root $output --uri "http://127.0.0.1:$port" --jar "$root/pymk-batch/target/pymk-batch.jar" *> "$output/probe.log"
    if ($LASTEXITCODE -ne 0) { throw "Day 38 registry probe failed; see $output/probe.log" }
    Write-Host "PASS: Day 38 real model registry, production Java parity, immutable lineage, interrupted upload, alias isolation and corruption rejection. Evidence: $output"
} finally {
    if ($project -notmatch '^pymk-day38-[a-f0-9]{10}$') { throw 'Unsafe cleanup target' }
    & docker @compose logs --no-color *> "$output/services.log"
    & docker @compose down -v --remove-orphans
    $cleanupFailed = $LASTEXITCODE -ne 0
    foreach ($name in $variables) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
    if ($cleanupFailed) { throw "Cleanup failed for isolated project $project" }
}
