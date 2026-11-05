param([string]$Python = "$PSScriptRoot/../pymk-ml-training/.venv/Scripts/python.exe", [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot/..").Path
$run = [guid]::NewGuid().ToString('N').Substring(0, 10)
$project = "pymk-day40-$run"
$restored = "pymk-day40-restore-$run"
$output = (New-Item -ItemType Directory -Path "$root/artifacts/day40-smoke/$run").FullName
$variables = @('PYMK_MLFLOW_DB_PASSWORD', 'PYMK_MLFLOW_ADMIN_PASSWORD', 'PYMK_MLFLOW_PORT', 'COMPOSE_PROFILES',
    'MLFLOW_ENABLE_ASYNC_LOGGING', 'MLFLOW_HTTP_REQUEST_MAX_RETRIES', 'MLFLOW_HTTP_REQUEST_TIMEOUT')
$previous = @{}
foreach ($name in $variables) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$env:COMPOSE_PROFILES = $null
$env:MLFLOW_ENABLE_ASYNC_LOGGING = 'true'
$env:MLFLOW_HTTP_REQUEST_MAX_RETRIES = '0'
$env:MLFLOW_HTTP_REQUEST_TIMEOUT = '5'
$env:PYMK_MLFLOW_DB_PASSWORD = [guid]::NewGuid().ToString('N')
$env:PYMK_MLFLOW_ADMIN_PASSWORD = [guid]::NewGuid().ToString('N')
$socket = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
$socket.Start(); $port = $socket.LocalEndpoint.Port; $socket.Stop()
$env:PYMK_MLFLOW_PORT = [string]$port
function DockerChecked([string[]]$arguments) {
    & docker @arguments
    if ($LASTEXITCODE -ne 0) { throw "Docker operation failed: $($arguments[0])" }
}
function Compose([string]$name, [string[]]$arguments) {
    DockerChecked (@('compose', '-p', $name, '-f', "$root/infra/docker-compose.yml", '--profile', 'mlflow') + $arguments)
}
function Container([string]$name, [string]$service) {
    $id = & docker compose -p $name -f "$root/infra/docker-compose.yml" --profile mlflow ps -aq $service
    if ($LASTEXITCODE -ne 0 -or -not $id -or @($id).Count -ne 1) { throw "No unique container for $service" }
    return $id.Trim()
}
function Probe([string]$phase, [string[]]$extra = @()) {
    & $Python "$root/pymk-ml-training/tests/day40_lifecycle_probe.py" $phase --root $output --uri "http://127.0.0.1:$port" --jar "$root/pymk-batch/target/pymk-batch.jar" @extra *> "$output/$phase.log"
    if ($LASTEXITCODE -ne 0) { throw "Lifecycle phase $phase failed; see $output/$phase.log" }
    Write-Host "PASS: lifecycle phase $phase"
}
try {
    if (-not (Test-Path "$root/pymk-batch/target/pymk-batch.jar")) { throw 'Build the Java reactor first' }
    if (-not $SkipBuild) { Compose $project @('build', 'mlflow') }
    Compose $project @('up', '-d', '--wait', '--wait-timeout', '180', 'mlflow')
    Probe 'seed'
    Probe 'artifact-outage' @('--container', (Container $project 'mlflow'))
    # This isolated server has no other writers. Stop it before copying either storage plane.
    Compose $project @('stop', 'mlflow')
    Probe 'outage'
    Compose $project @('exec', '-T', 'mlflow-db', 'pg_dump', '-U', 'mlflow_admin', '-d', 'mlflow', '-Fc', '-f', '/tmp/metadata.dump')
    DockerChecked @('cp', "$(Container $project 'mlflow-db'):/tmp/metadata.dump", "$output/metadata.dump")
    DockerChecked @('cp', "$(Container $project 'mlflow'):/mlartifacts", "$output/artifacts")
    Probe 'backup'
    Probe 'verify-backup'
    # Fresh volumes, SAME tracking URI: destination identity is part of immutable publication provenance.
    Compose $restored @('up', '-d', '--wait', '--wait-timeout', '120', 'mlflow-db')
    DockerChecked @('cp', "$output/metadata.dump", "$(Container $restored 'mlflow-db'):/tmp/metadata.dump")
    Compose $restored @('exec', '-T', 'mlflow-db', 'pg_restore', '-U', 'mlflow_admin', '--role=mlflow', '-d', 'mlflow',
        '--clean', '--if-exists', '--no-owner', '--exit-on-error', '/tmp/metadata.dump')
    Compose $restored @('run', '--rm', '--no-deps', '-v', "${output}/artifacts:/restore:ro", '--entrypoint', 'sh', 'mlflow',
        '-c', 'cp -R /restore/. /mlartifacts/')
    Compose $restored @('run', '--rm', 'mlflow-migrate')
    Compose $restored @('up', '-d', '--wait', '--wait-timeout', '180', 'mlflow')
    Probe 'restored'
    Write-Host "PASS: Day 40 outages, duplicate retry, coordinated restore, exact candidate reproduction and corruption rejection. Evidence: $output"
} finally {
    $cleanupFailed = $false
    foreach ($name in @($project, $restored)) {
        if ($name -notmatch '^pymk-day40-(restore-)?[a-f0-9]{10}$') { throw 'Unsafe smoke cleanup target' }
        & docker compose -p $name -f "$root/infra/docker-compose.yml" --profile mlflow logs --no-color *> "$output/$name.log"
        & docker compose -p $name -f "$root/infra/docker-compose.yml" --profile mlflow down -v --remove-orphans
        if ($LASTEXITCODE -ne 0) { $cleanupFailed = $true }
    }
    foreach ($name in $variables) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
    if ($cleanupFailed) { throw 'Isolated Day 40 cleanup failed; inspect the recorded project IDs' }
}
