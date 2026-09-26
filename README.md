# People You May Know (PYMK)

A portfolio-scale reimplementation of LinkedIn-style **"People You May Know"**
recommendations, built on the Java/Spring ecosystem, following the
multi-stage funnel architecture described in LinkedIn Engineering's public
PYMK writeup: **candidate generation (L0) → light ranking (L1) → heavy
ranking (L2) → re-ranking (fairness/diversity)**.

> 🚧 **Status: Day 1 of 30** — repo, multi-module build, and local
> infrastructure are up. See [`docs/PYMK_ROADMAP.md`](docs/PYMK_ROADMAP.md)
> for the day-by-day build log and [`docs/PYMK_DESIGN.md`](docs/PYMK_DESIGN.md)
> for the full system design.

---

## Why this project

Most CRUD portfolio projects stop at "REST API + database." This one
demonstrates both sides of a modern recommendation system:

- **Traditional backend engineering:** JPA, REST, Redis caching, Spring
  Batch ETL jobs, multi-module architecture designed to split into
  microservices without a rewrite.
- **Applied ML engineering:** feature engineering, offline model training
  (logistic regression → GBDT → small NN), ONNX/PMML serving from Java,
  offline evaluation (Recall@k, AUC, Precision@k, calibration), and a
  simulated offline A/B test against a naive baseline.

## Architecture

```mermaid
flowchart LR
    subgraph Client
        UI[Web/Mobile Client]
    end

    UI -->|GET /pymk/{memberId}| GW[pymk-api]
    GW --> ORCH[Orchestrator]

    subgraph Online["Online Serving Pipeline"]
        L0[L0: Candidate Generation]
        L1[L1: Light Ranker]
        L2[L2: Heavy Ranker]
        RR[Re-Ranker: Fairness/Diversity]
    end

    ORCH --> L0 --> L1 --> L2 --> RR --> ORCH
    ORCH --> CACHE[(Redis Cache)]
    L0 --> GRAPHDB[(Postgres: graph adjacency)]
    L0 --> VEC[(pgvector: embeddings)]
    L1 --> FEATSTORE[(Feature Store)]
    L2 --> FEATSTORE
```

Full architecture, data model, API design, and offline evaluation strategy:
[`docs/PYMK_DESIGN.md`](docs/PYMK_DESIGN.md).

## Tech stack

| Layer | Choice |
|---|---|
| Language / Framework | Java 21, Spring Boot 3.5.x |
| Persistence | Spring Data JPA + PostgreSQL |
| Vector similarity | pgvector (embedding-based retrieval) |
| Caching | Spring Data Redis |
| Batch / ETL | Spring Batch |
| Graph traversal | Postgres recursive CTE |
| ML training | Python (scikit-learn/XGBoost/PyTorch) → ONNX/PMML |
| ML serving | ONNX Runtime Java / JPMML-Evaluator |
| Build | Maven multi-module |
| Testing | JUnit 5, Testcontainers, Mockito |
| Local infra | Docker Compose (Postgres + pgvector, Redis) |

## Repository layout

```
pymk/
├── pymk-common/            # shared DTOs, enums, utils
├── pymk-domain/            # JPA entities, repositories
├── pymk-feature-store/     # feature computation + read/write API
├── pymk-candidate-gen/     # L0: graph, EBR, heuristic candidate sources
├── pymk-light-ranker/      # L1: logistic regression / GBDT scoring
├── pymk-heavy-ranker/      # L2: DNN model serving via ONNX Runtime
├── pymk-reranker/          # fairness, diversity, Bayesian-tuned blending
├── pymk-orchestrator/      # wires stages together
├── pymk-api/               # public REST controllers (the runnable app)
├── pymk-batch/             # Spring Batch jobs
├── pymk-ml-training/       # Python project (offline), exports model artifacts
├── infra/                  # docker-compose.yml, init SQL
└── docs/                   # design doc + roadmap
```

Every module except `pymk-api` is intentionally empty right now — Day 1's
job was to get the wiring, dependency graph, and package layout right before
any real logic lands. See the roadmap for what fills in each module and when.

## Quickstart

**Prerequisites:** JDK 21, Maven 3.9+, Docker.

```bash
# 1. Clone
git clone https://github.com/AhdyAhmed/People-You-May-Know-PYMK.git
cd People-You-May-Know-PYMK

# 2. Start Postgres (with pgvector) + Redis
docker compose -f infra/docker-compose.yml up -d

# 3. Build all modules
mvn -B verify

# 4. Run the API
mvn -pl pymk-api spring-boot:run
```

Then:
```bash
curl http://localhost:8080/
# {"service":"pymk-api","status":"up","milestone":"M1 - Day 1: repo & environment setup"}
```

To stop the local infra: `docker compose -f infra/docker-compose.yml down`
(add `-v` to also wipe the Postgres/Redis volumes).

## Build log

This project is being built in public, one focused session at a time. Each
day's scope and status lives in [`docs/PYMK_ROADMAP.md`](docs/PYMK_ROADMAP.md).

- [x] **Day 1 — Repo & environment setup**: multi-module Maven skeleton (10
      modules), Docker Compose (Postgres + pgvector, Redis), CI build.
- [ ] Day 2 — Core JPA entities (`Member`, `Connection`, `MemberEvent`)
- [ ] Day 3 — Repositories & basic queries
- [ ] ... see the roadmap for the full 30-day plan through M8.

## License

[MIT](LICENSE)

## Attribution

Architecture pattern (multi-stage funnel: L0 candidate generation → L1 light
ranking → L2 heavy ranking → re-ranking) adapted from LinkedIn Engineering's
public writeup on the "People You May Know" recommendation system. This is an
original implementation at portfolio scale, not a reproduction of LinkedIn's
internal system or text.
