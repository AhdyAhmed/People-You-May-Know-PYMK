# PYMK Backend — 30-Day Build Roadmap

Companion to `PYMK_DESIGN.md`. Assumes ~1–2 focused hours/day. Organized into 6 weeks that map directly onto the M1–M8 milestones. Adjust pace as needed — the important thing is finishing each day with something that runs, not something perfect.

---

## Week 1 — Foundations & Domain (M1)

**Day 1 — Repo & environment setup**
- Create multi-module Maven/Gradle project (`pymk-common`, `pymk-domain`, `pymk-api`, etc. as empty modules).
- Docker Compose with Postgres + Redis (pgvector extension enabled on the Postgres image).
- Push initial skeleton to GitHub with a placeholder README.

**Day 2 — Core JPA entities**
- Implement `Member`, `Connection`, `MemberEvent` entities in `pymk-domain`.
- Write Flyway/Liquibase migration scripts for the schema (indexes on `connections`).

**Day 3 — Repositories & basic queries**
- Spring Data JPA repositories for the three entities.
- Write a repository test (Testcontainers Postgres) confirming symmetric edge inserts work and adjacency lookups are fast.

**Day 4 — Synthetic data generator**
- Small CLI/batch script to generate ~50K–100K fake members (Faker library), a realistic connection graph (e.g. preferential attachment or small-world graph), and simulated events.
- Load it into Postgres; sanity-check counts and distribution.

**Day 5 — pgvector + embeddings table**
- Add `member_embeddings` entity/table.
- Generate placeholder embeddings for synthetic members (random vectors is fine for now — real embeddings come in Week 4).
- Confirm an ANN query (`<->` operator) runs and returns sensible neighbors.

**Day 6 — `pymk-api` skeleton**
- Stand up `GET /api/v1/members/{id}` and `POST /api/v1/connections` as the first real endpoints.
- OpenAPI/Swagger UI wired up.

**Day 7 — Buffer / catch-up + write-up**
- Fix anything slipped from Days 1–6.
- Write the first README section: "Data model & how to seed the DB."

---

## Week 2 — Naive End-to-End PYMK (M2)

**Day 8 — `CandidateSource` interface**
- Define the `CandidateSource` interface in `pymk-candidate-gen`.
- Implement `HeuristicCandidateSource` (same company/school/geo) first — simplest to reason about and test.

**Day 9 — Graph-walk candidate source**
- Implement `GraphWalkCandidateSource` using a recursive CTE (2-hop, 3-hop neighbors) over `connections`.
- Unit test with a small hand-built graph fixture.

**Day 10 — Union + parallel fan-out**
- Combine multiple `CandidateSource`s via `CompletableFuture`/virtual threads in an `L0CandidateGenerator` service.
- De-duplicate and cap output size (e.g. 3,000).

**Day 11 — Naive orchestrator + endpoint**
- `pymk-orchestrator`: wire L0 output directly to the API (no ranking yet, just candidates sorted by mutual-connection count as a placeholder).
- Expose `GET /api/v1/pymk/{memberId}`.

**Day 12 — Redis caching**
- Cache L0 output and the final response per member with a TTL.
- Test cache hit/miss paths.

**Day 13 — Manual QA pass**
- Pull a handful of real member IDs from your synthetic set, sanity-check the recommendations "make sense" (e.g. mostly same-company/mutual-friend heavy candidates).

**Day 14 — Buffer + README update**
- Document the "naive v1 pipeline is live" milestone with an example request/response in the README.

---

## Week 3 — Feature Store & Light Ranker (M3)

**Day 15 — `pymk_features` table + `PairId`**
- Add the `PymkPairFeature` entity, composite key, and migration.

**Day 16 — Feature computation logic**
- Write the feature calculation functions: mutual connection count, same-company/school/geo flags, profile-view counts.
- Unit test each feature calculator independently.

**Day 17 — `FeatureComputationJob` (Spring Batch)**
- First real Spring Batch job: chunk-process active member pairs, write into `pymk_features`.
- Run it against your synthetic dataset; confirm row counts and spot-check values.

**Day 18 — Label generation for training**
- Decide what "positive" means for training data (e.g. `INVITE_ACCEPTED` events between two members).
- Export a labeled dataset (features + label) to CSV/Parquet for offline training.

**Day 19 — Train logistic regression offline**
- Small Python (or Tribuo, if going pure-Java) script: train a logistic regression / XGBoost model on the exported dataset.
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
- Script to evaluate the heavy ranker against a held-out split; log results.

**Day 28 — Wire L2 into orchestrator + buffer**
- Orchestrator: L0 → L1 → L2. Confirm end-to-end latency is still reasonable (log timings per stage).

---

## Week 5 — Re-Ranker, Fairness, Bayesian Blend (M5)

**Day 29 — Linear blend + weight config**
- Implement the weighted blend of `P(sent)`/`P(accepted)` into a single score, with weights externalized as config (`application.yml`).

**Day 30 — Bayesian weight tuning (offline)**
- Small offline script (`scikit-optimize` or similar) that searches blend weights against the held-out logged dataset to maximize a simulated engagement metric; feed the tuned weights back into `pymk-reranker` config.

> **Beyond Day 30:** the design doc's remaining milestones — fairness/diversity re-ranking (MMR-style diversification), the full batch-job suite (`GraphIndexRebuildJob`, `ModelSyncJob`, `OfflineEvalJob`), observability (Micrometer/Grafana), and the final portfolio polish (one-command Docker Compose bring-up, architecture diagram in the README, and a written "naive baseline vs. multi-stage pipeline" offline A/B comparison) — form a natural **Week 6–7** extension. Want me to lay those out day-by-day too, or leave Week 6+ looser since it's more exploratory (tuning, polish, writing) than sequential build steps?

---

## How to use this roadmap
- Each day is scoped to end with something runnable — commit at the end of every day, even mid-feature.
- If a day's task balloons, don't burn Week 6 buffer early — push the excess into the "Day 7/14/21/28" buffer slots first.
- Treat Weeks 1–2 as non-negotiable (they're your portfolio's minimum viable demo); Weeks 3–5 are what make it *interesting* to an interviewer.
