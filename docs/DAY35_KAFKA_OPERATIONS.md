# Day 35 — Kafka resilience and observability

## Local operation

Days 31–34 implement infrastructure/contracts, the transactional outbox, durable
consumers/DLT recovery and live projections. Day 35 adds process metrics, cached
database/broker gauges, and automated broker/consumer restart and ordering checks.
No new migration or wire-contract version is needed; V11 remains the latest schema.

With the usual PostgreSQL, Redis and serving-model prerequisites:

```powershell
docker compose -f infra/docker-compose.yml --profile kafka up -d --wait kafka
docker compose -f infra/docker-compose.yml --profile kafka run --rm kafka-init
$env:PYMK_OUTBOX_RELAY_ENABLED = 'true'
$env:PYMK_EVENT_CONSUMERS_ENABLED = 'true'
$env:PYMK_EVENT_MONITORING_ENABLED = 'true'
$env:PYMK_MANAGEMENT_EXPOSURE = 'health,info,metrics'
java -jar pymk-api/target/pymk-api.jar --management.server.port=9091 --management.server.address=127.0.0.1
Invoke-RestMethod 'http://127.0.0.1:9091/actuator/metrics'
Invoke-RestMethod 'http://127.0.0.1:9091/actuator/metrics/pymk.events.consumer.lag?tag=group:pymk.feature-projection.v1'
```

Monitoring and metrics HTTP exposure are opt-ins. Default exposure remains only
`health,info`; delivery timers/counters are collected by Micrometer even when
background monitoring is disabled. This milestone publishes Actuator JSON metrics,
**not a Prometheus/Grafana deployment**. Unified dashboards remain M9. No new
authentication is supplied: keep management on loopback/a protected private
network and configure authenticated ingress before exposing it remotely.

The monitor uses its own single daemon worker, separate from relay scheduling and
request threads. Scrapes only read cached values; they make no SQL/Kafka calls.
`pymk.events.monitoring.poll-ms` defaults to 10000 (minimum 1000). SQL statements
have a two-second query timeout; broker operations have a two-second API timeout
and bounded three-second future waits. Polls cannot overlap. These are
per-operation limits, not a single global deadline; JDBC connection acquisition
also follows the configured connection-pool timeout. Kafka monitoring
follows the consumer enable flag by default; set
`pymk.events.monitoring.kafka-enabled=true` explicitly for a monitoring-only process.

## Metrics and interpretation

All names below have the prefix `pymk.events.`. Only fixed group, source and outcome
labels are used; payloads, IDs, keys and exception messages are never metric labels.

| Suffix | Meaning |
|---|---|
| `outbox.send` | Timer, outcome `acknowledged` or `failed`; count gives publishing-attempt throughput and total time gives mean latency. Acknowledged does not imply the later database publication marker committed. |
| `consumer.process` | Timer, group/outcome `committed` or `failed`; covers transactional processing through commit. Successful duplicate/no-op deliveries are included; this is not a unique-event counter or an offset-commit timer. |
| `consumer.retries` | Counter by group for bounded retries scheduled after failures 1–3. Shutdown can prevent a scheduled retry from executing. A failed DLT recovery starts another bounded cycle. |
| `consumer.dead.letters` | Counter by group/outcome `acknowledged` or `failed`. Uncertain sends are failures; repeated recovery can create duplicate DLT records. |
| `outbox.pending` | Number of unpublished authoritative outbox rows. |
| `outbox.oldest.age` | Seconds since ingestion of the oldest unpublished row; zero when empty. |
| `consumer.lag` | Retained source records behind committed offsets, summed over both source topics for each fixed consumer group. New groups start at earliest retained offsets. |
| `consumer.offset.out.of.range` | Number of source partitions whose stored commit is before the retained start or after the log end. Investigate retention/truncation; zero retained lag does not prove complete history. |
| `projection.pending` | Authoritative outbox events still missing their live effect: view/invite membership for the feature group, or a sufficient connection sequence for the cache group. Includes unpublished events and unrepaired quarantine; reconciliation can reduce it before consumer offsets advance. |
| `projection.oldest.age` | Seconds since ingestion of the oldest missing live effect, zero when caught up. Idle streams do not produce false freshness alarms. |
| `monitor.up` | Last poll success, separately labelled `database` and (when enabled) `kafka`. |
| `monitor.last.success` | Unix timestamp in seconds of the last successful poll, separately labelled by source. NaN before any successful poll. |

Failed polling sets the affected data gauges to **NaN**, not zero, and sets its
`monitor.up` to zero; last-success time remains unchanged. Monitor absence when
disabled and stale/no samples are also unknown, not proof of health. Polling
does not affect application readiness: Kafka downtime must not block serving.
Database and Kafka snapshots are independent and eventually consistent, not a
distributed atomic snapshot. Evaluate sustained signals over multiple polls.

Projection backlog is measured against retained authoritative outbox history;
direct externally produced Kafka records without matching outbox receipts are
covered by consumer lag/DLT metrics, not that backlog gauge. Monitor queries scan
retained state and use JSON contract fields; benchmark cost before production
scale and increase the poll interval or introduce indexed operational summaries.

Timers/counters are per process and reset on restart. Compute throughput from
counter/count deltas, treating a decrease as a reset; do not equate attempts to
unique side effects. Cluster-wide backlog gauges are duplicated across API
replicas: use a designated monitor or max, not sum. Before traffic, lazily created
timer/counter series may be absent. These metrics are operational signals, not
durable audit records; PostgreSQL receipts/journals retain the identity history.

Suggested starting alerts (tune to measured workload, not claimed production SLOs):

- Failed/stale monitoring for more than three poll intervals plus the bounded
  poll duration: investigate monitoring/connectivity before trusting backlog.
- Pending work older than 60 seconds or growing lag across three successful polls:
  inspect relay/consumer availability and DLT failures.
- Any new DLT acknowledgement or persistent DLT failure: review the incident;
  never auto-reset offsets or rewrite its event ID.
- Persistent offset-out-of-range: investigate deleted source history and perform
  authoritative reconciliation; an offset reset cannot restore expired records.

## Failure handling

| Failure | Expected behavior / response |
|---|---|
| Kafka unavailable | HTTP ingestion commits to PostgreSQL until the bounded backlog cap; relay retries. Recommendation reads do not contact Kafka. Monitor Kafka data becomes unknown, while DB backlog remains visible. |
| Consumer stops or restarts | Its independent lag increases; restart resumes committed offsets. A post-DB/pre-offset crash safely rechecks receipts. |
| Poison or conflicting identity | Bounded blocking retry, then acknowledged DLT publication before source progress; failed DLT publication cannot skip the source record. |
| Redis unavailable | Recommendation cache degrades to misses; live invalidation commits durable member epochs without Redis. |
| Projection missing or Kafka history expired | Run the finite Day 34 reconciliation job against retained authoritative source history. Do not delete deduplication receipts. |
| DB unavailable | Admission/processing fails rather than acknowledging uncommitted state; monitor DB values become unknown. Restore DB first; use existing retry/receipt paths. |

Per-key order applies to the original source stream; DLT replay is a later append
and may be out of order. Consumer identity checks and connection sequences remain
mandatory. Broker ACK + PostgreSQL commit + Kafka offset commit are **not** a
distributed exactly-once transaction. A source partition-count change can move a
key; do not resize v1 topics casually. Version topology and plan a controlled
cutover/backfill before relying on ordering across such a change.

## Topology, replay and schema changes

- Four v1 topics: two sources (seven-day retention) and two DLTs (30 days), three
  partitions each. DLTs have 2 MiB capacity for original bytes plus recovery headers.
  `kafka-init` is repeatable and validates configuration drift without deleting data.
- [Day 31 contracts/topology](DAY31_KAFKA_CONTRACTS.md): frozen schemas, fixtures,
  compatibility rules, listener setup and persistence checks. Keep v1 frozen;
  breaking changes require a new schema/topic version and explicit compatibility tests.
- [Day 32 outbox](DAY32_EVENT_OUTBOX.md): admission, head-of-key ordering, leases,
  acknowledgement windows, backlog cap and recovery.
- [Day 33 DLT/replay](DAY33_EVENT_CONSUMERS.md): inspect a specific incident, fix
  the cause, then use the finite `DeadLetterReplay` CLI for its exact DLT offset.
  Replay preserves original IDs/bytes and rejects malformed poison; never bulk
  reset a group as a substitute for diagnosis. DLT access contains original data
  and needs the same security controls as source events.
- [Day 34 live repair](DAY34_LIVE_FEATURES.md): window/knowledge boundaries,
  journal bootstrap, atomic generations and durable cache epochs. Receipts,
  journals and outbox history are retained by default; pruning requires a separately
  designed recovery policy. Nightly Airflow scheduling remains Day 43.

The development broker is plaintext/RF1 and is not high availability. Production
TLS/SASL, ACLs, replication, quotas and operational SLOs require deployment work;
this milestone does not claim production hardening.

## Repeatable validation

```powershell
mvn -B -ntp verify
./scripts/day23-release-smoke.ps1 -IncludeObservability
```

Real PostgreSQL/Redis/Kafka tests cover HTTP → outbox → source → journal/live
effect, duplicates/conflicts, bounded retry/DLT outage, replay, per-key order,
broker restart on the same retained container, independent consumer restart,
lag/backlog transitions and HTTP metric exposure. The packaged smoke repeats
training, serving, rollback, Kafka outage/recovery, Redis-independent repair,
API restart and monitoring through actual HTTP endpoints.

### Verification record

Verified on 2026-10-13:

- Full `mvn -B verify`: all 13 reactor projects passed; 263 Java tests with
  zero failures, errors or skips (`artifacts/day35-verify-current.log`).
- Python regression: 57 tests passed with warnings treated as errors;
  `pip check` found no broken requirements (`artifacts/day35-python-current.log`).
- The Java suite includes seven real-Kafka consumer tests, including broker and
  consumer restarts, per-key ordering, retry/DLT recovery and monitoring transitions.
- Packaged `-IncludeObservability` smoke passed fresh training, activation, HTTP
  probabilities, caching and two rollbacks. Broker outage exposed unavailability
  and retained backlog; reconciliation worked with Kafka stopped and Redis paused.
  Recovery and API restart preserved receipt/live-effect identity and reached zero
  consumer lag and projection backlog over HTTP (`artifacts/day35-serving-smoke.log`,
  `artifacts/day23-smoke/a1b7622167/`).
- Disposable test containers and volumes were removed; evidence logs were retained.
  These checks validate the covered scenarios, not a guarantee of defect-free or
  production-hardened operation.
