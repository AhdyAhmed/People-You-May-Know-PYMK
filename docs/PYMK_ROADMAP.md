# PYMK Backend — Build Roadmap: Core, Kafka & MLOps

Companion to `PYMK_DESIGN.md`. The first 30 focused workdays deliver M1–M5;
M6–M10 are explicit post-Day-30 extensions. The schedule assumes roughly 1–2
focused hours per day, but completion is evidence-based rather than calendar-based.

**Current checkpoint:** Day 34 complete (M1–M5 complete; M6 in progress).
Durable ingestion/outbox, idempotent consumers, DLT recovery, live event windows,
targeted cache epochs and finite reconciliation are implemented and verified.
Day 35 / Kafka resilience and observability is next.

**Planned sequence:** Kafka (M6, Days 31–35) → MLflow (M7, Days 36–40) →
Airflow + batch operations (M8, Days 41–45) → observability (M9) → portfolio
release (M10). MLflow and Airflow are not implemented at the Day 34 checkpoint.
This expansion preserves Days 1–30 and Kafka's existing scope; the former M7
batch-operations work moves into M8, and the former M8/M9 become M9/M10.

**Definition of done for every day:** the reactor compiles, relevant automated
tests pass, migrations and entity mappings agree, and user-facing behavior or
architectural decisions are reflected in the README/design docs. A manual check
alone does not mark a day complete when the behavior can be automated.

Completed Days 1–34 are the implementation record. Unchecked future days below
are acceptance criteria, not completed features. The 1–2 hour estimate is a
planning guide: preserve correctness and move an unfinished deliverable rather
than marking it complete on schedule. Model/data releases must be reproducible,
versioned, compatible with serving, and reversible.

### Dependency and acceptance guide

Days 1–34 are the completed checkpoint. Day 23 supplies the coordinated
embedding/feature/model activation mechanism before live vectors change.
Day 24 adds release-bound multi-target datasets; Day 25 adds offline heavy training.
Day 26 adds the opt-in Java heavy scorer; Day 27 adds frozen offline evaluation.
Day 28 adds explicit public L2 serving with an L1 rollback path. Day 29 adds
configured blending and employer exposure selection; Day 30 adds validation-only
Bayesian tuning and immutable deployment configuration without automatic promotion.
M7 adds tracked experiments and registry-backed release preparation; M8 extends
the existing jobs to scheduled refreshes and reconciliation. Use the existing
Day 19 trainer for a compatible L1 release when changing embeddings, rather than
waiting for L2 or reusing placeholder-derived training features.

For every future release, retain a concise evidence record: input/cohort hashes,
source snapshot and knowledge cutoff, artifact/runtime versions, commands and
results, known availability limits, and a demonstrated recovery/rollback. Quality
or load gates need stated metrics and budgets before a promotion; failing them
keeps the previous release active. Follow the design's section 1.3 dependency
table and section 7.1 manifest contract when implementing those gates.

---

## Week 1 — Foundations & Domain (M1)

**Day 1 — Repo & environment setup ✅**
- Create the multi-module Maven project (`pymk-common`, `pymk-domain`, `pymk-api`, etc. as empty modules).
- Docker Compose with Postgres + Redis (pgvector extension enabled on the Postgres image).
- Push initial skeleton to GitHub with a placeholder README.

**Day 2 — Core JPA entities ✅**
- Implement `Member`, `Connection`, `MemberEvent` entities in `pymk-domain`.
- Write Flyway migration scripts for the schema (indexes on `connections`).

**Day 3 — Repositories & basic queries ✅**
- Spring Data JPA repositories for the three entities.
- Write a repository test (Testcontainers Postgres) confirming symmetric edge inserts work and adjacency lookups are fast.

**Day 4 — Synthetic data generator ✅**
- Small CLI/batch script to generate ~50K–100K fake members (Faker library), a realistic connection graph (e.g. preferential attachment or small-world graph), and temporally valid simulated events.
- Load it into Postgres; sanity-check counts and distribution.

**Day 5 — pgvector + embeddings table ✅**
- Add `member_embeddings` entity/table.
- Generate placeholder embeddings for synthetic members (random vectors is fine for now — real embeddings come in Week 4).
- Confirm a cosine-distance ANN query (`<=>` operator) runs and returns sensible neighbors.

**Day 6 — `pymk-api` skeleton ✅**
- Stand up `GET /api/v1/members/{id}` and `POST /api/v1/connections` as the first real endpoints.
- OpenAPI/Swagger UI wired up.
- *Built in 3 parts:* **(1)** OpenAPI/Swagger + `GET /members/{id}` + error handling ✅ · **(2)** `POST /connections` ✅ · **(3)** status/API documentation + end-to-end test ✅.

**Day 7 — Buffer / catch-up + write-up ✅**
- Audited Days 1–6 and reconciled the live milestone, roadmap, design, and README status.
- Added the root README section "Data model & how to seed the DB," including invariants, safe reset behavior, configuration overrides, and verification commands.

---

## Week 2 — Naive End-to-End PYMK (M2)

**Day 8 — `CandidateSource` interface ✅**
- Added the `CandidateSource` contract and immutable `CandidateHit` carrying candidate ID, source type, source-local score, and string metadata.
- Implemented deterministic `HeuristicCandidateSource` ranking exact company/school/geo matches by match strength, then member ID.
- Added a shared batched eligibility policy that excludes self, existing connections, duplicates, and missing/deleted members, with real-Postgres integration tests.

**Day 9 — Graph-walk + embedding candidate sources ✅**
- Implemented `GraphWalkCandidateSource` with a cycle-safe recursive CTE, shortest 2/3-hop distance, shortest-path counts, deterministic ordering, and pre-limit direct-connection exclusion.
- Implemented `EmbeddingRetrievalCandidateSource` using pgvector cosine distance, similarity scoring, and pre-limit direct-connection exclusion.
- Added deterministic real-Postgres graph/vector fixtures covering provenance, scoring, limits, absent embeddings/members, and shared eligibility behavior.

**Day 10 — Union + parallel fan-out ✅**
- Added `L0CandidateGenerator` with semaphore-bounded Java 21 virtual-thread fan-out across all registered sources.
- De-duplicated by member ID while retaining immutable per-source hits, and used reciprocal-rank fusion so incomparable source-local scores are not mixed directly.
- Added configurable per-source budgets, a 3,000 default global cap, deterministic ordering, strict failure propagation/cancellation, and tests for concurrency, budgets, provenance, caps, validation, and source failures.

**Day 11 — Naive orchestrator + endpoint ✅**
- Added `RecommendationOrchestrator`, which enriches the Day 10 L0 union with one bulk mutual-connection query and sorts by mutual count, fusion score, then candidate ID.
- Exposed `GET /api/v1/pymk/{memberId}?limit=20`, with a default of 20, a server-enforced maximum of 100, explanation reasons, and the established Problem Details contract for invalid, missing-member, and temporarily unavailable paths.
- Added orchestrator unit tests plus a full MockMvc → L0 → PostgreSQL/pgvector integration fixture covering eligibility, ordering, limits, errors, and OpenAPI discovery.

**Day 12 — Redis caching ✅**
- Added Redis-backed caches for the full L0 union and the top-100 final result; requested limits are sliced from one reusable per-member result rather than fragmenting keys by limit.
- Added validated, configurable TTLs and schema/pipeline/model versions in every key, JSON stable-DTO payloads, and cache-error fallback to the underlying pipeline.
- Connection creation now invalidates both layers after commit for the endpoints and their pre-mutation two-hop neighborhoods, covering requesters whose three-hop paths can change.
- Added real-Redis Testcontainers coverage for hit/miss, payload round trips, TTL expiry, versioned keys, and post-commit graph invalidation.

**Day 13 — Recommendation QA pass ✅**
- Added a real Postgres/pgvector + Redis end-to-end quality fixture and checked several synthetic requester IDs, including mutual-heavy, shared-profile, direct-connection, and unrelated members.
- Automated the safety invariants across representative and boundary limits: no self, no existing connections, no duplicates, no missing candidates, and requested limit respected.
- Verified deterministic cached responses, non-increasing mutual-count ordering, explanation reasons, and a sensible baseline ordering (two mutuals before one mutual before a profile-only match).
- Added `scripts/day13-recommendation-smoke.ps1` for repeatable spot checks against any locally running seeded application.

**Day 14 — Buffer + README update ✅**
- Declared the M2 "naive v1 pipeline is live" milestone and documented the complete request path from concurrent L0 retrieval through Redis-backed top-100 slicing.
- Added a runnable request, public response example, stable-contract guidance, and an explicit account of current baseline limitations.
- Reconciled README, design, roadmap, OpenAPI wording, and the live status endpoint with the Day 14 implementation boundary.

---

## Week 3 — Feature Store & Light Ranker (M3)

**Day 15 — `pymk_features` table + `PairId` ✅**
- Added the directional, serializable `PairId` composite key, `PymkPairFeature` entity, and Spring Data repository in the previously skeletal feature-store module.
- Added Flyway V3 with member foreign keys, a composite primary key, self-pair/count/cosine/time constraints, and indexes for member, candidate, and stale-snapshot access paths.
- Preserved both `featureAsOf` (the leakage boundary) and `computedAt` (materialization audit time), with Java and database enforcement that the snapshot cannot be later than computation.
- Added real-PostgreSQL migration/mapping/repository tests and kept the synthetic-data reset path compatible with dependent feature rows.

**Day 16 — Feature computation logic ✅**
- Added `PairFeatureComputer` to produce the complete directional feature vector without persisting it; the Day 17 batch job will own chunked upserts.
- Added snapshot-bounded mutual-connection and pair-event queries, an exact nonblank profile-match function, safe cosine similarity, and neutral similarity when either embedding was not available at the snapshot.
- Defined the profile-view window as `(featureAsOf - 30 days, featureAsOf]` and rejected missing/not-yet-created pair members and snapshots later than computation time.
- Added pure unit tests plus real-PostgreSQL/pgvector integration coverage for all signals, directionality, time boundaries, future-data exclusion, missing embeddings, and invalid inputs.

**Day 17 — `FeatureComputationJob` (Spring Batch) ✅**
- Turned `pymk-batch` into a runnable Spring Boot/Spring Batch application with a required, validated `featureAsOf` parameter, job-owned `computedAt`, and Flyway-managed Batch metadata (V4).
- Added a restartable cursor over members that existed at the snapshot and bounded, snapshot-safe candidate retrieval from two/three-hop graph walks, exact profile matches, and pgvector similarity; self, direct, future, and duplicate candidates are excluded.
- Chunk-computed Day 16 features with non-regressing PostgreSQL upserts; stale older rows are deleted only after successful materialization. Committed chunks can coexist with older rows during a refresh or after failure; L1 rejects mixed snapshots and the failed job must be restarted before affected requests can resume.
- Ran the complete job repeatedly against a deterministic synthetic PostgreSQL/pgvector fixture and verified completion, feature values, time boundaries, stale-row cleanup, stable row counts, parameter rejection, rerun idempotency, and rejection of an older snapshot.

**Day 18 — Label generation for training ✅**
- Added the restartable `TrainingDatasetExportJob` with an explicit feature snapshot, a configurable 30-day observation window, and a required mature `labelWindowEnd`; future or reversed windows and missing feature snapshots are rejected.
- Labeled a directional pair positive only when its `INVITE_ACCEPTED` occurs strictly after `featureAsOf` and at or before `labelWindowEnd`, excluding snapshot-boundary, post-window, reverse-direction, and non-acceptance events; Flyway V5 adds the pair/type/time lookup index.
- Exported deterministic, temporally ordered CSV rows containing the complete Day 16 feature vector, window metadata, and a binary label through a restartable cursor/chunk writer.
- Wrote to an execution-private staging file and promoted it only after success, preserving the previous artifact on failure; real-PostgreSQL integration coverage verifies boundary safety, ordering, rerun determinism, validation, and cleanup of staging files.

**Day 19 — Train logistic regression offline ✅**
- Turned `pymk-ml-training` into an installable Python 3.11+ project that strictly validates and combines Day 18 CSV exports, rejects schema/temporal drift and duplicate snapshot pairs, and trains a class-balanced, scaled logistic-regression baseline with a fixed seed.
- Added chronological train/validation/test splitting at the whole-snapshot level, requiring at least three distinct `feature_as_of` values so no snapshot can cross partition boundaries.
- Exported the scaler plus classifier to an ONNX opset-17 model and atomically published a metadata report containing feature order, input hashes, temporal boundaries, metrics, coefficients, runtime versions, and the model SHA-256.
- Validated the ONNX graph and executed it with ONNX Runtime, requiring its probabilities to match scikit-learn; automated tests cover temporal isolation, invalid inputs, artifact contracts, and end-to-end training/export. CI now verifies the Python project alongside the Maven reactor.
- Day 22 audit: reject an existing ONNX or metadata version before training and publish completed files without overwrite, including concurrent publication attempts. An interrupted pair cannot be promoted; retraining uses a new version and serving verifies the pair's checksum.

**Day 20 — `pymk-light-ranker` serving ✅**
- Added ONNX Runtime Java model loading with strict validation of the Day 19 metadata schema, six-feature order, input/output tensor contracts, positive class, and model SHA-256; incompatible or corrupted artifacts fail before serving.
- Implemented `L1LightRanker.rank(memberId, candidates)` with one batched feature-store query and one batched inference call, preserving L0 provenance while returning a configurable top 500 by positive-class model probability. Class weighting means these scores are not yet validated as calibrated acceptance rates.
- Made ties deterministic with L0 fusion score and candidate ID, rejected missing features and mixed snapshots instead of serving partial results, and exposed Spring configuration for model/metadata paths and the output cap.
- Added real PostgreSQL query coverage, unit coverage for feature order/ranking/failure paths, and a cross-language test that loads and scores an actual model exported by the Day 19 Python pipeline.

**Day 21 — Wire L1 into orchestrator + buffer ✅**
- Changed the live request path to cached L0 generation → batched L1 scoring → reusable top-100 response; the public `score` is now the model's positive-class probability rather than a mutual-count placeholder.
- Reused the mutual count carried by the L1 feature row for explanations and the public signal field, removing the baseline orchestrator's redundant bulk mutual-count query.
- Moved Redis to `l0-l1-v1` / `light-logreg-v1` namespaces so naive results cannot leak across the cutover, while preserving existing mutation invalidation and cache-failure fallback behavior.
- Added automated before/after ordering comparison, L1 failure-to-503 coverage, full PostgreSQL/pgvector + Redis API fixtures with materialized features, probability-score assertions, and cached-path determinism across several sample members.
- Hardened the Day 21 contract: verify the configured cache namespace against the loaded model before reading either cache layer; evict null, wrong-owner, self/duplicate, oversized, and unordered cached results; reject invalid model probabilities with the serving-error contract.
- Added a full HTTP → L0 → PostgreSQL feature store → actual ONNX Runtime → Redis integration test, complementing deterministic model fixtures. Updated the manual smoke check to enforce bounded, finite, descending model probabilities.
- Hardened Day 19 training: preserve label-window end times, reject training outcomes extending into validation and validation outcomes extending into test, and record verified label cutoffs in model metadata. No overlapping partition is fitted or exported.

---

## Week 4 — Heavy Ranker (M4)

**Day 22 — Richer pair features ✅**
- Implemented the runnable Python profile TF-IDF/SVD fit/transform CLI, immutable checksum-validated JSON transforms, and staging CSVs with finite normalized 128-dimensional vectors; blank/tokenless/unseen profiles yield zero vectors and small corpora have deterministic bounded transforms.
- Required explicit profile observation timestamps and timezone-aware cutoffs; excluded not-yet-created members and rejected unavailable historical profiles and future-fitted transforms. Frozen vocabulary, IDF and SVD components are reused without refitting.
- Added V6 embedding version/fit-cutoff provenance without changing the six-feature L1 order. Java validates vectors and defensively copies arrays, rejects mixed embedding spaces in pair computation, and excludes neutral vectors and other versions from stored-member cosine retrieval (including batch snapshot retrieval).
- Shared Python/pgvector fixtures verify semantic neighbors, dimensions and cosine ordering. Tests cover determinism/reload, invalid provenance, blank profiles, small corpora, immutability, checksums and temporal boundaries. Coordinated replacement of the live placeholder generation belongs to Day 23; this CLI never modifies the live database or caches.
- Verification after the design audit: full `mvn verify` builds all 12 modules with 120 tests and no failures/errors/skips; Python `-W error -m unittest discover -s tests -v` passes 19 tests. Real HTTP/ONNX/PostgreSQL/Redis integration, V6 migration, embedding CLI regression tests and Docker Compose configuration pass. These checks are evidence for this checkpoint, not a guarantee against every future defect.
- Follow-up audit: validate complete transform metadata and exact component shapes even with a valid checksum; reject ragged/duplicate-header CSVs and conflicting requested versions; handle equivalent bags of words without unstable SVD variance. Scope raw-vector queries to legacy embeddings or require explicit provenance, and compare fit cutoffs as well as versions in online/batch retrieval and pair computation.

**Day 23 — `EmbeddingRefreshJob` ✅**
- Stage a complete embedding generation with restartable computation, validate coverage and vector contracts, and activate it only after success; failed runs retain the previous active generation.
- Rematerialize compatible pair features, define cache invalidation and rollback, and verify rerun determinism and interruption/restart behavior. Coordinate model/feature/embedding versions as one serving release.
- Implement immutable generation IDs and a release record binding the staging manifest (source snapshot, transform hash/cutoff, vector generation and candidate budgets) to compatible features/model hashes. Preserve deployment pipeline/cache schema versions and add release ID/epoch to cache keys. Validate CSV hashes, duplicate/missing members and zero-vector coverage before promotion.
- Stage compatible features and evaluate/retrain L1 with the existing Day 19 workflow on matching mature snapshots. Switch a single active-release pointer after all gates pass; pin it for each request and reject stale concurrent promoters. Inject import/feature/model failures and prove that active data and rollback remain usable. This prerequisite cannot be deferred to future scheduling (now M8) or implemented with the Day 17 in-place writer alone.
- Implemented: V7 release-scoped vectors/features, immutable transform/manifest registry, resumable import/member chunks, source-revision fencing, registered training exports and provenance sidecars, transform-compatible L1 training, AUC/ONNX promotion gates, database-held model artifacts, atomic epoch-checked activation/rollback and request-pinned release cache namespaces. The release record binds the staging manifest to the validated model contract (`pair-v1`); existing pipeline/cache schema configuration remains a deployment setting, not a mutable per-release override.
- Reproduction and limitations: [Day 23 runbook](DAY23_RELEASES.md). `scripts/day23-release-smoke.ps1` exercises actual Python training, PostgreSQL/pgvector staging, public HTTP/ONNX serving, Redis namespaces and rollback with isolated containers. Automatic scheduling, retention cleanup, production-scale performance and Kafka remain future work.
- Verified 2026-10-03: full 12-module `mvn verify`, 124 Java tests and 21 Python tests passed; real train-to-HTTP smoke and two rollback transitions passed. See the runbook's verification record for scope and fixture-quality limits.

**Day 24 — Training data v2 + multi-target labels ✅**
- Add an explicitly versioned v2 export with directional `invite_sent` and `invite_accepted` outcomes in one mature future window; preserve the existing Day 18/L1 CSV contract.
- State that both targets are unconditional window outcomes; an acceptance can refer to an invitation sent before the snapshot. Test event types, direction, boundaries, duplicates, absent outcomes, and future-data exclusion.
- Archive exports before the current live pair table replaces their snapshots. Keep chronological partitions with label horizons ending before the next partition's feature start, and record cohort/feature/embedding versions.
- Bind each export to one pinned release/source snapshot and document event-time versus knowledge-time reconstruction. Corrected labels/data produce new dataset identities; archived inputs are never silently rewritten for a reported experiment.
- Implemented `servingReleaseJob action=export-v2`, V8's separate immutable dataset registry, binary unconditional labels, per-row schema version, CSV/cohort hashes, source/feature/embedding provenance, concurrent-publisher fencing and the strict Python v2 loader/chronological-split CLI. Existing L1 exports/training remain v1. See [Day 24 runbook](DAY24_DATASETS.md); the extended smoke runs with `-IncludeV2`. L2 training/serving remain Days 25–26, not part of this checkpoint.
- Verification completed 2026-10-04: all 12 Maven modules, 128 Java tests and 27 Python tests passed; real Java-to-Python v2 export validation, existing L1 training/HTTP serving and both rollback transitions passed. Detailed evidence and limitations are recorded in the runbook.

**Day 25 — Train the heavy model offline ✅**
- Train a small PyTorch network with two named probability heads and fixed seeds; fit preprocessing only on training data and export it with the ONNX artifact.
- Publish versioned metadata for ordered features, tensor shapes, output/label meanings, checksums, training cutoffs, and compatible embedding generations. Require Python/ONNX parity before publishing.
- Compare against the frozen L1/baseline cohorts; a more complex model is not evidence of improved quality.
- Fit vocabulary/IDF/SVD/scaling and network parameters on training data only. Validation selects hyperparameters/calibration; each temporal fold pins its upstream transforms and excludes the test feature corpus from fitting.
- Implemented seeded, single-thread CPU PyTorch MLP with train-only scaling, validation-loss checkpoint selection, two named dynamic ONNX probability outputs, actual runtime parity, immutable artifacts and complete v2/embedding provenance. Reports frozen-L1 acceptance and mutual-count ranking comparisons on identical cohorts; single-class AUC/AP remain unavailable. No calibration or automatic promotion is claimed. See [Day 25 runbook](DAY25_HEAVY_TRAINING.md); `-IncludeHeavy` extends the isolated smoke while public serving remains L0 → L1.
- Verified 2026-10-04: all 12 Maven modules, 128 Java tests and 33 Python tests passed; extended fresh-export/training/ONNX/HTTP/rollback smoke, dependency checks and Compose configuration passed. See the runbook's evidence record; L2 Java serving remains Day 26.

**Day 26 — `pymk-heavy-ranker` serving ✅**
- Load the ONNX model via ONNX Runtime Java; batch-score all L1 survivors for a member in one inference call.
- Validate both named outputs, feature/embedding compatibility, checksums, and probability bounds before serving. Test actual exported-model inference, parity, missing features, empty batches, invalid outputs, and model resource cleanup.
- Implemented transactional release-pinned feature reads and one inference batch for every L1 survivor; no truncation, reordering or premature head blending. Strict metadata/native tensor contracts and managed-release transform checks fail closed. Default-disabled Spring wiring preserves the public L1 API until Day 28. See [Day 26 runbook](DAY26_HEAVY_SERVING.md) for configuration and reproducible checks; `-IncludeJavaHeavy` extends the fresh-training smoke with Java/Python parity and native lifecycle tests.
- Verified 2026-10-04: full 12-module build, 146 Java tests and 34 Python tests passed with no test failures/errors/skips. Fresh-training Java parity, heavy-enabled API startup, HTTP/cache/rollback smoke, dependency checks and Compose configuration passed. This is a tested component milestone, not public L2 cutover or a calibration/performance claim.

**Day 27 — Offline eval: AUC / Precision@k / ECE ✅**
- Script to evaluate the heavy ranker against the held-out time split; log AUC, Precision@k, ECE, and comparison with the mutual-connections baseline.
- Evaluate the class-weighted L1 scores separately for calibration; use untouched chronological validation data for any calibration fitting and reserve test data for the final report. Do not claim measured acceptance rates before this evidence exists.
- Define requester-grouped Recall@k/Precision@k denominators and coverage, include prevalence and unavailable single-class metrics, and distinguish full-pool recall from recall within L0 candidates. Compare every variant on the same frozen cohort and report uncertainty.
- Include blank-profile, new-member, degree and candidate-source cohorts, positives lost at each stage, fixed candidate budgets and requester-bootstrap intervals. Set quality acceptance thresholds before viewing the final test comparison.
- Implemented immutable, checksum/provenance-bound reports on the actual heavy/L1 ONNX models and identical frozen pair pools. Both targets include ranking metrics; probability models include raw ECE/Brier and reliability bins. Query denominators, single-class metrics, small-pool coverage and paired requester-bootstrap uncertainty are explicit. A versioned policy fixes thresholds/budgets; insufficient evidence blocks its offline quality gate, never changes serving.
- Historical subgroup and L0/L1 loss analyses accept a strict hash-bound replay-context input. Existing v2 exports do not record those fields: without context they are reported unavailable and the gate cannot pass. Full eligible-population recall and pre-export losses remain unavailable, not approximated by exported-pool recall. No calibrator is fitted on checkpoint-selection validation/test data. The existing Day 25 fixture is not retroactively claimed as blind preregistered evidence. See [Day 27 runbook](DAY27_EVALUATION.md); use `-IncludeEvaluation` for the full smoke.
- Verified 2026-10-04: all 12 Maven modules, 146 Java tests and 46 Python tests passed; dependency/Compose checks and fresh-training evaluation/Java parity/API/cache/rollback smoke passed. The small smoke cohort correctly fails the evidence quality gate without altering serving. Final evaluation includes the UTC-cutoff and float32 ECE-bin regression fixes; see the runbook's evidence record.

**Day 28 — Wire L2 into orchestrator + buffer ✅**
- Integrate L0 → L1 → L2 with an explicit pipeline/cache version and configurable L1 rollback path. Serve complete results or the existing `503` contract; keep score semantics and OpenAPI/docs synchronized.
- Verify live HTTP requests, eligibility, deterministic ties, cache invalidation, rollback, and dependency/model failure cases. Measure per-stage and end-to-end cold/warm p50/p95/p99 with stated data size, hardware, concurrency, and request mix.
- Implemented explicit process-level L1/L2 modes, one pinned request across all stages, heavy acceptance-head ordering (candidate-ID ties), reusable top-100 results, warm-hit compatibility validation and no silent fallback. Derived artifact/semantics/budget and pinned source-revision cache namespaces prevent cross-mode reuse after source changes. Status/OpenAPI identify score semantics. Micrometer stage timers and the real HTTP test provide a bounded cold/warm latency report; the tiny fixture is not a production SLO claim. See [Day 28 runbook](DAY28_PUBLIC_L2.md); `-IncludeL2Serving` exercises packaged-process rollback and re-enable with shared Redis.
- Verified 2026-10-04: all 12 Maven modules, 154 Java tests and 46 Python tests passed; fresh packaged L2 → L1-only rollback → L2 smoke with shared Redis, release rollback, model parity, dependency and Compose checks passed. The four-member, concurrency-one diagnostic measured cold/warm HTTP p99 of 171.34/35.96 ms; the 150 ms end-to-end target is not established, and no production-quality promotion is claimed.

---

## Week 5 — Re-Ranker, Exposure Diversity, Bayesian Blend (M5)

**Day 29 — Linear blend + weight config ✅**
- Implement the weighted blend of `P(sent)`/`P(accepted)` into a single score, with weights externalized as config (`application.yml`).
- Add a deterministic diversity pass with a configurable employer/cluster exposure cap; document that this is exposure diversity, not a protected-attribute fairness guarantee.
- Validate finite nonnegative weights normalized to sum to one. Cache the scored pool before diversity, apply `max(1, floor(capFraction*requestedLimit))` per known group, and return fewer results if constraints are infeasible.
- Define missing-group behavior, stable ties, and score-vs-display ordering. Version the pipeline and extend the Day 21 smoke contract with exposure and tie-order checks; greedy score-ordered selection preserves descending scores. Test limits 1, 2, 20, and 100 and cached requests with different limits.
- Describe the cap as a count based on requested size, not a guaranteed fraction of an underfilled response. Report returned size and realized group exposure; test heavily concentrated and entirely blank-group pools.
- Implemented opt-in `RERANK` mode with normalized finite weights, decimal-safe requested-size caps, stable score/ID ordering, case-normalized known employers and exempt blank groups. All L1 survivors are cached before per-request selection, with policy/artifact/release/source-revision isolation. L1/L2 rollback semantics remain intact. Unit and real ONNX/HTTP tests cover all four limits, 242-survivor backfilling, profile/connection invalidation, empty pools and failure safety. See [Day 29 runbook](DAY29_BLEND_DIVERSITY.md); `-IncludeReranker` extends the fresh-training packaged smoke with exposure checks and rollback/re-enable.

**Day 30 — Bayesian weight tuning (offline) ✅**
- Search weights against validation data using a declared offline ranking objective; reserve test data for the final evaluation and reject invalid or diversity-breaking weight sets.
- Save the seed, search budget, cohort hashes, selected configuration, baseline comparison, and reproducible report. Without impression/propensity logs, describe the result as a synthetic ranking proxy, not causal engagement lift.
- Close M5 with a documented local run from seeding through the actual ranked/diversified HTTP endpoint, immutable release versions, and rollback instructions.
- Implemented a seeded fixed-RBF GP/expected-improvement search over normalized two-head weights, with a fixed employer cap and macro accepted-hits/requested-slot objective. Validation alone selects weights; fixed test/baseline comparisons follow selection. Hash-bound employer context, input mutation rejection, immutable report/config publication and Java/Python policy parity are tested. The exported-pool proxy does not claim exact historical L0 replay or causal lift. `-IncludeBlendTuning` extends the fresh-training smoke to load exported properties into the packaged RERANK API and exercise rollback/re-enable. See [Day 30 runbook](DAY30_BLEND_TUNING.md). M5 is complete; production quality promotion and M6–M10 remain separate work.

---

## Week 6 — Kafka Event Backbone (M6, post-Day-30)

Kafka is intentionally scheduled after the recommendation pipeline so it adds
freshness and operational realism without blocking the first complete product.
PostgreSQL remains authoritative and Kafka stays off the synchronous read path.

**Day 31 — Kafka infrastructure + versioned contracts ✅**

- Added the standalone `pymk-event-stream` reactor module with Boot-managed Spring Kafka/Testcontainers dependencies, immutable typed envelopes, bounded strict schema/semantic validation and stable producer-content fingerprints. Unknown versions/types/fields, malformed values, duplicate JSON fields, wrong keys and same-ID conflicting producer content are covered by tests.
- Added an optional `kafka` Compose profile using Apache Kafka 3.9.1 KRaft, loopback host/internal listeners, a persistent named volume, broker health check and idempotent topic initialization that rejects configuration drift. Both v1 topics have explicit three-partition, seven-day, append-time retention settings; local single-node durability is not advertised as production HA.
- Published frozen v1 JSON Schemas/golden fixtures for `pymk.member-events.v1` and `pymk.connection-events.v1`, directional member keys and canonical connection-pair/aggregate-sequence contracts. Documented independent consumer groups, replay/deduplication horizons and future DLT recovery rules.
- Added real-broker Spring Kafka publish/consume, per-key ordering, independent-group replay/committed-offset tests and a separate Compose smoke covering host listeners and persistence through broker recreation. The API remains Kafka-independent; durable receipts/outbox and consumer effects are still Days 32–35. See [Day 31 runbook](DAY31_KAFKA_CONTRACTS.md).
- Verified 2026-10-09: all 13 reactor projects succeeded with 212 Java tests and no failures/errors/skips; 57 Python tests and dependency checks passed. Kafka Compose recreation/persistence and the fresh training-to-HTTP/tuned re-ranking/rollback smoke passed, the latter with development Kafka stopped. CI now runs the Compose smoke after the Java build.

**Day 32 — Transactional outbox + publisher ✅**
- Added forward-only V9 outbox/aggregate-sequence migration. Member events and connection mutations commit with their outbox record in one PostgreSQL transaction.
- Implemented an opt-in scheduled relay with acknowledged, idempotent Kafka producer, bounded head-of-key claims, recoverable leases, stale-owner checks and retained retries. No broker calls occur on the request path.
- Added strict bounded `POST /api/v1/events` ingestion with durable `202` receipts, valid member references, five-minute future skew, late-event support, identical receipt retries and `409` content conflicts.
- Connection API emits canonical, sequenced `CONNECTION_CREATED` only for actual graph changes; no fabricated acceptance label. Existing synchronous cache invalidation remains.
- Serialized admission enforces a configurable pending-row limit; full backlog returns retryable 503 without discarding accepted data. Development reset refuses to erase data with pending events.
- Added PostgreSQL/Kafka integration coverage for concurrent identity/admission, rollback, per-key blocking, lease recovery, stale owners, broker failure/recovery and crash-after-ack-before-marker replay. See [Day 32 runbook](DAY32_EVENT_OUTBOX.md) for operating limits and at-least-once semantics.
- Verified 2026-10-09: all 13 reactor projects and 229 Java tests passed with zero failures/errors/skips; all 57 Python tests and dependency checks passed.
- Verified 2026-10-10: the packaged outbox smoke passed real HTTP acceptance through Kafka outage, scheduled recovery, wire identity and original receipt after API restart, alongside fresh training/serving/cache/rollback checks. The separate Kafka Compose persistence smoke also passed.

**Day 33 — Idempotent consumers + failure handling ✅**
- Added V10 receipts, normalized journal projection and persisted replay floors. Both opt-in consumer groups commit receipt and journal together before synchronous record-offset acknowledgement. Live feature/cache effects remain Day 34.
- Added three bounded exponential retries and versioned 30-day DLTs with original bytes, metadata and safe failure categories. DLT capacity includes headroom for recovery headers on near-limit poison records.
- Implemented independent group deduplication, content-conflict quarantine, transaction rollback and safe post-commit redelivery. Blocking retry does not let later records bypass a poison record until processing or DLT recovery succeeds.
- Require a broker-acknowledged DLT send before advancing the source offset; DLT failures retain the source record and restart bounded backoff. Replay after recovery may be out of order and retains the original identity.
- Retain deduplication history indefinitely by default. A persisted ingestion-time floor rejects unknown replay outside the supported history; known identical receipts remain no-ops. Added a finite single-offset replay CLI that never rewrites IDs or resets groups.
- Added real PostgreSQL/Kafka tests for both source types, API/outbox-to-consumer flow, duplicate/conflicting/concurrent identities, crash windows, independent groups, DLT outage/recovery, near-limit poison, executable replay and retention boundaries. See [Day 33 runbook](DAY33_EVENT_CONSUMERS.md).
- Verified 2026-10-11: all 13 reactor projects and 248 Java tests passed with zero failures/errors/skips; 57 Python tests and dependency validation passed. Kafka Compose persistence and the packaged HTTP/outbox/consumer smoke passed, including broker recovery, API restart, caching and two serving rollbacks. Evidence paths are recorded in the runbook.

**Day 34 — Streaming feature freshness + cache invalidation ✅**
- Implemented V11 bootstrap from Day 33 journals into versioned live event membership and sequence-aware connection state. Receipts remain intact; journal-triggered projections commit in the receipt transaction.
- Added directional profile-view/invite counters with configurable event-time windows, late-arrival admission, read-time expiry and explicit ingestion cutoffs. Exposed a live HTTP read endpoint without modifying historical model features or labels.
- Added targeted durable cache epochs for connection endpoints and two-hop neighborhoods, with sequence checks and request-pinned cache namespaces. Existing synchronous invalidation and source revision checks remain safeguards; Redis availability is not required to commit an invalidation.
- Added finite `liveFeatureReconciliationJob`: explicit event/knowledge boundaries, retained journal/outbox repair (including unpublished events), atomic generation replacement, concurrent-delivery fencing and sequence-safe invalidation recovery. No legacy in-place feature writer or scheduler dependency; Airflow scheduling remains Day 43.
- Added tests for late/out-of-order events, duplicate/replayed events, window expiry, knowledge boundaries, rollback, migration backfill, targeted cache behavior and in-flight writes, concurrent generation replacement, and the executable Batch job. Extended real-Kafka and packaged outage/restart checks.
- Verified 2026-10-12: all 13 reactor projects and 259 Java tests passed with zero failures/errors/skips; 57 Python tests and dependency checks passed. Packaged training/serving/cache/rollback checks passed, including reconciliation with Kafka stopped and Redis paused, live HTTP counters, replay and API restart without duplicate effects.
- See [Day 34 operations](DAY34_LIVE_FEATURES.md) for live-versus-historical boundaries, recovery commands, retained-source requirements and finite-job scale limits.

**Day 35 — Kafka resilience, observability, and runbook**
- Add end-to-end Testcontainers tests covering API → database/outbox → Kafka → consumer/projection, duplicates, retries, dead-letter routing, ordering per key, and broker/consumer restarts.
- Publish metrics for outbox and consumer lag, throughput, retry/dead-letter counts, processing latency, and projection freshness.
- Document local operation, topic creation/retention, replay, dead-letter recovery, schema evolution, and failure-mode behavior.

---

## Week 7 — MLflow Experiment Tracking & Model Lifecycle (M7, planned)

Build on Kafka's durable source/ingestion boundaries and the existing offline
CLIs. MLflow records experiments and model versions; it does not replace ONNX
Runtime Java or the Day 23 serving-release authority. See design section 7.3.

**Day 36 — MLflow infrastructure + storage ownership**

- Add an optional Compose profile with a pinned MLflow server/client compatibility set, health check, dedicated PostgreSQL database/role, and persistent artifact storage. Keep the base Day 30 startup usable without MLflow.
- Use a durable local artifact volume for the laptop profile; document the shared S3-compatible storage configuration and test it before claiming remote-worker support. Separate tracking metadata from artifact bytes.
- Keep PYMK Flyway migrations separate from MLflow-owned metadata upgrades. Restrict the UI/network and credentials; test restart persistence and an upgrade/backup plan before accepting the infrastructure.

**Day 37 — Reproducible tracking for existing ML commands**

- Add explicit tracking integration to profile-transform fitting, L1/L2 training, frozen evaluation and blend tuning without changing their numerical results or default CLI behavior.
- Log code/build identity, dependency versions, seed, parameters, source/knowledge cutoffs, dataset/cohort/split hashes, release IDs, metrics and checksum-bound output references. Record failed runs and `not_met` gates; unavailable metrics remain unavailable, not zero.
- Use stable logical experiment identities with linked retry attempts; keep raw member data and secrets out of parameters/tags. Test offline/untracked mode, resumed publication and tracking failure without silently reporting a fully published run.

**Day 38 — Immutable artifacts + model registry contracts**

- Register exact L1/L2 ONNX versions only after upload/read-back checksum and metadata/tensor/parity validation. Attach transform, dataset, evaluation and blend artifacts as lineage, not as independently interchangeable serving models.
- Extend the release manifest with run IDs, registry name/version, artifact URI and digests while retaining all existing feature/embedding/model compatibility fields. Version the extension and keep existing Day 30 manifests usable.
- Use aliases/tags for human discovery, not deprecated registry stages or live serving resolution. Test interrupted uploads, mismatched files, reused output versions and alias movement after staging; none may change the pinned candidate or active release.

**Day 39 — Guarded promotion + model synchronization**

- Implement the model-sync adapter that downloads an exact registered version to a staged immutable location and invokes existing validation/release preparation. No request handler calls MLflow.
- Bind L1, optional L2, transforms/features, candidate budgets and blend configuration into one approved manifest. Require parity, temporal/cohort validity, coverage, declared quality and cold/warm latency gates plus explicit operator approval; successful training is not promotion.
- Preserve PostgreSQL expected-epoch activation and cache isolation. L2/RERANK configuration is process-level today: verify a controlled rollout and matching rollback rather than claiming an alias flip updates running JVMs. Reject stale approvals or changed digests and prove L1 fallback.

**Day 40 — MLflow failure recovery + lifecycle runbook**

- Add integration coverage for tracking/artifact outages, duplicate retry publication, corrupted downloads, concurrent promotion attempts and missing rollback artifacts. Current valid serving must remain available; registry-dependent new promotion fails closed.
- Demonstrate backup/restore of registry metadata plus artifact bytes, then reproduce a registered candidate and roll back a served release. Retention must preserve active, rollback and required evaluation lineage.
- Document tracking, comparison, registration, approval, synchronization and recovery commands. Provide a deterministic local demo from an existing frozen dataset to a tracked candidate; a failed quality gate is a valid demo outcome, not permission to activate it.

---

## Week 8 — Airflow Orchestration & Batch Operations (M8, planned)

Airflow schedules bounded workflows after Kafka and MLflow are established.
Spring Batch still owns chunk transactions/checkpoints and the Python CLIs still
own training/evaluation. No DAG consumes an unbounded Kafka stream or participates
in a recommendation request. See design section 7.4.

**Day 41 — Airflow infrastructure + workflow contracts**

- Add an optional Linux-container Compose profile with pinned Airflow/provider versions, a bounded local executor, dedicated metadata database/role, persistent logs and health checks. Document Windows Docker Desktop/WSL2 setup and measured resource needs; keep Airflow dependencies isolated from the trainer environment.
- Define thin DAGs in `orchestration/airflow/`, with UTC data intervals, explicit parameters, `catchup=False`, timeouts, retry budgets and resource pools. Choose and document the worker launch mechanism; ship the tested Java/Python runtime and artifact mounts, not an assumed host installation.
- Keep Airflow metadata migrations under Airflow's tooling, not app Flyway. Test DAG imports/structure without live services or expensive work during parsing; keep service credentials outside DAG source.

**Day 42 — Scheduled feature and ML workflows**

- Orchestrate source validation → immutable embedding/feature staging → mature export → tracked L1/L2 training → frozen evaluation → validation-only blend tuning → candidate preparation. Use separate cadence/dependency paths; today's fresh snapshot must not be trained on before its label window matures.
- Invoke existing Spring Batch jobs and Python CLIs through a tested adapter that waits for real completion, checks exit/execution status and validates outputs. Pass artifact references/checksums through XCom, never datasets or ONNX bytes.
- Extend Day 23 managed-generation refreshes; never schedule the legacy in-place Day 17 writer against active managed features. Prove compatible model/feature coverage, failed refresh isolation and retained prior generations.

**Day 43 — Kafka reconciliation + reproducible backfills**

- Schedule finite reconciliation of streaming projections against PostgreSQL at declared event/ingestion watermarks, leaving continuous consumers in `pymk-event-stream`. Include window expiry, late arrivals, deduplication retention and DLT/replay runbook integration.
- Require explicit historical source revisions, feature/knowledge cutoffs, label maturity and destination versions for backfills. Missing historical inputs fail validation; never substitute current profiles or overwrite published datasets. Backfills cannot activate a serving release automatically.
- Bound normal and backfill concurrency separately, use shared resource pools and database fencing with manual invocations, and test replay convergence without contaminating historical exports. Add graph-index refresh only for the configured store, not an implicit Neo4j dependency.

**Day 44 — Retry/restart identities + controlled release workflow**

- Persist the mapping from logical work key (job, interval, source/config hashes and output version) to Spring Batch instance/executions, MLflow run/attempts and output manifest. Airflow task retries must resume or validate/reuse completed work, not create new timestamp-based jobs or overwrite artifacts.
- Test a worker crash after job completion but before task acknowledgement, scheduler restart, timeout/cancellation and orphan-job reconciliation. Concurrent manual/scheduled work must share database locking/leases; Airflow pools alone are insufficient.
- Connect approved candidates to M7 synchronization and expected-epoch activation. Verify approval is bound to exact manifest/epoch, block stale or incomplete candidates, and retain an operator-controlled deployment/rollback boundary for process-level L2/blend changes.

**Day 45 — End-to-end orchestration resilience + runbook**

- Run a deterministic full DAG on already mature fixtures and a separate fresh-data maturity rejection case; verify actual packaged HTTP recommendations after an approved compatible release and after rollback.
- Test scheduler/worker/Kafka/MLflow/artifact-store outages, task retries, competing runs, restored metadata and retention cleanup. Prove no duplicate effects, no partially published release, and no serving dependency on Airflow availability.
- Document schedules, pause/resume, explicit backfills, manual CLI recovery, alert ownership and recovery from each checkpoint. Schedule model-sync and offline-evaluation reporting; retain active/rollback artifacts and auditable lineage during cleanup. Publish failure/freshness/run-duration telemetry before M9's unified dashboards.

### Remaining post-Day-30 extensions

- **M9 — Observability and quality:** bounded source deadlines/concurrency, per-stage timers/counters, separate liveness/readiness, and Prometheus/Grafana dashboards for cache errors, feature age/coverage, model versions, outbox/consumer lag, DLT recovery, MLflow publication/registration failures and Airflow task/run health. Correlate DAG/run/release IDs in logs and reports, not unbounded metric labels; never label metrics by member ID. Test alerts and dependency outages. Exercise in-flight cache refill after mutation and fence stale writes before promising immediate coherence. Record query plans and exact-versus-ANN recall/latency at fixed resource and candidate budgets.
- **M10 — Portfolio release:** one-command startup with documented lightweight and full Kafka/MLflow/Airflow profiles, deterministic seed/model/DAG workflow, health plus actual recommendation smoke tests, immutable release manifests and tested rollback, latency/load evidence at a stated scale, and a reproducible baseline-vs-pipeline report. Exercise upgrade from the previous checkpoint, separate app/vendor metadata migrations, database backup/restore and artifact recovery after restart. Public deployment requires authentication/authorization, request limits, injected secrets, restricted admin/UI endpoints, and a retention/deletion policy first; Kubernetes remains optional.

---

## How to use this roadmap
- Each day is scoped to end with something runnable and verified.
- If a day's task balloons, use the Day 7/14/21/28 buffer slots before moving milestone boundaries; post-Day-30 milestones should move as a unit rather than weakening their reliability acceptance criteria.
- Treat Weeks 1–2 as non-negotiable (they're your portfolio's minimum viable demo); Weeks 3–5 are what make it *interesting* to an interviewer.
