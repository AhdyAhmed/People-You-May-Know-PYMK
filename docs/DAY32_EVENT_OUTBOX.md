# Day 32 — Durable event ingestion and outbox publishing

This records the Day 32 checkpoint. Day 33 now adds durable journal consumers and
DLT recovery; see [the consumer runbook](DAY33_EVENT_CONSUMERS.md). Publisher
configuration and guarantees below remain applicable.

## Implemented boundary

The API commits domain writes and a V9 `event_outbox` row in one PostgreSQL
transaction. Kafka is never contacted by an ingestion, connection or recommendation
request. A separate, opt-in scheduled relay publishes committed envelopes.
Consumer deduplication, DLTs and streaming projections remain Days 33–35;
publishing an event today does not update streaming features.

`POST /api/v1/events` accepts the following JSON (use real existing member IDs):

```json
{
  "schemaVersion": 1,
  "eventId": "27fb9652-6815-43c6-a5a6-3b1c58e95d32",
  "eventType": "PROFILE_VIEW",
  "occurredAt": "2026-10-09T12:00:00Z",
  "subjectMemberId": 1,
  "targetMemberId": 2,
  "correlationId": "89694ccd-e0ef-4e16-b796-acd275e95d32",
  "payload": {}
}
```

Successful response: HTTP 202 with `eventId`, server-assigned `ingestedAt`
and `status: "accepted"`. This means the database commit succeeded, **not**
that Kafka or a projection has processed the event. Swagger documents the request.
Reuse the identical event ID/content after a lost response. Receipts survive
publication; an identical retry returns the original receipt, changed content is
409. Correlation ID is part of content identity; JSON formatting is not.

All five v1 engagement types require two existing, distinct positive int64 member
IDs and an empty payload. Unknown fields, duplicate JSON keys, numeric coercions,
wrong schema versions, non-UTC timestamps and client-provided `ingestedAt` are
rejected (400). Missing members return 404. Requests are bounded to 16 KiB (413);
the final wire envelope must also fit the frozen contract. Future event times may
be at most 300 seconds ahead of server time; historical/late events are accepted.
Identical receipt lookup precedes time/reference revalidation so a retry retains
its original knowledge boundary. Like the existing API, this is a trusted local
portfolio endpoint, not an authenticated public telemetry gateway.

`POST /api/v1/connections` still creates two directed rows and performs synchronous
post-commit cache invalidation. A newly created/repaired pair produces exactly one
`CONNECTION_CREATED` event with canonical `min:max` key and a monotonically
increasing per-pair sequence. Existing pairs produce no event. This API does not
invent an `INVITE_ACCEPTED` training label. Offline synthetic bulk loading is not
a runtime event producer.

## Enable publishing

Create/validate the Day 31 topics first, then start the API with the relay enabled:

```powershell
docker compose -f infra/docker-compose.yml --profile kafka up -d --wait kafka
docker compose -f infra/docker-compose.yml --profile kafka run --rm kafka-init
$env:PYMK_OUTBOX_RELAY_ENABLED = "true"
$env:PYMK_KAFKA_BOOTSTRAP_SERVERS = "localhost:9092"
java -jar pymk-api/target/pymk-api.jar
```

Normal database, Redis and model prerequisites from the README still apply.
Compose-hosted clients use `kafka:19092`. Default relay state is disabled: the
API can run without a broker and events remain durably queued. Do not leave it
disabled indefinitely if fresh delivery is required.

| Setting | Default | Meaning |
|---|---:|---|
| `pymk.events.max-pending` / `PYMK_EVENTS_MAX_PENDING` | 100000 | Maximum accepted unpublished rows |
| `pymk.events.future-skew-seconds` | 300 | Maximum future occurrence skew |
| `pymk.events.relay.batch-size` | 50 | Claimed key heads per poll (1–1000) |
| `pymk.events.relay.lease-seconds` | 60 | Recover abandoned claims after this interval |
| `pymk.events.relay.retry-seconds` | 5 | Fixed bounded retry delay, no discard threshold |
| `pymk.events.relay.poll-ms` | 1000 | Fixed delay after the previous batch finishes |

## Failure, concurrency and ordering

Admission takes PostgreSQL transaction advisory lock 320032 before runtime domain
writes. This serializes capacity checks and event identity decisions across API
instances, and makes outbox ID order follow commit order. It is intentionally a
simple portfolio-scale design, not an unbounded-throughput claim. Broker calls
never hold that admission lock. A full backlog rejects **new** event/connection
mutations with 503 and Retry-After: 5; duplicate receipts and existing connections
remain valid. A failed append rolls back domain writes too.

The relay claims only the oldest unpublished row for each topic/key using
`FOR UPDATE SKIP LOCKED`, a UUID owner and a bounded lease. Earlier failed/leased
rows block later rows for that key, not other keys. Before sending it locks the
row again and checks ownership; the row lock remains held through broker send
and publication marking. An expired lease cannot bypass an actively locked send.
An abandoned claim is recoverable after expiry; stale owners cannot mark or send
reclaimed rows. Keep relay concurrency below database connection-pool capacity.

The producer uses acks=all, idempotence, max.in.flight=1, bounded metadata wait
(5 seconds) and delivery timeout (15 seconds). A failed send retains the row,
records an attempt and schedules retry; uncertain local producers are closed/reset
before releasing the database lock. Only an acknowledged send gets a published
marker. A database/process crash between those two operations replays the **exact**
stored envelope and ID. There is no distributed PostgreSQL/Kafka transaction.

Delivery is at least once, not exactly once. Head-of-key gating prevents advancing
to a later distinct record before the earlier acknowledgement/marker. A delayed
network request or replay can still deliver duplicates; future consumers must
deduplicate IDs and reject stale state-replacement sequences. Do not use broker
arrival order as a substitute for those safeguards. Broker unavailability delays
freshness, not recommendation reads.

Receipts and unpublished rows have no automatic deletion policy. Disk capacity
and published-receipt archival need operational planning before production.
Development data reset refuses to run while unpublished rows exist; do not delete
them to bypass this guard. Published receipts and pair sequences survive a reset,
so an old event ID cannot be silently re-admitted. Do not reseed while live writers
are running. Migrations V1–V8 are unchanged; V9 is forward-only.

## Inspect and verify

```sql
SELECT count(*) AS pending, min(ingested_at) AS oldest_pending
FROM event_outbox WHERE published_at IS NULL;
SELECT count(*) AS leased
FROM event_outbox WHERE published_at IS NULL AND lease_owner IS NOT NULL;
SELECT id, topic, aggregate_key, attempts, available_at, lease_until
FROM event_outbox WHERE published_at IS NULL ORDER BY id LIMIT 50;
```

Avoid logging envelope bodies or using member/event IDs as metric labels.
Automated regression coverage is in `EventOutboxIntegrationTest`, using real
PostgreSQL and Kafka plus deterministic injected failure windows:

```powershell
mvn -B -ntp -pl pymk-api -am "-Dtest=EventOutboxIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
mvn -B -ntp verify
./scripts/day23-release-smoke.ps1 -IncludeOutbox
# Optional broader L2/blend/rollback coverage in the same run:
./scripts/day23-release-smoke.ps1 -IncludeBlendTuning -IncludeOutbox
```

The packaged smoke creates isolated PostgreSQL, Redis and Kafka resources, trains
real ONNX models, exercises the existing serving/rollback pipeline, stops Kafka,
accepts HTTP writes, restores the broker, verifies scheduled delivery and restarts
the API before retrying the original receipt. It removes only its own disposable
containers/volume and keeps logs under `artifacts/day23-smoke/`.

Verification on 2026-10-09: `mvn -B -ntp verify` passed all 13 reactor projects,
229 Java tests, zero failures/errors/skips. All 57 Python tests passed with
warnings treated as errors; `pip check` reported no broken requirements.
The separate Kafka Compose smoke also passed host/internal listeners, real
publish/consume, broker recreation, persistent replay and idempotent topic setup.

On 2026-10-10 the focused packaged `-IncludeOutbox` smoke completed successfully:
fresh training, release activation, real HTTP recommendations/caching, two
rollbacks, durable acceptance during broker outage, scheduled publication after
recovery, matching Kafka event identity and unchanged receipt after API restart.
Evidence: `artifacts/day23-smoke/9c00cb3a67/` and
`artifacts/day32-outbox-recheck.log`. Its disposable services were cleaned up.
The earlier broader run passed the L2/blend checks but lost its PostgreSQL
container during the final restart; it is not counted as a completed smoke run.
