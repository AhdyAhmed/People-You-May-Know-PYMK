# Day 31 — Kafka infrastructure and versioned contracts

This is the historical Day 31 checkpoint. Day 32 now implements API ingestion
and outbox publishing; see [the current publisher runbook](DAY32_EVENT_OUTBOX.md).
The broker setup and frozen v1 contracts below remain applicable.

Day 31 adds a runnable, optional development broker and the `pymk-event-stream`
Java library. It does **not** publish application mutations yet. The transactional
outbox/event-ingestion endpoint starts on Day 32; durable consumer deduplication,
DLTs and projections follow on Days 33–35. The API does not depend on this module
or connect to Kafka. M1–M5 serving/training behavior stays unchanged.

## Local operation

Requirements: Java 21, Maven, Docker with Linux containers and Docker Compose.
The broker image is `apache/kafka:3.9.1`, matching the Kafka client version managed
by Spring Boot 3.5.11. Spring Kafka 3.3.13 and Testcontainers 1.21.4 are also
inherited from Boot; the JSON Schema validator uses the Jackson-2-compatible
1.5.9 API with Boot-managed Jackson dependencies.

```powershell
# Existing Postgres/Redis-only startup is unchanged.
docker compose -f infra/docker-compose.yml up -d

# Start Kafka and create/validate both topics explicitly.
docker compose -f infra/docker-compose.yml --profile kafka up -d --wait kafka
docker compose -f infra/docker-compose.yml --profile kafka run --rm kafka-init
docker compose -f infra/docker-compose.yml --profile kafka ps -a

# Inspect topics and their actual settings.
docker compose -f infra/docker-compose.yml exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:19092 --describe

# Stop only the broker; named-volume data survives.
docker compose -f infra/docker-compose.yml stop kafka
```

`--profile kafka up -d` also starts the one-shot initializer; inspect its exit
status/logs because a healthy broker alone does not prove topic initialization
succeeded. Re-running `kafka-init` is idempotent and rejects existing topics with
unexpected partition/retention settings instead of silently modifying them.
Do not delete or recreate an existing topic to bypass a drift check; inspect the
cluster and approve any migration explicitly.

Host clients use `localhost:9092`; containers on the Compose network use
`kafka:19092`. `PYMK_KAFKA_PORT` changes both the host mapping and advertised host
port (set it before startup and keep it set for subsequent Compose commands).
Only loopback is published; the plaintext controller/internal listeners are not
published to the host. KRaft runs a combined broker/controller, with a stable
development cluster ID and a named volume at `/var/lib/kafka/data`. Never change
the cluster ID while reusing that volume. No ZooKeeper is required.

The default startup profile remains lightweight. MLflow/Airflow remain planned
after Kafka and are not dependencies for Day 31.

## Wire contracts and routing

Schemas ship inside the event-stream JAR under `contracts/`. JSON Schema draft
2020-12 format assertions are enabled; validation needs no network/schema server.
`EventContract` additionally enforces exact int64 integers, distinct members,
canonical connection endpoints, bounded input, and topic/key agreement. JSON
duplicate fields, trailing values, tombstones and unknown topic/schema versions
are rejected. Validation errors do not echo payload/member data.

| Topic | Types | Kafka key | Payload |
|---|---|---|---|
| `pymk.member-events.v1` | `PROFILE_VIEW`, `SEARCH_APPEARANCE`, `INVITE_SENT`, `INVITE_ACCEPTED`, `INVITE_IGNORED` | Decimal subject/actor member ID | `{}`; all v1 engagement semantics are in the envelope |
| `pymk.connection-events.v1` | `CONNECTION_CREATED` | `smallerMemberId:largerMemberId` | Positive int64 `aggregateSequence` |

Both envelopes require `schemaVersion=1`, stable lowercase canonical UUID
`eventId`, `eventType`, UTC `occurredAt` and `ingestedAt`, distinct positive int64
`subjectMemberId`/`targetMemberId`, UUID `correlationId`, and `payload`. For the
five current member event kinds, the target is required, not optional. Timestamps
use `Z` with at most nanosecond precision. Payloads and envelope fields are closed:
unknown fields/types require a new contract/topic version, not a silent v1 edit.
Golden fixtures and frozen-schema fingerprints make this compatibility policy
an automated regression check, not a claim of arbitrary forward compatibility.

Member events are directional: an acceptance event follows the original invitation
direction used by the existing label pipeline. Connection events canonicalize
the pair and do not manufacture an `INVITE_ACCEPTED` training label. Day 32 must
allocate the pair's aggregate sequence in the domain/outbox transaction; event
time and Kafka offset are not substitutes for a domain sequence.

Example member event (a contract example, not an implemented HTTP endpoint):

```json
{
  "schemaVersion": 1,
  "eventId": "1fdf16fa-e0b9-4d38-8c97-182b247c4eae",
  "eventType": "PROFILE_VIEW",
  "occurredAt": "2026-01-01T12:00:00Z",
  "ingestedAt": "2026-01-01T12:01:00Z",
  "subjectMemberId": 42,
  "targetMemberId": 100,
  "correlationId": "7c869a89-1317-453c-8b67-560261016538",
  "payload": {}
}
```

The immutable Java envelope derives topic/key. `EventContract.encode` and
`decode` apply the same contract. Historical replay is structurally valid;
member existence, server-assigned receipt time, permitted future clock skew,
admission and authorization belong to Day 32 ingestion, not this timeless codec.
Do not add current-time validation to replay consumers.

### Duplicate identity versus broker delivery

`compareIdentity` distinguishes different IDs, matching producer content and
same-ID conflicts. SHA-256 is computed over deterministic typed JSON, not incoming
field order/whitespace; timestamps are normalized. The content includes event
type, IDs, correlation, occurrence time and payload, but excludes server-assigned
`ingestedAt`. A retried request must preserve its producer fields and receive the
**original** stored receipt/time, not overwrite it with a new ingestion time.

This helper has no in-memory or durable deduplication cache. Day 32 needs a
transactionally unique receipt/event ID and conflict check; Day 33 needs
`(consumerName,eventId)` identity plus projection effects in one transaction.
Producer idempotence does not provide exactly-once PostgreSQL effects. Replays
keep original IDs and receipt metadata; delivery/attempt IDs are separate audit
fields, never replacement business identities.

## Topology, retention and replay policy

Both current topics have three partitions, replication factor one,
`min.insync.replicas=1`, delete-only retention of seven days (`604800000` ms),
`retention.bytes=-1`, one-hour segments, and `message.timestamp.type=LogAppendTime`.
Thus a late historical `occurredAt` does not prematurely age a newly appended
Kafka record out of retention. Domain event/ingestion times remain in the value.
Retention is segment-based, not an exact deletion instant or a backup guarantee.
Monitor disk usage: unlimited byte retention does not mean unlimited disk capacity.

Auto topic creation is disabled. `KafkaTopics.developmentTopics()` and the Compose
initializer are checked against a real broker in integration tests. Stable keys
give order within a partition, not global order. Changing partition count may
move a key and is an explicit ordering migration, not a casual scaling switch.

Planned independent groups:

- `pymk.feature-projection.v1`: member/connection projections.
- `pymk.cache-invalidation.v1`: connection-driven invalidation.
- Future analytics uses its own group. Replicas of the same logical consumer
  share a group; unrelated effects must not share one and divide each other's records.

Day 33 consumers will disable auto commit, commit only after durable effects,
and define per-key retry behavior. A fresh projection can replay within available
retention; an existing group resumes committed offsets. Ordinary online consumers
should use `auto.offset.reset=none` and fail visibly on missing/expired offsets;
only explicit rebuild tooling may choose `earliest` after verifying its coverage.
Integration tests explicitly use `earliest` for new isolated groups, not a silent
production recovery policy.

Retain consumer identity at least through the seven-day source replay horizon
plus maximum retry/outbox/recovery delay. Planned DLTs
`pymk.member-events.dlt.v1` and `pymk.connection-events.dlt.v1` have a 30-day
recovery horizon, so their replay requires identity retention covering that
horizon too (or a fresh isolated projection rebuild). DLTs are **not created or
consumed on Day 31**. Replay beyond retained history requires an authoritative
PostgreSQL rebuild at explicit event/knowledge watermarks; never silently reset
an existing projection's offsets and double-apply effects. Unpublished accepted
outbox rows must not expire under Kafka retention rules (Day 32).

### Development versus production

This is a local plaintext, single-node convenience stack, not HA or a secure
public service. Acknowledged records survive ordinary container recreation when
the named volume survives; loss of that disk/node is not tolerated. Production
requires a separately reviewed deployment: multiple brokers, typically replication
factor three/min ISR two, `acks=all`, producer idempotence, TLS/SASL and topic/group
ACLs, backups, retention/capacity monitoring and tested failover. Do not claim those
guarantees from the local broker test. Admin ports/UIs must not be exposed publicly.

See the official [Kafka Docker guide](https://kafka.apache.org/39/getting-started/docker/)
and [Testcontainers Kafka module](https://java.testcontainers.org/modules/kafka/).

## Verification and reproducible smoke

```powershell
mvn -B -ntp verify
& pymk-ml-training/.venv/Scripts/python.exe -W error -m unittest discover -s pymk-ml-training/tests -v
& pymk-ml-training/.venv/Scripts/python.exe -m pip check
docker compose -f infra/docker-compose.yml config --quiet
docker compose -f infra/docker-compose.yml --profile kafka config --quiet
./scripts/day31-kafka-smoke.ps1
./scripts/day23-release-smoke.ps1 -IncludeBlendTuning
```

The new smoke script uses a GUID-named Compose project, free loopback port and
disposable topic. It checks host and container listeners, topic settings,
publish/consume, then recreates the broker and verifies persisted replay and
idempotent initialization. It removes **only its own** temporary containers and
volumes in `finally`; evidence remains under `artifacts/day31-smoke/`. Normal
Compose data is not deleted. The JVM smoke class is explicitly selected by this
script; it does not require a running Compose broker during normal `mvn verify`.
Normal tests use isolated Testcontainers and fail rather than skip if Docker is unavailable.

If a Windows-managed corporate/local CA is trusted by Windows but absent from
Java's default trust store, these invocation-local options can use that existing
trust (do not disable certificate validation or commit machine-specific settings):

```powershell
mvn -B -ntp '-Djavax.net.ssl.trustStoreType=Windows-ROOT' '-Djavax.net.ssl.trustStore=NONE' verify
./scripts/day31-kafka-smoke.ps1 -MavenOptions @('-Djavax.net.ssl.trustStoreType=Windows-ROOT', '-Djavax.net.ssl.trustStore=NONE')
```

### Verification record — 2026-10-09

- Full `mvn -B -ntp verify`: all 13 reactor projects succeeded; **212 Java tests,
  zero failures/errors/skips**, including 40 contract cases and two real-Kafka
  integration tests. Evidence: `artifacts/day31-maven-verify.log`.
- Python `-W error` regression suite: **57 tests passed**; `pip check` found no
  broken requirements. Evidence: `artifacts/day31-python-tests.log`.
- Base and Kafka-profile Compose configuration validated. Kafka smoke run
  `d132c6f6fe` passed host/internal connectivity, checked initialization,
  publish/consume and replay after broker recreation; evidence is under
  `artifacts/day31-smoke/d132c6f6fe/`. Its disposable resources were removed.
- Fresh train-to-packaged-HTTP run `5945966c0e` passed ONNX parity, activation,
  cache namespaces, L2/L1 and re-ranking rollback/re-enable, requested limits
  1/2/20/100, and loading the exported tuned configuration. Development Kafka
  was stopped throughout this run; the packaged API also contains no Kafka
  client/event-stream dependency. Evidence: `artifacts/day31-serving-smoke.log`
  and `artifacts/day23-smoke/5945966c0e/`.
- The small synthetic cohort intentionally does not meet production model-quality
  gates. These are passing correctness/recovery checks, not a production-quality,
  high-availability, performance or universal bug-free guarantee. Day 32 remains
  the next implementation milestone.
