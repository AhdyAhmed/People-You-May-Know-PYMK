# Day 33 — Idempotent consumers, retries and dead-letter recovery

This records the Day 33 checkpoint. [Day 34](DAY34_LIVE_FEATURES.md) adds live
window projections and durable cache epochs through V11, including bootstrap of
existing journals. The receipt, retry and replay guarantees below still apply.

## Implemented behavior

Two opt-in Spring Kafka consumer groups independently process both v1 source topics:

- `pymk.feature-projection.v1`
- `pymk.cache-invalidation.v1`

Their current effect is a normalized, durable event journal. Flyway V10 adds
`event_consumer_receipt`, `event_consumer_journal` and `event_consumer_policy`.
The receipt key is `(consumer_name, event_id)`; the receipt and journal row commit
in **one PostgreSQL transaction before the listener returns**. Record-level,
synchronous Kafka offset commits happen afterward. There is no claim of a
distributed database/Kafka exactly-once transaction.

An identical redelivery makes no second database effect. Changed producer content
under the same ID is a conflict, not an overwrite. Receipt fingerprints preserve
the Day 31 rule excluding server-assigned receipt time. A process failure after
database commit but before offset commit safely repeats the identity check.
Concurrent copies are serialized by the database unique key, not an in-memory cache.

This is working consumer infrastructure and durable inbox projection, **not yet
live recommendation feature updates or asynchronous cache eviction**. Those remain
Day 34. Existing connection API cache invalidation stays synchronous/post-commit;
consumers never create duplicate canonical `member_events` or training labels.

Day 34 must explicitly bootstrap a versioned live projection from this journal.
Adding a new side effect to an existing consumer does not make old receipts run
again; deleting receipts to force that would break the deduplication guarantee.

## Start locally

Use the normal PostgreSQL, Redis and model prerequisites from the README:

```powershell
docker compose -f infra/docker-compose.yml --profile kafka up -d --wait kafka
docker compose -f infra/docker-compose.yml --profile kafka run --rm kafka-init
$env:PYMK_OUTBOX_RELAY_ENABLED = "true"
$env:PYMK_EVENT_CONSUMERS_ENABLED = "true"
$env:PYMK_KAFKA_BOOTSTRAP_SERVERS = "localhost:9092"
java -jar pymk-api/target/pymk-api.jar
```

The relay and consumers are separate opt-ins, both disabled by default. A consumer
does not need the local relay enabled to consume records already in Kafka.
Consumers run in the API process for this portfolio deployment; Kafka remains
outside the synchronous recommendation request path.

Rerun `kafka-init` after upgrading from Day 32. It adds two versioned DLTs without
deleting the existing source topics. All four topics have three partitions/RF1.
Source retention is seven days; DLT retention is thirty days, with delete cleanup,
no byte-retention cap and broker append timestamps. DLTs allow 2 MiB records so
recovery headers fit around a maximum-sized source poison record. This is a local
plaintext single-broker setup, not production security or high availability.

## Retries and ordering

Each consumer processes records synchronously with automatic offset commit disabled,
record acknowledgements and synchronous commits. It makes an initial attempt and
up to three retries, using 250/500/1000 ms exponential delays (2-second cap).
The poll size is one record, the database transaction timeout is ten seconds,
and the maximum poll interval is five minutes. Broker/retry waits are bounded;
infrastructure stalls can still cause rebalances and redelivery, which the durable
receipts handle. Revisit these limits together when adding Day 34 handlers.

Blocking retry preserves the failed record's position: later records in its
partition cannot advance before successful processing or acknowledged DLT recovery.
The current single listener lane can also delay other partitions assigned to it.
The other consumer group has independent offsets and can continue.

Once a poison record is acknowledged in the DLT, later source records can proceed.
A subsequent DLT replay therefore **does not preserve original order**. Day 34
state replacement must reject stale aggregate sequences; additive counters must
deduplicate identity and use event-time windows. Kafka producer idempotence is
not a substitute for consumer database deduplication.

## Dead-letter contract and failure safety

`pymk.member-events.dlt.v1` and `pymk.connection-events.dlt.v1` preserve original
key/value bytes, including malformed UTF-8, JSON, oversized payloads and tombstones.
Non-recovery source headers are copied. Recovery metadata uses UTF-8 headers:

| Header | Value |
|---|---|
| `pymk-dlt-version` | `1` |
| `pymk-dlt-consumer` | Failing consumer group |
| `pymk-dlt-topic` | Original source topic |
| `pymk-dlt-partition` / `pymk-dlt-offset` | Original position |
| `pymk-dlt-timestamp` / `pymk-dlt-timestamp-type` | Original broker timestamp metadata |
| `pymk-dlt-reason` | `INVALID_CONTRACT`, `IDENTITY_CONFLICT`, `REPLAY_BEFORE_FLOOR` or `PROCESSING_FAILED` |

No exception message or payload text is copied into failure-reason headers.
Existing recovery headers are replaced rather than repeatedly accumulated.
DLTs contain member data: restrict read/write access and avoid dumping their
contents into shared logs. Publisher payload logging is disabled.

The DLT producer uses acks=all and idempotence, with 5-second metadata wait and
15-second delivery timeout; recovery waits for the send result. An unsuccessful
or uncertain DLT send throws, preventing advancement of the source offset.
The retry cycle restarts with backoff until recovery works; accepted records are
not discarded after a fixed number of failed DLT attempts.

A crash after DLT acknowledgement but before the source offset commit can duplicate
DLT records. Treat `consumer + source topic/partition/offset` as the recovery
incident identity; replayed valid envelopes retain their original event ID.

The implementation uses Spring Kafka's
[documented error-handler recovery behavior](https://docs.spring.io/spring-kafka/reference/3.3-SNAPSHOT/kafka/annotation-error-handling.html).

## Replay and retention boundary

Receipts have **no automatic expiry or pruning**. Their retention is intentionally
longer than both source and DLT retention, including archived/repeated replay.
Published outbox receipts and consumer journals also survive the development
dataset reset. Stop live writers/consumers before reseeding; resetting a dataset
does not reset event identities.

Each consumer's persisted `min_ingested_at` policy defaults to the Unix epoch.
A previously processed identical event remains a no-op even before that floor.
An unseen event whose original `ingestedAt` precedes the floor is quarantined as
`REPLAY_BEFORE_FLOOR`. Valid late `occurredAt` values are not rejected merely for
being old. The shared policy-row lock prevents a concurrent floor change from
racing acceptance.

Do not lower a floor after deleting deduplication history, or prune receipts and
then replay old events into the same projection. Retain/restore complete identity
history or rebuild a new versioned projection/group before doing so. Source/DLT
retention expiry does not recreate missing records: a new group reads the earliest
**available** source offset, not all historical events. Recovery of expired source
data needs authoritative outbox/archive data or the Day 34 reconciliation path,
not an offset reset that claims to restore deleted Kafka history.

After fixing a transient processing cause, replay one reviewed DLT offset:

```powershell
java "-Dloader.main=com.ahdyahmed.pymk.stream.contract.DeadLetterReplay" -cp pymk-api/target/pymk-api.jar org.springframework.boot.loader.launch.PropertiesLauncher localhost:9092 pymk.member-events.dlt.v1 0 17
```

Replace the topic/partition/offset with the specific incident. This finite CLI
reads exactly that retained offset, checks its version/topic metadata and validates
the original envelope/key. It republishes the exact producer bytes/identity to the
source topic and waits for acknowledgement, without committing a DLT offset,
resetting a group, changing the event ID or copying recovery headers. Both groups
see the replay; a group that already processed it deduplicates it independently.
Unavailable offsets fail closed. Malformed poison is rejected rather than silently
repaired. A genuine identity conflict needs investigation; repeatedly replaying it
does not resolve the conflict or authorize overwriting the original event.

## Checks and evidence

```sql
SELECT consumer_name, count(*) FROM event_consumer_receipt GROUP BY consumer_name;
SELECT consumer_name, count(*) FROM event_consumer_journal GROUP BY consumer_name;
SELECT * FROM event_consumer_policy;
```

```powershell
mvn -B -ntp -pl pymk-api -am "-Dtest=DurableEventConsumerTest,EventConsumerKafkaIntegrationTest,EventDeadLetterPublisherTest,OutboxMigrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
mvn -B -ntp verify
./scripts/day31-kafka-smoke.ps1
./scripts/day23-release-smoke.ps1 -IncludeConsumers
```

Tests cover atomic rollback, conflicting/concurrent identities, independent groups,
commit-before-offset failure, blocking retry, unavailable DLT broker, exact-byte
dead letters, near-limit poison, executable single-record replay and receipt/floor
behavior beyond the DLT retention window. The packaged smoke uses actual HTTP,
outbox scheduling, Kafka, both journal consumers and an API process restart.

Verification on 2026-10-11:

- `mvn -B -ntp verify`: all 13 reactor projects passed; 248 Java tests,
  zero failures, errors or skips (`artifacts/day33-verify.log`).
- Python regression suite: 57 tests passed with warnings treated as errors;
  dependency validation reported no broken requirements (`artifacts/day33-python.log`).
- Kafka Compose smoke passed all four topic contracts, host/internal clients,
  broker recreation with retained data and repeatable initialization
  (`artifacts/day33-kafka-compose.log`, `artifacts/day31-smoke/b2035e9a3f/`).
- The packaged replay entrypoint loaded successfully; its actual acknowledged
  single-record replay and duplicate replay were exercised against real Kafka
  by `EventConsumerKafkaIntegrationTest` in the full Java suite.
- Packaged application smoke passed fresh training, activation, HTTP probabilities,
  caching and two serving rollbacks, plus HTTP ingestion during a Kafka outage,
  scheduled relay recovery, one journal effect per independent consumer and
  duplicate safety after API restart (`artifacts/day33-serving-smoke.log`,
  `artifacts/day23-smoke/7be6e7cfdc/`). Disposable smoke containers and volumes
  were removed; evidence files were retained.
