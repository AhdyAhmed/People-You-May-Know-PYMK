# Day 34 — Live feature windows and durable cache invalidation

## Implemented boundary

Flyway V11 bootstraps a versioned live projection from both Day 33 journals before
the application starts. Existing receipts and historical feature/model tables are
not rewritten. A journal-insert trigger updates the live projection in the same
PostgreSQL transaction as the consumer receipt. A failed projection rolls back
the receipt and journal; Kafka offsets still advance only after commit.

Keep `PYMK_OUTBOX_RELAY_ENABLED=true` and `PYMK_EVENT_CONSUMERS_ENABLED=true`
for continuous delivery using the existing Kafka setup. These remain explicit
opt-ins. No new infrastructure or scheduler is required.

`GET /api/v1/members/{id}/live-features/{candidateId}` exposes directional
`profileViews`, `invitesSent`, and `invitesAccepted`, plus generation, window start,
as-of time and knowledge cutoff. Both members must exist and differ. The default
window is 30 days; `pymk.live-features.window-days` permits 1–365 days.
The endpoint is uncached and does not imply that Kafka has caught up: an accepted
event may not appear until its consumer commits or reconciliation runs.

Event membership uses immutable event IDs, not increment-only totals. Every
distinct event with `windowStart < occurredAt <= asOf` and
`ingestedAt <= knowledgeCutoff` counts once, regardless of arrival order.
This matches the historical feature window's exclusive-start/inclusive-end
semantics. Expiry is evaluated at read time without needing another event or
scheduled decrement. Future-skew events count only once their event time arrives.

Live counters are **not silently substituted into L1/L2 model inputs**. Managed
training/serving snapshots and labels retain their existing reproducible release
boundary. A future model using live signals requires its own compatible training
contract and release evaluation. These live counters cover the versioned event
pipeline, not pre-Day-32 synthetic `member_events` rows without wire identities.

## Connection invalidation

The cache consumer stores the greatest aggregate sequence per connection pair.
Duplicate or older sequences cannot replace newer state or advance cache versions.
A newer connection event atomically increments durable `member_cache_epoch`
values for both endpoints and their current two-hop neighborhoods. Recommendation
cache keys include that member epoch across L0, results and re-ranking pools.
Redis does not participate in this transaction: unavailable Redis cannot lose an
invalidation. Old Redis keys expire by their normal TTLs.

Request scopes pin each member's epoch before using its cache. An in-flight
request may finish against its old generation, but cannot populate the new
generation with its old result. Unrelated members keep their cache namespaces.
Existing synchronous post-commit invalidation and global source revision checks
remain correctness safeguards. This is durable namespace invalidation, not a
claim of atomic transactions across PostgreSQL and Redis.

## Finite repair during Kafka downtime

Run the packaged batch job with explicit UTC event-time and knowledge boundaries:

```powershell
$cutoff = [DateTime]::UtcNow
$windowStart = $cutoff.AddDays(-30).ToString('o')
$windowEnd = $cutoff.ToString('o')
java -jar pymk-batch/target/pymk-batch.jar --spring.batch.job.name=liveFeatureReconciliationJob "windowStart=$windowStart" "windowEnd=$windowEnd" "knowledgeCutoff=$windowEnd"
```

Choose the window needing repair and a cutoff no later than now. Increasing
window bounds are required; future ends/cutoffs fail validation. For a repeat of
an already-completed identical Batch job instance, supply a new `runId=...`.

The job uses the same live event membership and window semantics as online reads:

1. Acquire the API admission lock and lock the active projection pointer, fencing
   source admission and concurrent live writes during replacement.
2. Create a new generation. Preserve events outside the repair window and events
   known after the requested cutoff; an older cutoff cannot erase newer knowledge.
3. Rebuild the selected window from retained feature journals and authoritative
   outbox envelopes, including unpublished records. The accepted outbox identity
   takes precedence when both sources contain the same event ID.
4. Recover connection invalidations from retained outbox records through the
   cutoff, using the same sequence-aware function as streaming consumers.
5. Atomically switch the generation pointer. Waiting consumers then insert into
   the new generation. Kafka delivery/replay of repaired events remains a no-op
   for their live membership or already-applied connection sequence.

The job has a 120-second transaction timeout, records its generation in Spring
Batch metadata and exits. A failure rolls back both state and activation. It
does not run the legacy in-place feature computation job, reset Kafka offsets,
delete receipts, or depend on Kafka/Redis availability. Airflow scheduling remains
Day 43, not part of this implementation.

The cutoff is a **repair knowledge boundary**, not a historical export: active
state retains newer-known events and the API reads current time. Historical
training continues to use its existing snapshot/export jobs. Previously activated
live generations remain immutable and retained for inspection; no automatic
pruning is introduced. Retained journal/outbox history is required for recovery
after Kafka retention expiry. Purging it removes that guarantee. This finite
portfolio implementation scans retained sources and holds the admission lock;
benchmark and partition/chunk it before using production-scale histories.

## Verification

```powershell
mvn -B -ntp verify
./scripts/day23-release-smoke.ps1 -IncludeLiveFeatures
```

Tests cover migration of populated Day 33 journals, late/out-of-order delivery,
duplicates, window boundaries/expiry, ingestion cutoffs, stale connection
sequences, targeted cache namespaces, in-flight cache writes, rollback,
generation replacement concurrent with delivery, real Kafka projection effects,
the finite Batch job and invalid parameters. The packaged smoke pauses Kafka,
pauses Redis during reconciliation, checks live HTTP counters and cache epochs,
then verifies replay and API restart without duplicate effects.

Verified on 2026-10-12:

- Full `mvn -B -ntp verify`: all 13 reactor projects passed; 259 Java tests,
  zero failures, errors or skips (`artifacts/day34-verify.log`).
- Python regression: 57 tests passed with warnings treated as errors; `pip check`
  reported no broken requirements (`artifacts/day34-python.log`).
- The full Java suite includes the populated V10-to-V11 upgrade, nine live
  projection tests, six real-Kafka consumer tests and two finite reconciliation-job
  tests. Existing historical feature, model, serving and outbox regressions passed.
- Packaged `-IncludeLiveFeatures` smoke passed fresh training, serving activation,
  HTTP probabilities, caching and two rollbacks. Reconciliation succeeded with
  Kafka stopped and Redis paused; live HTTP counters and cache epochs stayed
  correct after Kafka recovery/replay and API restart
  (`artifacts/day34-serving-smoke.log`, `artifacts/day23-smoke/f57757357b/`).
  Disposable test containers/volumes were removed; evidence logs were retained.
