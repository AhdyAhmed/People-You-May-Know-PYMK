param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot/..").Path
$run = [guid]::NewGuid().ToString('N').Substring(0, 10)
$project = "pymk-day36-$run"
$restored = "pymk-day36-restore-$run"
$output = (New-Item -ItemType Directory -Path "$root/artifacts/day36-smoke/$run").FullName
$variables = @('PYMK_MLFLOW_DB_PASSWORD', 'PYMK_MLFLOW_ADMIN_PASSWORD', 'PYMK_MLFLOW_PORT')
$previous = @{}
foreach ($name in $variables) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$env:PYMK_MLFLOW_DB_PASSWORD = [guid]::NewGuid().ToString('N')
$env:PYMK_MLFLOW_ADMIN_PASSWORD = [guid]::NewGuid().ToString('N')
function FreePort {
    $socket = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $socket.Start()
    try { return $socket.LocalEndpoint.Port } finally { $socket.Stop() }
}
$env:PYMK_MLFLOW_PORT = [string](FreePort)
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
function Probe([string]$name, [string]$phase) {
    # Independent container uses pinned client and HTTP artifact proxy, no artifact volume mount.
    Compose $name @('run', '--rm', '--no-deps', '--entrypoint', 'python', 'mlflow-migrate',
        '/opt/pymk-mlflow/probe.py', $phase, '--experiment', "day36-$run")
}
try {
    DockerChecked @('compose', '-f', "$root/infra/docker-compose.yml", 'config', '--quiet')
    $base = & docker compose -f "$root/infra/docker-compose.yml" config --services
    if ($LASTEXITCODE -ne 0 -or @($base).Count -ne 2 -or 'postgres' -notin $base -or 'redis' -notin $base) { throw 'Base profile changed' }
    if (-not $SkipBuild) { Compose $project @('build', 'mlflow') }
    DockerChecked @('image', 'inspect', 'pymk-mlflow:3.17.1', '--format', '{{.Id}}')
    Compose $project @('up', '-d', '--wait', '--wait-timeout', '180', 'mlflow')
    Compose $project @('run', '--rm', '--no-deps', '--entrypoint', 'python', 'mlflow-migrate', '-m', 'pip', 'check')
    Compose $project @('run', '--rm', '--no-deps', '--entrypoint', 'python', 'mlflow-migrate', '-m', 'pip', 'freeze')
    if ((Invoke-WebRequest "http://127.0.0.1:$env:PYMK_MLFLOW_PORT/health").StatusCode -ne 200) { throw 'Host health failed' }
    if ((Invoke-WebRequest "http://127.0.0.1:$env:PYMK_MLFLOW_PORT/").StatusCode -ne 200) { throw 'UI failed' }
    $details = (& docker inspect (Container $project 'mlflow') | ConvertFrom-Json)[0]
    if ($details.NetworkSettings.Ports.'5000/tcp'[0].HostIp -ne '127.0.0.1') { throw 'UI is not loopback-only' }
    if ($details.Config.User -ne '10001:10001') { throw 'Server is not non-root' }
    $dbDetails = (& docker inspect (Container $project 'mlflow-db') | ConvertFrom-Json)[0]
    if ($dbDetails.HostConfig.PortBindings.PSObject.Properties.Count -gt 0) { throw 'Metadata DB port is published' }
    Probe $project 'write'
    Compose $project @('up', '-d', '--force-recreate', '--wait', '--wait-timeout', '180', 'mlflow-db', 'mlflow')
    Probe $project 'read'
    Write-Host 'PASS: base profile unchanged, loopback UI, non-root server, restricted DB role, pinned client, run/metric/artifact persistence after container recreation'

    # Quiesce all writers; this project has only this one server/client.
    Compose $project @('stop', 'mlflow')
    Compose $project @('exec', '-T', 'mlflow-db', 'pg_dump', '-U', 'mlflow_admin', '-d', 'mlflow', '-Fc', '-f', '/tmp/metadata.dump')
    DockerChecked @('cp', "$(Container $project 'mlflow-db'):/tmp/metadata.dump", "$output/metadata.dump")
    DockerChecked @('cp', "$(Container $project 'mlflow'):/mlartifacts", "$output/artifacts")
    Get-ChildItem "$output/artifacts" -Recurse -File | Get-FileHash -Algorithm SHA256 | Format-Table -AutoSize
    Get-FileHash "$output/metadata.dump" -Algorithm SHA256 | Format-Table -AutoSize

    # Restore into entirely different volumes, preserving originals for comparison.
    $env:PYMK_MLFLOW_PORT = [string](FreePort)
    Compose $restored @('up', '-d', '--wait', '--wait-timeout', '120', 'mlflow-db')
    DockerChecked @('cp', "$output/metadata.dump", "$(Container $restored 'mlflow-db'):/tmp/metadata.dump")
    Compose $restored @('exec', '-T', 'mlflow-db', 'pg_restore', '-U', 'mlflow_admin', '--role=mlflow', '-d', 'mlflow',
        '--clean', '--if-exists', '--no-owner', '--exit-on-error', '/tmp/metadata.dump')
    Compose $restored @('run', '--rm', '--no-deps', '-v', "${output}/artifacts:/restore:ro", '--entrypoint', 'sh', 'mlflow',
        '-c', 'cp -R /restore/. /mlartifacts/')
    Compose $restored @('run', '--rm', 'mlflow-migrate')
    Compose $restored @('run', '--rm', 'mlflow-migrate')
    Compose $restored @('up', '-d', '--wait', '--wait-timeout', '180', 'mlflow')
    Probe $restored 'read'
    Write-Host "PASS: coordinated metadata/artifact restore into fresh volumes and repeat metadata upgrade; original run ID and artifact bytes retained. Evidence: $output"
} finally {
    foreach ($name in @($project, $restored)) {
        if ($name -notmatch '^pymk-day36-(restore-)?[a-f0-9]{10}$') { throw 'Unsafe smoke cleanup target' }
        & docker compose -p $name -f "$root/infra/docker-compose.yml" --profile mlflow logs --no-color *> "$output/$name.log"
        & docker compose -p $name -f "$root/infra/docker-compose.yml" --profile mlflow down -v --remove-orphans
        if ($LASTEXITCODE -ne 0) { Write-Warning "Cleanup failed for isolated project $name" }
    }
    foreach ($name in $variables) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
