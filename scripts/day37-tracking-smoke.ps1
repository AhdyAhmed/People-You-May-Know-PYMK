param([string]$Python = "$PSScriptRoot/../pymk-ml-training/.venv/Scripts/python.exe", [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot/..").Path
$run = [guid]::NewGuid().ToString('N').Substring(0, 10)
$project = "pymk-day37-$run"
$output = (New-Item -ItemType Directory -Path "$root/artifacts/day37-smoke/$run").FullName
$variables = @('PYMK_MLFLOW_DB_PASSWORD', 'PYMK_MLFLOW_ADMIN_PASSWORD', 'PYMK_MLFLOW_PORT', 'PYMK_TEST_TRACKING_URI', 'COMPOSE_PROFILES', 'MLFLOW_ENABLE_ASYNC_LOGGING')
$previous = @{}
foreach ($name in $variables) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$env:COMPOSE_PROFILES = $null
$env:MLFLOW_ENABLE_ASYNC_LOGGING = 'true' # Adapter must explicitly await every acknowledged write.
$env:PYMK_MLFLOW_DB_PASSWORD = [guid]::NewGuid().ToString('N')
$env:PYMK_MLFLOW_ADMIN_PASSWORD = [guid]::NewGuid().ToString('N')
$socket = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
$socket.Start(); $port = $socket.LocalEndpoint.Port; $socket.Stop()
$env:PYMK_MLFLOW_PORT = [string]$port
$uri = "http://127.0.0.1:$port"
$compose = @('compose', '-p', $project, '-f', "$root/infra/docker-compose.yml", '--profile', 'mlflow')
function Compose([string[]]$parameters) {
    & docker @compose @parameters
    if ($LASTEXITCODE -ne 0) { throw 'Day 37 Compose step failed' }
}
function Probe([string]$phase) {
    & $Python "$root/pymk-ml-training/tests/day37_tracking_probe.py" $phase --root $output --uri $uri *> "$output/$phase.log"
    if ($LASTEXITCODE -ne 0) { throw "Day 37 $phase probe failed; see $output/$phase.log" }
}
try {
    if (-not $SkipBuild) { Compose @('build', 'mlflow') }
    Compose @('up', '-d', '--wait', '--wait-timeout', '180', 'mlflow')
    Probe 'live'
    $env:PYMK_TEST_TRACKING_URI = $uri
    & $Python -m unittest discover -s "$root/pymk-ml-training/tests" -p test_tracking_commands.py -v *> "$output/commands.log"
    if ($LASTEXITCODE -ne 0) { throw "Tracked profile/L2/evaluation/blend parity checks failed; see $output/commands.log" }
    Write-Host 'PASS: all five CLIs published real MLflow runs; checked original numeric outputs, failed computations and not_met quality gate'
    Compose @('stop', 'mlflow')
    Probe 'outage'
    Compose @('up', '-d', '--wait', '--wait-timeout', '180', 'mlflow')
    Probe 'resume'
    Write-Host "PASS: MLflow outage returned nonzero, retained local ONNX output and resumed publication without training or duplicate acknowledged runs. Evidence: $output"
} finally {
    if ($project -notmatch '^pymk-day37-[a-f0-9]{10}$') { throw 'Unsafe cleanup target' }
    & docker @compose logs --no-color *> "$output/services.log"
    & docker @compose down -v --remove-orphans
    if ($LASTEXITCODE -ne 0) { Write-Warning "Cleanup failed for isolated project $project" }
    foreach ($name in $variables) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
