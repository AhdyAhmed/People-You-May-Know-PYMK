# pymk-datagen

Synthetic data generator for local development (Day 4, `docs/PYMK_ROADMAP.md`).
Generates members, a connection graph, and engagement events, then bulk-loads
them into Postgres.

Deliberately **not** part of `pymk-batch`: this is a one-off seeding CLI, not
one of the recurring Spring Batch jobs (`FeatureComputationJob`,
`EmbeddingRefreshJob`, etc.) described in `docs/PYMK_DESIGN.md` section 7.

## What it generates

- **Members** — via [Datafaker](https://www.datafaker.net/), with
  `company`/`school`/`geoRegion` drawn from a small fixed pool (default 250
  each) sampled with Zipf-distributed popularity, so a handful of
  companies/schools/regions dominate — the way real membership actually
  clusters, and what makes `HeuristicCandidateSource` (Day 8) meaningful.
- **Connections** — a [Barabási–Albert preferential-attachment
  graph](https://en.wikipedia.org/wiki/Barab%C3%A1si%E2%80%93Albert_model):
  new members connect to existing members with probability proportional to
  their current degree, producing a few well-connected hubs and a long tail
  of lightly-connected members, rather than a uniform random graph.
- **Events** — an `INVITE_SENT` + `INVITE_ACCEPTED` pair for every accepted
  connection (using the connection's own timestamp, so the two agree), plus
  additional `PROFILE_VIEW` / `SEARCH_APPEARANCE` / ignored-invite volume
  sampled over 2-hop "friend of a friend" pairs.
- **Embeddings** — a random, L2-normalized 128-dim placeholder vector per
  member (Day 5). These carry no semantic meaning by construction — they
  exist so the ANN query path (`member_embeddings`, pgvector's `<=>`
  operator) has real rows to run against end to end. Real embeddings
  (node2vec over the connection graph, or profile-text embeddings) replace
  them in Week 4 via `EmbeddingRefreshJob`. `pymk-domain`'s
  `MemberEmbeddingAnnQueryTest` is what actually proves the ANN query
  behaves sensibly, using embeddings constructed to be meaningful — these
  placeholders are volume, not a correctness demo.

All random choices are seeded (`pymk.datagen.seed`, default `42`): the same
seed and member count reproduce the same graph and profile distribution.
Timestamps remain relative to the time the generator is run.

## Running it

Requires the Postgres container from `infra/docker-compose.yml` to be up
(Flyway migrates the schema automatically on startup if it hasn't run yet).

```bash
# Build sibling modules first (once, and again after changing them)
mvn -B install -DskipTests

# Default: 100,000 members, ~12 avg degree, plus 2 extra event attempts/member
mvn -pl pymk-datagen spring-boot:run

# Override any pymk.datagen.* property from the command line
mvn -pl pymk-datagen spring-boot:run \
  -Dspring-boot.run.arguments="--pymk.datagen.members=20000 --pymk.datagen.seed=7"
```

Or build the jar once and reuse it:

```bash
mvn -pl pymk-datagen -am package -DskipTests
java -jar pymk-datagen/target/pymk-datagen.jar --pymk.datagen.members=20000
```

By default the generator **truncates** `members`, `connections`,
`member_events`, and `member_embeddings` before loading
(`pymk.datagen.truncate-existing`, default `true`) — set it to `false` to
preserve the existing tables. Because generated member IDs start at `1`,
preserving a non-empty generated dataset will normally cause primary-key
conflicts; this switch is a safety control, not an append mode.

## Output

Logs progress per stage (generation, load, per-table batch progress) and
finishes with a summary: row counts, degree distribution (min/p50/p95/p99/max),
top 5 companies by headcount, and event-type counts — the "sanity-check
counts and distribution" step the roadmap calls for. Embedding row count is
included too, though "distribution" doesn't mean much for random noise —
that's what `MemberEmbeddingAnnQueryTest` (`pymk-domain`) is for.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `pymk.datagen.members` | `100000` | Total members to generate |
| `pymk.datagen.avg-degree` | `12` | Target average node degree (edges/new member ≈ half this) |
| `pymk.datagen.events-per-member` | `2.0` | Extra implicit/ignored-invite event attempts per member, in addition to accepted-connection lifecycle events |
| `pymk.datagen.seed` | `42` | Master random seed (reproducible runs) |
| `pymk.datagen.truncate-existing` | `true` | Wipe members, connections, events, and embeddings before loading |
| `pymk.datagen.organization-pool-size` | `250` | Distinct companies/schools in the pool (regions use a fifth of this) |
