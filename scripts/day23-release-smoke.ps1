param([string]$Python = "$PSScriptRoot/../pymk-ml-training/.venv/Scripts/python.exe", [switch]$IncludeV2, [switch]$IncludeHeavy, [switch]$IncludeJavaHeavy, [switch]$IncludeEvaluation, [switch]$IncludeL2Serving, [switch]$IncludeReranker, [switch]$IncludeBlendTuning, [switch]$IncludeOutbox, [switch]$IncludeConsumers)
if ($IncludeConsumers) { $IncludeOutbox = $true }
if ($IncludeBlendTuning) { $IncludeReranker = $true }
if ($IncludeReranker) { $IncludeL2Serving = $true }
if ($IncludeL2Serving) { $IncludeEvaluation = $true }
if ($IncludeEvaluation) { $IncludeJavaHeavy = $true }
if ($IncludeJavaHeavy) { $IncludeHeavy = $true }
if ($IncludeHeavy) { $IncludeV2 = $true }
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot/..").Path
$run = [guid]::NewGuid().ToString('N').Substring(0, 10)
$output = (New-Item -ItemType Directory -Path "$root/artifacts/day23-smoke/$run").FullName
$pg = $null
$redis = $null
$api = $null
$outboxKafkaStarted = $false
$previousKafkaPort = $env:PYMK_KAFKA_PORT
$step = 0
$apiBoot = 0
function StartSmokeApi([string]$mode) {
    $script:lastMode = $mode
    if ($script:api -and -not $script:api.HasExited) {
        Stop-Process -Id $script:api.Id
        if (-not $script:api.WaitForExit(10000)) { throw 'Previous API process did not stop' }
    }
    $script:apiBoot++
    $apiArgs = @('-jar', $apiJar) + $database + @("--server.port=$port","--spring.data.redis.port=$redisPort",'--spring.data.redis.host=127.0.0.1',"--pymk.light-ranker.model-path=$output/missing.onnx","--pymk.light-ranker.metadata-path=$output/missing.json","--pymk.serving.ranker=$mode")
    if ($IncludeOutbox) {
        $apiArgs += @('--pymk.events.relay.enabled=true', "--pymk.events.relay.bootstrap-servers=localhost:$kafkaPort")
    } else { $apiArgs += '--pymk.events.relay.enabled=false' }
    if ($IncludeConsumers) {
        $apiArgs += @('--pymk.events.consumers.enabled=true', "--pymk.events.consumers.bootstrap-servers=localhost:$kafkaPort")
    } else { $apiArgs += '--pymk.events.consumers.enabled=false' }
    if ($mode -eq 'RERANK' -and $IncludeBlendTuning) {
        $configPath = "$output/blend/application-blend.properties" -replace '\\','/'
        $apiArgs += "--spring.config.additional-location=file:$configPath"
    }
    if ($mode -in @('L2','RERANK') -or ($IncludeJavaHeavy -and -not $IncludeL2Serving)) {
        $apiArgs += @('--pymk.heavy-ranker.enabled=true',"--pymk.heavy-ranker.model-path=$output/heavy/heavy-mlp-v1.onnx","--pymk.heavy-ranker.metadata-path=$output/heavy/heavy-mlp-v1.metadata.json")
    } else { $apiArgs += '--pymk.heavy-ranker.enabled=false' }
    $quoted = $apiArgs | ForEach-Object { '"' + $_ + '"' }
    $script:api = Start-Process java -ArgumentList $quoted -PassThru -WindowStyle Hidden -RedirectStandardOutput "$output/api-$apiBoot-$mode.log" -RedirectStandardError "$output/api-$apiBoot-$mode-error.log"
    $ready = $false
    for ($i = 0; $i -lt 90; $i++) {
        if ($script:api.HasExited) { throw "API exited; inspect $output/api-$apiBoot-$mode.log" }
        try { $health = Invoke-RestMethod "http://127.0.0.1:$port/actuator/health"; if ($health.status -eq 'UP') { $ready = $true; break } } catch { }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'API readiness timeout' }
    $probe = Invoke-WebRequest "http://127.0.0.1:$port/api/v1/pymk/1?limit=2"
    if ($probe.Headers['X-PYMK-Scorer'] -ne $mode) { throw 'API scorer header disagrees with serving mode' }
}
function Checked([string]$exe, [string[]]$arguments) {
    & $exe @arguments
    if ($LASTEXITCODE -ne 0) { throw "$exe failed with exit code $LASTEXITCODE" }
}
function Sql([string]$statement) {
    $result = & docker exec $pg psql -U pymk -d pymk -v ON_ERROR_STOP=1 -Atc $statement
    if ($LASTEXITCODE -ne 0) { throw 'SQL failed' }
    return $result
}
function Batch([string[]]$parameters) {
    $script:step++
    $log = "$output/batch-$script:step.log"
    & java -jar $batchJar @database @parameters *> $log
    if ($LASTEXITCODE -ne 0) { throw "Batch failed: $log" }
}
function CheckResponse {
    $response = Invoke-RestMethod "http://127.0.0.1:$port/api/v1/pymk/1?limit=2"
    if ($response.recommendations.Count -ne 2 -or $response.recommendations[0].candidateId -ne 2) {
        throw 'Expected semantic neighbor 2 to rank first'
    }
    foreach ($recommendation in $response.recommendations) {
        $score = [double]$recommendation.score
        if (-not [double]::IsFinite($score) -or $score -lt 0 -or $score -gt 1) { throw 'Invalid probability' }
    }
    return ($response | ConvertTo-Json -Depth 10 -Compress)
}
Push-Location $root
try {
    if ($IncludeOutbox) {
        $kafkaProject = "pymk-day32-$run"
        if ($kafkaProject -notmatch '^pymk-day32-[a-f0-9]{10}$') { throw 'Invalid isolated Kafka project' }
        $kafkaListener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
        $kafkaListener.Start()
        $kafkaPort = $kafkaListener.LocalEndpoint.Port
        $kafkaListener.Stop()
        $env:PYMK_KAFKA_PORT = [string]$kafkaPort
        $kafkaCompose = @('compose', '-p', $kafkaProject, '-f', "$root/infra/docker-compose.yml", '--profile', 'kafka')
        $outboxKafkaStarted = $true
        Checked 'docker' ($kafkaCompose + @('up', '-d', '--wait', '--wait-timeout', '120', 'kafka'))
        Checked 'docker' ($kafkaCompose + @('run', '--rm', 'kafka-init'))
    }
    $batchJar = (Get-Item "$root/pymk-batch/target/pymk-batch.jar").FullName
    $apiJar = (Get-Item "$root/pymk-api/target/pymk-api.jar").FullName
    if (-not $batchJar -or -not $apiJar) { throw 'Run mvn verify first' }
    $pg = & docker run -d --rm --name "pymk-day23-pg-$run" -e POSTGRES_USER=pymk -e POSTGRES_PASSWORD=pymk -e POSTGRES_DB=pymk -p 127.0.0.1::5432 pgvector/pgvector:pg16
    if ($LASTEXITCODE -ne 0) { throw 'Could not start isolated PostgreSQL' }
    $redis = & docker run -d --rm --name "pymk-day23-redis-$run" -p 127.0.0.1::6379 redis:7-alpine
    if ($LASTEXITCODE -ne 0) { throw 'Could not start isolated Redis' }
    $pgPort = ((& docker port $pg 5432) -split ':')[-1]
    $redisPort = ((& docker port $redis 6379) -split ':')[-1]
    for ($i = 0; $i -lt 60; $i++) {
        & docker exec $pg pg_isready -U pymk *> $null
        if ($LASTEXITCODE -eq 0) { break }
        Start-Sleep -Seconds 1
    }
    $database = @("--spring.datasource.url=jdbc:postgresql://127.0.0.1:$pgPort/pymk", '--spring.datasource.username=pymk', '--spring.datasource.password=pymk')
    Batch @('--spring.batch.job.enabled=false')
    Sql @'
INSERT INTO members(id,full_name,headline,company,school,geo_region,created_at) VALUES
(1,'Engineer 1','Java backend engineer distributed systems','Acme','Engineering University','Cairo','2026-01-01'),
(2,'Engineer 2','Java backend engineer distributed systems','Acme','Engineering University','Cairo','2026-01-01'),
(3,'Chef','Chef culinary pastry restaurant','Bakery','Cooking Academy','Paris','2026-01-01'),
(4,'Blank',NULL,NULL,NULL,NULL,'2026-01-01');
INSERT INTO member_events(actor_member_id,target_member_id,type,occurred_at)
SELECT a,b,'INVITE_ACCEPTED',d::timestamptz FROM (VALUES (1,2),(2,1)) p(a,b)
CROSS JOIN (VALUES ('2026-01-05'),('2026-01-12'),('2026-01-19')) dates(d);
INSERT INTO member_events(actor_member_id,target_member_id,type,occurred_at)
SELECT 2,1,'INVITE_SENT',d::timestamptz FROM (VALUES ('2026-01-04'),('2026-01-11'),('2026-01-18')) dates(d);
'@ | Out-Null
    $transform = "$output/transform.json"
    $snapshots = @('2026-01-03','2026-01-10','2026-01-17','2026-02-01')
    for ($i = 0; $i -lt 4; $i++) {
        $id = "release-$i"
        Write-Host "Staging $id ($($snapshots[$i]))"
        $argsList = @('-m','pymk_training.profile_embeddings','--input',"$root/pymk-ml-training/tests/fixtures/profiles.csv",'--artifact',$transform,'--output',"$output/$id.csv",'--as-of',"$($snapshots[$i])T00:00:00Z")
        if ($i -eq 0) { $argsList += @('--fit','--embedding-version','profile-release-v1') }
        Checked $Python $argsList
        Checked $Python @('-m','pymk_training.release_manifest','--release-id',$id,'--transform',$transform,'--vectors',"$output/$id.csv",'--output',"$output/$id.json",'--min-validation-auc','0.9')
        Batch @('--spring.batch.job.name=embeddingRefreshJob',"manifestPath=$output/$id.json")
        if ($i -lt 3) {
            $labelEnd = @('2026-01-10','2026-01-17','2026-01-24')[$i]
            Batch @('--spring.batch.job.name=servingReleaseJob','action=export',"releaseId=$id","labelWindowEnd=${labelEnd}T00:00:00Z","outputPath=$output/train-$i.csv")
            if ($IncludeV2) {
                Batch @('--spring.batch.job.name=servingReleaseJob','action=export-v2',"releaseId=$id","datasetId=smoke-v2-$i","labelWindowEnd=${labelEnd}T00:00:00Z","outputPath=$output/v2-$i.csv")
            }
        }
    }
    Checked $Python @('-m','pymk_training.train_light_ranker','--input',"$output/train-0.csv",'--input',"$output/train-1.csv",'--input',"$output/train-2.csv",'--output-dir',"$output/model",'--model-version','release-logreg-v1','--embedding-transform',$transform)
    if ($IncludeV2) {
        Checked $Python @('-m','pymk_training.dataset_v2','--input',"$output/v2-0.csv",'--input',"$output/v2-1.csv",'--input',"$output/v2-2.csv")
        if ((Sql 'SELECT count(*) FROM pymk_training_datasets_v2') -ne '3') { throw 'Missing v2 dataset registrations' }
        Write-Host 'PASS: Day 24 v2 exports, provenance and chronological partitions validated in Python'
    }
    $model = "$output/model/release-logreg-v1.onnx"
    $metadata = "$output/model/release-logreg-v1.metadata.json"
    if ($IncludeHeavy) {
        Checked $Python @('-m','pymk_training.train_heavy_ranker','--input',"$output/v2-0.csv",'--input',"$output/v2-1.csv",'--input',"$output/v2-2.csv",'--output-dir',"$output/heavy",'--model-version','heavy-mlp-v1','--embedding-transform',$transform,'--l1-model',$model,'--l1-metadata',$metadata)
        Write-Host 'PASS: Day 25 two-head PyTorch training, named ONNX parity and frozen-cohort comparisons'
        if ($IncludeBlendTuning) {
            Checked $Python @('-m','pymk_training.blend_context_fixture','--input',"$output/v2-0.csv",'--input',"$output/v2-1.csv",'--input',"$output/v2-2.csv",'--profiles',"$root/pymk-ml-training/tests/fixtures/profiles.csv",'--output',"$output/blend-context.json")
            Checked $Python @('-m','pymk_training.tune_blend','--input',"$output/v2-0.csv",'--input',"$output/v2-1.csv",'--input',"$output/v2-2.csv",'--model',"$output/heavy/heavy-mlp-v1.onnx",'--metadata',"$output/heavy/heavy-mlp-v1.metadata.json",'--l1-model',$model,'--l1-metadata',$metadata,'--policy',"$root/pymk-ml-training/blend-policy.json",'--context',"$output/blend-context.json",'--output-dir',"$output/blend")
            $blend = Get-Content "$output/blend/report.json" -Raw | ConvertFrom-Json
            if ($blend.automaticPromotion -or $blend.optimizer.trials.Count -ne $blend.policy.searchBudget) { throw 'Invalid tuning report' }
            $digest = (Get-FileHash "$output/blend/application-blend.properties" -Algorithm SHA256).Hash.ToLowerInvariant()
            if ($digest -ne $blend.configurationSha256) { throw 'Tuned configuration checksum mismatch' }
            Write-Host 'PASS: Day 30 validation-only Bayesian search and immutable deployment configuration'
        }
        if ($IncludeEvaluation) {
            Checked $Python @('-m','pymk_training.evaluate_heavy_ranker','--input',"$output/v2-0.csv",'--input',"$output/v2-1.csv",'--input',"$output/v2-2.csv",'--model',"$output/heavy/heavy-mlp-v1.onnx",'--metadata',"$output/heavy/heavy-mlp-v1.metadata.json",'--l1-model',$model,'--l1-metadata',$metadata,'--policy',"$root/pymk-ml-training/evaluation-policy.json",'--output',"$output/heavy/evaluation.json")
            $evaluation = Get-Content "$output/heavy/evaluation.json" -Raw | ConvertFrom-Json
            if ($evaluation.qualityGate.status -ne 'not_met' -or $evaluation.automaticPromotion) { throw 'Tiny smoke fixture must not qualify for model promotion' }
            if ($evaluation.partitions.test.exportedPool.heavyAccepted.rows -le 0) { throw 'Missing held-out evaluation rows' }
            Write-Host 'PASS: Day 27 frozen evaluation report; tiny cohort correctly fails the quality gate without changing serving'
        }
        if ($IncludeJavaHeavy) {
            $fixture = "$output/heavy/java-fixture.json"
            & $Python -m pymk_training.heavy_serving_fixture --model "$output/heavy/heavy-mlp-v1.onnx" --metadata "$output/heavy/heavy-mlp-v1.metadata.json" > $fixture
            if ($LASTEXITCODE -ne 0) { throw 'Java parity fixture export failed' }
            & mvn -pl pymk-heavy-ranker -am '-Dtest=OnnxHeavyRankerModelTest' '-Dsurefire.failIfNoSpecifiedTests=false' "-Dpymk.heavy.fixture=$fixture" test *> "$output/heavy/java-parity.log"
            if ($LASTEXITCODE -ne 0) { throw "Java L2 parity failed: $output/heavy/java-parity.log" }
            Write-Host 'PASS: Day 26 Java L2 named-head parity, invalid-model rejection and lifecycle checks against the freshly trained model'
        }
    }
    # A complete legacy baseline makes rollback exercise the public API too.
    Sql "INSERT INTO member_embeddings SELECT member_id,embedding,updated_at,embedding_version,fit_cutoff FROM pymk_release_embeddings WHERE release_id='release-3'; INSERT INTO pymk_features SELECT member_id,candidate_id,mutual_connection_count,same_company,same_school,same_geo,embedding_cosine_sim,profile_view_count_last_30d,feature_as_of,computed_at FROM pymk_release_features WHERE release_id='release-3'" | Out-Null
    Batch @('--spring.batch.job.name=servingReleaseJob','action=activate','releaseId=release-3','expectedEpoch=0',"modelPath=$model","metadataPath=$metadata","legacyModelPath=$model","legacyMetadataPath=$metadata")
    # Start from database-held model bytes, with deliberately nonexistent local model paths.
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start(); $port = $listener.LocalEndpoint.Port; $listener.Stop()
    if ($IncludeL2Serving) { StartSmokeApi 'L2' } else { StartSmokeApi 'L1' }
    $first = CheckResponse
    if ((CheckResponse) -ne $first) { throw 'Cached response mismatch' }
    Batch @('--spring.batch.job.name=servingReleaseJob','action=rollback','releaseId=legacy','expectedEpoch=1')
    if ($IncludeL2Serving) {
        $incompatible = Invoke-WebRequest "http://127.0.0.1:$port/api/v1/pymk/1?limit=2" -SkipHttpErrorCheck
        if ([int]$incompatible.StatusCode -ne 503) { throw 'L2 must reject the legacy release' }
        StartSmokeApi 'L1'
        $heavyFirst = $first
        $first = CheckResponse
    }
    CheckResponse | Out-Null
    Batch @('--spring.batch.job.name=servingReleaseJob','action=rollback','releaseId=release-3','expectedEpoch=2')
    if ((CheckResponse) -ne $first) { throw 'Rollback response mismatch' }
    $keys = & docker exec $redis redis-cli --scan --pattern '*:release:*'
    foreach ($generation in @('release-3:1','legacy:2','release-3:3')) {
        if (-not ($keys -match [regex]::Escape($generation))) { throw "Missing cache namespace $generation" }
    }
    if ((Sql 'SELECT epoch FROM pymk_active_release') -ne '3') { throw 'Unexpected release epoch' }
    if ($IncludeL2Serving) {
        StartSmokeApi 'L2'
        if ((CheckResponse) -ne $heavyFirst) { throw 'L2 re-enable changed the frozen release response' }
        Write-Host 'PASS: Day 28 public L2 HTTP scoring, incompatible-release 503, L1-only process rollback and L2 re-enable with shared Redis'
    }
    if ($IncludeReranker) {
        StartSmokeApi 'RERANK'
        foreach ($limit in @(1,2,20,100)) {
            & "$PSScriptRoot/day13-recommendation-smoke.ps1" -BaseUrl "http://127.0.0.1:$port" -MemberId 1 -Limit $limit
        }
        $reranked = Invoke-RestMethod "http://127.0.0.1:$port/api/v1/pymk/1?limit=2"
        if ($reranked.recommendations.Count -ne 2 -or $reranked.exposure.knownGroupCounts.acme -ne 1 -or $reranked.exposure.knownGroupCap -ne 1) {
            throw 'Expected two candidates with at most one from each known employer at limit=2'
        }
        $poolKeys = & docker exec $redis redis-cli --scan --pattern '*:scored-pool-v1:*:scorer:day29-*'
        if (-not $poolKeys) { throw 'Missing versioned reusable scored pool' }
        StartSmokeApi 'L2'
        if ((CheckResponse) -ne $heavyFirst) { throw 'Reranker rollback changed L2 response' }
        StartSmokeApi 'RERANK'
        $reEnabled = Invoke-RestMethod "http://127.0.0.1:$port/api/v1/pymk/1?limit=2"
        if (($reEnabled | ConvertTo-Json -Depth 10 -Compress) -ne ($reranked | ConvertTo-Json -Depth 10 -Compress)) {
            throw 'Reranker re-enable changed selection'
        }
        Write-Host 'PASS: Day 29 blend/diversity HTTP contract at limits 1/2/20/100, scored pool cache, L2 rollback and reranker re-enable'
        if ($IncludeBlendTuning) {
            $properties = Get-Content "$output/blend/application-blend.properties"
            $sent = [double](($properties | Where-Object { $_ -like 'pymk.reranker.sent-weight=*' }) -split '=')[1]
            $accepted = [double](($properties | Where-Object { $_ -like 'pymk.reranker.accepted-weight=*' }) -split '=')[1]
            if ([Math]::Abs($sent + $accepted - 1) -gt 1e-12) { throw 'Exported weights are not normalized' }
            $l1Budget = [int]$blend.policy.l1Survivors
            $tunedKeys = & docker exec $redis redis-cli --scan --pattern "*:scored-pool-v1:*:scorer:day29-*-$l1Budget-*:release:release-3:3"
            if (-not $tunedKeys) { throw 'Packaged RERANK did not load the exported survivor budget' }
            Write-Host 'PASS: Day 30 tuned configuration loaded by the actual diversified HTTP endpoint, rollback and re-enable'
        }
    }
    if ($IncludeOutbox) {
        Checked 'docker' ($kafkaCompose + @('stop', 'kafka'))
        $eventId = [guid]::NewGuid().ToString()
        $eventBody = @{
            schemaVersion = 1; eventId = $eventId; eventType = 'PROFILE_VIEW'
            occurredAt = [DateTime]::UtcNow.ToString('o'); subjectMemberId = 1; targetMemberId = 2
            correlationId = [guid]::NewGuid().ToString(); payload = @{}
        } | ConvertTo-Json -Compress
        $accepted = Invoke-WebRequest "http://127.0.0.1:$port/api/v1/events" -Method Post -ContentType 'application/json' -Body $eventBody
        if ([int]$accepted.StatusCode -ne 202) { throw 'Event was not durably accepted during broker outage' }
        $receipt = $accepted.Content
        $retry = Invoke-WebRequest "http://127.0.0.1:$port/api/v1/events" -Method Post -ContentType 'application/json' -Body $eventBody
        if ($retry.Content -ne $receipt) { throw 'Duplicate event changed its receipt' }
        for ($i = 0; $i -lt 45; $i++) {
            if ((Sql 'SELECT count(*) FROM event_outbox WHERE published_at IS NULL AND attempts > 0') -eq '1') { break }
            Start-Sleep -Seconds 1
        }
        if ((Sql 'SELECT count(*) FROM event_outbox WHERE published_at IS NULL AND attempts > 0') -ne '1') {
            throw 'Outage did not retain the failed publish for retry'
        }
        $readDuringOutage = Invoke-WebRequest "http://127.0.0.1:$port/api/v1/pymk/1?limit=2"
        if ([int]$readDuringOutage.StatusCode -ne 200) { throw 'Broker outage broke recommendation reads' }
        $connection = Invoke-WebRequest "http://127.0.0.1:$port/api/v1/connections" -Method Post -ContentType 'application/json' -Body '{"memberId":3,"connectedMemberId":4}'
        if ([int]$connection.StatusCode -ne 201) { throw 'Connection did not commit during broker outage' }
        Checked 'docker' ($kafkaCompose + @('up', '-d', '--wait', '--wait-timeout', '120', 'kafka'))
        for ($i = 0; $i -lt 60; $i++) {
            if ((Sql 'SELECT count(*) FROM event_outbox WHERE published_at IS NOT NULL') -eq '2') { break }
            Start-Sleep -Seconds 1
        }
        if ((Sql 'SELECT count(*) FROM event_outbox WHERE published_at IS NOT NULL') -ne '2') { throw 'Relay failed to drain after broker recovery' }
        $wire = & docker @kafkaCompose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:19092 --topic pymk.member-events.v1 --from-beginning --max-messages 1 --timeout-ms 20000 2> "$output/outbox-consumer.log"
        if ($LASTEXITCODE -ne 0 -or ($wire | ConvertFrom-Json).eventId -ne $eventId) { throw 'Kafka record identity mismatch' }
        if ($IncludeConsumers) {
            for ($i = 0; $i -lt 60; $i++) {
                if ((Sql 'SELECT count(*) FROM event_consumer_journal') -eq '4') { break }
                Start-Sleep -Seconds 1
            }
            if ((Sql 'SELECT count(*) FROM event_consumer_journal') -ne '4') { throw 'Both consumers did not commit both events' }
            if ((Sql 'SELECT count(*) FROM event_consumer_receipt') -ne '4') { throw 'Receipt/journal transaction mismatch' }
        }
        StartSmokeApi $lastMode
        $afterRestart = Invoke-WebRequest "http://127.0.0.1:$port/api/v1/events" -Method Post -ContentType 'application/json' -Body $eventBody
        if ($afterRestart.Content -ne $receipt -or (Sql 'SELECT count(*) FROM event_outbox') -ne '2') {
            throw 'Process restart lost receipt identity'
        }
        if ($IncludeConsumers) {
            if ((Sql 'SELECT count(*) FROM event_consumer_journal') -ne '4') { throw 'Restart duplicated consumer effects' }
            Write-Host 'PASS: Day 33 packaged HTTP/outbox/Kafka pipeline committed once per independent consumer and survived API restart'
        }
        Write-Host 'PASS: Day 32 actual HTTP acceptance through broker outage, scheduled relay recovery, Kafka identity and durable receipt after API restart'
    }
    Write-Host "PASS: real training, activation, database-model startup, HTTP probabilities, caching and two rollbacks. Evidence: $output"
} finally {
    if ($api -and -not $api.HasExited) { Stop-Process -Id $api.Id }
    # Only the two uniquely named containers created by this invocation are removed.
    if ($redis) { & docker stop $redis | Out-Null }
    if ($pg) { & docker stop $pg | Out-Null }
    if ($outboxKafkaStarted) {
        & docker @kafkaCompose logs --no-color kafka *> "$output/outbox-broker.log"
        # Only this invocation's validated GUID project and its disposable volume.
        & docker @kafkaCompose down --volumes
        $kafkaCleanupExit = $LASTEXITCODE
    }
    $env:PYMK_KAFKA_PORT = $previousKafkaPort
    Pop-Location
    if ($outboxKafkaStarted -and $kafkaCleanupExit -ne 0) { throw 'Isolated Kafka cleanup failed' }
}
