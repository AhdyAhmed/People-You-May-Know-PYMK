param([switch]$SkipBuild, [switch]$IncludeDay43, [switch]$IncludeDay44)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot/..").Path
$run = [guid]::NewGuid().ToString('N').Substring(0,10)
$project = "pymk-day42-$run"
$output = (New-Item -ItemType Directory -Path "$root/artifacts/day42-smoke/$run").FullName
$variables = @('PYMK_AIRFLOW_DB_PASSWORD','PYMK_AIRFLOW_ADMIN_PASSWORD','PYMK_AIRFLOW_UI_PASSWORD',
    'PYMK_AIRFLOW_JWT_SECRET','PYMK_AIRFLOW_FERNET_KEY','PYMK_AIRFLOW_PORT','COMPOSE_PROFILES',
    'PYMK_MLFLOW_DB_PASSWORD','PYMK_MLFLOW_ADMIN_PASSWORD','PYMK_MLFLOW_PORT',
    'PYMK_WORKFLOW_DB_PASSWORD','PYMK_WORKFLOW_DB_USER','PYMK_WORKFLOW_JDBC_URL','PYMK_WORKFLOW_ALLOW_ACTIVATION')
$previous = @{}
foreach ($name in $variables) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
foreach ($name in @('PYMK_AIRFLOW_DB_PASSWORD','PYMK_AIRFLOW_ADMIN_PASSWORD','PYMK_AIRFLOW_UI_PASSWORD',
    'PYMK_AIRFLOW_JWT_SECRET','PYMK_MLFLOW_DB_PASSWORD','PYMK_MLFLOW_ADMIN_PASSWORD','PYMK_WORKFLOW_DB_PASSWORD')) {
    [Environment]::SetEnvironmentVariable($name, [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N'), 'Process')
}
$env:PYMK_AIRFLOW_FERNET_KEY = [Convert]::ToBase64String([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).Replace('+','-').Replace('/','_')
$env:PYMK_WORKFLOW_DB_USER = 'pymk'
$env:PYMK_WORKFLOW_JDBC_URL = 'jdbc:postgresql://workflow-postgres:5432/pymk'
$env:PYMK_WORKFLOW_ALLOW_ACTIVATION = 'false'
$env:COMPOSE_PROFILES = $null
function FreePort {
    $socket = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $socket.Start()
    try { return $socket.LocalEndpoint.Port } finally { $socket.Stop() }
}
$env:PYMK_AIRFLOW_PORT = [string](FreePort)
$env:PYMK_MLFLOW_PORT = [string](FreePort)
$compose = @('compose','-p',$project,'-f',"$root/infra/docker-compose.yml",'-f',"$root/infra/airflow-workflows.yml",
    '-f',"$root/infra/airflow-workflows-smoke.yml",'--profile','airflow','--profile','mlflow')
function Compose([string[]]$arguments) {
    & docker @compose @arguments
    if ($LASTEXITCODE -ne 0) { throw "Day 42 Docker operation failed: $($arguments[0]); evidence $output" }
}
try {
    if (-not $SkipBuild) { Compose @('build','airflow-api','mlflow') }
    & docker run --rm --network none --entrypoint python pymk-airflow:3.2.2-day41 -m unittest discover -s /opt/pymk/tests -v *> "$output/contracts.log"
    if ($LASTEXITCODE -ne 0) { throw "Offline tests failed: $output/contracts.log" }
    Compose @('up','-d','--wait','--wait-timeout','240','workflow-postgres','mlflow','airflow-api','airflow-scheduler','airflow-dag-processor')
    $workerArguments = @('exec','-T','airflow-api','python','/opt/pymk/workflow_smoke.py')
    if ($IncludeDay44) { $workerArguments += '--day44' }
    elseif ($IncludeDay43) { $workerArguments += '--day43' }
    Compose $workerArguments
    if ($IncludeDay44) {
        Compose @('restart','airflow-scheduler')
        Compose @('up','-d','--wait','--wait-timeout','120','airflow-scheduler')
        Compose @('exec','-T','airflow-api','python','/opt/pymk/recovery_smoke.py')
    }
    Compose @('exec','-T','airflow-api','python','/opt/pymk/smoke.py','run')
    $scope = if ($IncludeDay44) { 'Days 41-44 workflows and recovery' } elseif ($IncludeDay43) { 'Days 41-43 workflows, reconciliation and historical backfills' } else { 'Day 42 scheduled workflows and Day 41 regression' }
    Write-Host "PASS: $scope. Evidence: $output"
} finally {
    if ($project -notmatch '^pymk-day42-[a-f0-9]{10}$') { throw 'Unsafe cleanup target' }
    & docker @compose logs --no-color *> "$output/services.log"
    # Preserve worker logs, receipts, and reports before removing this disposable project's volumes.
    $apiId = & docker @compose ps -aq airflow-api
    if ($apiId) {
        & docker cp "${apiId}:/opt/airflow/artifacts" "$output/worker-artifacts"
        if ($LASTEXITCODE -ne 0) { Write-Warning 'Worker artifact capture failed' }
        # Scheduled run IDs contain colons, which cannot be extracted as Windows paths.
        & docker exec $apiId tar -czf /tmp/day42-task-logs.tar.gz -C /opt/airflow logs
        if ($LASTEXITCODE -eq 0) { & docker cp "${apiId}:/tmp/day42-task-logs.tar.gz" "$output/task-logs.tar.gz" }
        if ($LASTEXITCODE -ne 0) { Write-Warning 'Task log archive capture failed' }
    }
    & docker @compose down -v --remove-orphans
    $cleanupFailed = $LASTEXITCODE -ne 0
    foreach ($name in $variables) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
    if ($cleanupFailed) { throw "Cleanup failed for isolated project $project" }
}
