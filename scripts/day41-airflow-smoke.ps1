param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot/..").Path
$run = [guid]::NewGuid().ToString('N').Substring(0,10)
$project = "pymk-day41-$run"
$output = (New-Item -ItemType Directory -Path "$root/artifacts/day41-smoke/$run").FullName
$variables = @('PYMK_AIRFLOW_DB_PASSWORD','PYMK_AIRFLOW_ADMIN_PASSWORD','PYMK_AIRFLOW_UI_PASSWORD',
    'PYMK_AIRFLOW_JWT_SECRET','PYMK_AIRFLOW_FERNET_KEY','PYMK_AIRFLOW_PORT','COMPOSE_PROFILES')
$previous = @{}
foreach ($name in $variables) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
foreach ($name in $variables[0..3]) { [Environment]::SetEnvironmentVariable($name, [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N'), 'Process') }
$env:PYMK_AIRFLOW_FERNET_KEY = [Convert]::ToBase64String([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).Replace('+','-').Replace('/','_')
$env:COMPOSE_PROFILES = $null
$socket = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
$socket.Start(); $env:PYMK_AIRFLOW_PORT = [string]$socket.LocalEndpoint.Port; $socket.Stop()
$compose = @('compose','-p',$project,'-f',"$root/infra/docker-compose.yml",'--profile','airflow')
function Compose([string[]]$arguments) {
    & docker @compose @arguments
    if ($LASTEXITCODE -ne 0) { throw "Airflow Docker operation failed: $($arguments[0]); evidence $output" }
}
try {
    $base = & docker compose -f "$root/infra/docker-compose.yml" config --services
    if ($LASTEXITCODE -ne 0 -or @($base).Count -ne 2 -or 'postgres' -notin $base -or 'redis' -notin $base) { throw 'Default profile changed' }
    if (-not $SkipBuild) { Compose @('build','airflow-api') }
    # Parsing tests run with no network and without injecting service credentials.
    & docker run --rm --network none --entrypoint python pymk-airflow:3.2.2-day41 -m unittest discover -s /opt/pymk/tests -v *> "$output/contracts.log"
    if ($LASTEXITCODE -ne 0) { throw "DAG contract/import tests failed: $output/contracts.log" }
    Compose @('up','-d','--wait','--wait-timeout','240','airflow-api','airflow-scheduler','airflow-dag-processor')
    $apiId = & docker @compose ps -q airflow-api
    $dbId = & docker @compose ps -q airflow-db
    $api = (& docker inspect $apiId | ConvertFrom-Json)[0]
    $db = (& docker inspect $dbId | ConvertFrom-Json)[0]
    if ($api.Config.User -eq '0' -or $api.Config.User -eq 'root') { throw 'Airflow must not run as root' }
    if ($api.NetworkSettings.Ports.'8080/tcp'[0].HostIp -ne '127.0.0.1') { throw 'UI must be loopback-only' }
    if ($db.HostConfig.PortBindings.PSObject.Properties.Count -gt 0) { throw 'Metadata DB port exposed' }
    $role = & docker @compose exec -T airflow-db psql -U airflow_admin -d airflow -Atc "SELECT rolsuper, rolcreatedb, rolcreaterole FROM pg_roles WHERE rolname='airflow'"
    if ($LASTEXITCODE -ne 0 -or $role.Trim() -ne 'f|f|f') { throw 'Airflow metadata role is overprivileged' }
    Compose @('exec','-T','airflow-api','python','-m','pip','check')
    Compose @('exec','-T','airflow-api','/opt/pymk/trainer/bin/python','-m','pip','check')
    Compose @('exec','-T','airflow-api','/opt/pymk/trainer/bin/python','-c','import importlib.util; assert importlib.util.find_spec("airflow") is None')
    Compose @('exec','-T','airflow-api','python','-c','import importlib.util; from pathlib import Path; assert importlib.util.find_spec("pymk_training") is None; assert not Path("/opt/pymk/training/.venv").exists()')
    Compose @('exec','-T','airflow-api','python','/opt/pymk/smoke.py','run')
    & docker stats --no-stream --format '{{.Name}} CPU={{.CPUPerc}} Memory={{.MemUsage}}' $apiId $dbId (& docker @compose ps -q airflow-scheduler) (& docker @compose ps -q airflow-dag-processor) *> "$output/resources.log"
    if ($LASTEXITCODE -ne 0) { throw 'Resource capture failed' }
    # Recreate processes without deleting persistent volumes; completed run must survive.
    Compose @('stop','airflow-scheduler','airflow-dag-processor','airflow-api')
    Compose @('up','-d','--force-recreate','--wait','--wait-timeout','240','airflow-api','airflow-scheduler','airflow-dag-processor')
    Compose @('exec','-T','airflow-api','python','/opt/pymk/smoke.py','persisted')
    Write-Host "PASS: Day 41 isolated Airflow profile, contracts, actual scheduled subprocess runtime, authentication and persistence. Evidence: $output"
} finally {
    if ($project -notmatch '^pymk-day41-[a-f0-9]{10}$') { throw 'Unsafe cleanup target' }
    & docker @compose logs --no-color *> "$output/services.log"
    & docker @compose down -v --remove-orphans
    $cleanupFailed = $LASTEXITCODE -ne 0
    foreach ($name in $variables) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
    if ($cleanupFailed) { throw "Cleanup failed for isolated project $project" }
}
