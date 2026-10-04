# PYMK Backend — 30-Day Build Roadmap

Companion to `PYMK_DESIGN.md`. The first 30 focused workdays deliver M1–M5;
M6–M8 are explicit post-Day-30 extensions. The schedule assumes roughly 1–2
focused hours per day, but completion is evidence-based rather than calendar-based.

**Current checkpoint:** Day 8 complete; Day 9 is next. Target-architecture
modules remain intentionally empty until their scheduled day.

**Definition of done for every day:** the reactor compiles, relevant automated
tests pass, migrations and entity mappings agree, and user-facing behavior or
architectural decisions are reflected in the README/design docs. A manual check
alone does not mark a day complete when the behavior can be automated.

---

## Week 1 — Foundations & Domain (M1)

**Day 1 — Repo & environment setup ✅**
- Create the multi-module Maven project (`pymk-common`, `pymk-domain`, `pymk-api`, etc. as empty modules).
- Docker Compose with Postgres + Redis (pgvector extension enabled on the Postgres image).
- Push initial skeleton to GitHub with a placeholder README.

**Day 2 — Core JPA entities ✅**
- Implement `Member`, `Connection`, `MemberEvent` entities in `pymk-domain`.
- Write Flyway/Liquibase migration scripts for the schema (indexes on `connections`).

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

**Day 9 — Graph-walk + embedding candidate sources**
- Implement `GraphWalkCandidateSource` using a recursive CTE (2-hop, 3-hop neighbors) over `connections`.
- Implement `EmbeddingRetrievalCandidateSource` using the Day 5 cosine ANN repository.
- Test both against deterministic graph/vector fixtures, including eligibility filtering.

**Day 10 — Union + parallel fan-out**
- Combine all three `CandidateSource`s with bounded virtual-thread fan-out in an `L0CandidateGenerator` service.
- De-duplicate by member ID while preserving source provenance, then enforce per-source budgets and a global cap (e.g. 3,000).

**Day 11 — Naive orchestrator + endpoint**
- `pymk-orchestrator`: wire L0 output directly to the API (no ranking yet, just candidates sorted by mutual-connection count as a placeholder).
- Expose `GET /api/v1/pymk/{memberId}`.

**Day 12 — Redis caching**
- Cache L0 output and the final response per member with a TTL and schema/model version in each cache key.
- Invalidate affected members after graph mutations; test hit, miss, expiry, and invalidation paths.

**Day 13 — Manual QA pass**
- Pull a handful of real member IDs from your synthetic set, sanity-check the recommendations "make sense" (e.g. mostly same-company/mutual-friend heavy candidates).
- Add an automated smoke check for invariants: no self, no existing connections, no duplicates, requested limit respected.

**Day 14 — Buffer + README update**
- Document the "naive v1 pipeline is live" milestone with an example request/response in the README.

---

## Week 3 — Feature Store & Light Ranker (M3)

**Day 15 — `pymk_features` table + `PairId`**
- Add the `PymkPairFeature` entity, composite key, migration, and feature-as-of timestamp.

**Day 16 — Feature computation logic**
- Write the feature calculation functions: mutual connection count, same-company/school/geo flags, profile-view counts.
- Unit test each feature calculator independently.

**Day 17 — `FeatureComputationJob` (Spring Batch)**
- First real Spring Batch job: chunk-process active member pairs, compute features strictly as of the configured snapshot time, and write into `pymk_features`.
- Run it against your synthetic dataset; confirm row counts and spot-check values.

**Day 18 — Label generation for training**
- Define observation and label windows; a positive is an `INVITE_ACCEPTED` after the feature snapshot and within the label window.
- Export a temporally ordered labeled dataset (features + label) to CSV/Parquet without post-outcome leakage.

**Day 19 — Train logistic regression offline**
- Small Python (or Tribuo, if going pure-Java) script: train a logistic regression / XGBoost model on the exported dataset.
- Use time-based train/validation/test splits rather than random row splits.
- Export as PMML or ONNX.

**Day 20 — `pymk-light-ranker` serving**
- Load the exported model via JPMML-Evaluator or ONNX Runtime Java.
- Implement `L1LightRanker.rank(memberId, candidates)` → top ~500.

**Day 21 — Wire L1 into orchestrator + buffer**
- Orchestrator now calls L0 → L1 instead of L0 → naive sort.
- Compare output before/after L1 on a few sample members.

---

## Week 4 — Heavy Ranker (M4)

**Day 22 — Richer pair features**
- Extend `pymk_features` with embedding cosine similarity (now using real, not random, embeddings — compute simple embeddings from graph structure or profile text, e.g. node2vec or a bag-of-words + SVD).

**Day 23 — `EmbeddingRefreshJob`**
- Spring Batch job to (re)compute `member_embeddings` for all members using the chosen method.

**Day 24 — Training data v2 + multi-target labels**
- Re-export training data including new features, with two label columns: `invite_sent`, `invite_accepted`.

**Day 25 — Train the heavy model offline**
- Small feed-forward NN (Python/Keras or PyTorch) predicting both targets; export to ONNX.

**Day 26 — `pymk-heavy-ranker` serving**
- Load the ONNX model via ONNX Runtime Java; batch-score all L1 survivors for a member in one inference call.

**Day 27 — Offline eval: AUC / Precision@k / ECE**
- Script to evaluate the heavy ranker against the held-out time split; log AUC, Precision@k, ECE, and comparison with the mutual-connections baseline.

**Day 28 — Wire L2 into orchestrator + buffer**
- Orchestrator: L0 → L1 → L2. Confirm end-to-end latency is still reasonable (log timings per stage).

---

## Week 5 — Re-Ranker, Exposure Diversity, Bayesian Blend (M5)

**Day 29 — Linear blend + weight config**
- Implement the weighted blend of `P(sent)`/`P(accepted)` into a single score, with weights externalized as config (`application.yml`).
- Add a deterministic diversity pass with a configurable employer/cluster exposure cap; document that this is exposure diversity, not a protected-attribute fairness guarantee.

**Day 30 — Bayesian weight tuning (offline)**
- Small offline script (`scikit-optimize` or similar) that searches blend weights against the held-out logged dataset to maximize a simulated engagement metric; feed the tuned weights back into `pymk-reranker` config.

### Post-Day-30 extensions

- **M6 — Batch operations:** scheduling, restartability, idempotency, and the remaining `GraphIndexRebuildJob`, `ModelSyncJob`, and `OfflineEvalJob` jobs.
- **M7 — Observability and quality:** per-stage timers/counters, Prometheus/Grafana dashboards, cache metrics, model/version tags, and recurring offline-quality reports.
- **M8 — Portfolio release:** one-command startup, production-style configuration/secrets guidance, load-test evidence for the latency target, architecture/runbook documentation, and a reproducible baseline-vs-pipeline evaluation report.

---

## How to use this roadmap
- Each day is scoped to end with something runnable and verified.
- If a day's task balloons, use the Day 7/14/21/28 buffer slots before moving milestone boundaries.
- Treat Weeks 1–2 as non-negotiable (they're your portfolio's minimum viable demo); Weeks 3–5 are what make it *interesting* to an interviewer.
