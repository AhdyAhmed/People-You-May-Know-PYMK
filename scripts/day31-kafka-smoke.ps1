param([string[]]$MavenOptions = @())
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot/..").Path
$run = [guid]::NewGuid().ToString('N').Substring(0, 10)
$project = "pymk-day31-$run"
if ($project -notmatch '^pymk-day31-[a-f0-9]{10}$') { throw 'Invalid isolated smoke project name' }
$output = (New-Item -ItemType Directory -Path "$root/artifacts/day31-smoke/$run").FullName
$previousPort = $env:PYMK_KAFKA_PORT
$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
$listener.Start()
$port = $listener.LocalEndpoint.Port
$listener.Stop()
$env:PYMK_KAFKA_PORT = [string]$port
$compose = @('compose', '-p', $project, '-f', "$root/infra/docker-compose.yml", '--profile', 'kafka')
function CheckedDocker([string[]]$arguments) {
    & docker @compose @arguments
    if ($LASTEXITCODE -ne 0) { throw "Docker step failed: $arguments" }
}
function Probe([string]$phase) {
    $options = @('-B', '-ntp', '-pl', 'pymk-event-stream', '-Dtest=KafkaComposeSmoke',
        "-Dpymk.kafka.bootstrap=localhost:$port", "-Dpymk.kafka.smoke.topic=pymk.smoke.$run",
        "-Dpymk.kafka.smoke.phase=$phase") + $MavenOptions + @('test')
    & mvn @options *> "$output/$phase.log"
    if ($LASTEXITCODE -ne 0) { throw "Kafka $phase probe failed; inspect $output/$phase.log" }
}
Push-Location $root
try {
    CheckedDocker @('config', '--quiet')
    CheckedDocker @('up', '-d', '--wait', '--wait-timeout', '120', 'kafka')
    CheckedDocker @('run', '--rm', 'kafka-init')
    Probe 'write'
    Write-Host 'PASS: host listener, internal topic init, topic contracts and real publish/consume'
    # Recreate the broker to prove the named volume, not just process memory, retains the record.
    CheckedDocker @('up', '-d', '--force-recreate', '--wait', '--wait-timeout', '120', 'kafka')
    CheckedDocker @('run', '--rm', 'kafka-init')
    Probe 'read'
    Write-Host 'PASS: broker recreation, persistent event replay and idempotent topic initialization'
    Write-Host "Evidence: $output"
} finally {
    & docker @compose logs --no-color kafka *> "$output/broker.log"
    # Only the fresh GUID-named smoke project's containers/volumes are removed.
    & docker @compose down --volumes
    $cleanupExit = $LASTEXITCODE
    $env:PYMK_KAFKA_PORT = $previousPort
    Pop-Location
    if ($cleanupExit -ne 0) { throw "Smoke cleanup failed for isolated project $project" }
}
