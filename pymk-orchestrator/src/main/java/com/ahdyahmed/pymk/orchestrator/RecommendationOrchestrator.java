package com.ahdyahmed.pymk.orchestrator;

import com.ahdyahmed.pymk.candidategen.CandidateGenerationException;
import com.ahdyahmed.pymk.candidategen.CandidateHit;
import com.ahdyahmed.pymk.candidategen.CandidateSourceType;
import com.ahdyahmed.pymk.candidategen.L0CandidateGenerator;
import com.ahdyahmed.pymk.candidategen.MergedCandidate;
import com.ahdyahmed.pymk.domain.repository.MemberRepository;
import com.ahdyahmed.pymk.lightranker.L1LightRanker;
import com.ahdyahmed.pymk.lightranker.LightRankedCandidate;
import com.ahdyahmed.pymk.lightranker.LightRankerException;
import com.ahdyahmed.pymk.heavyranker.HeavyRankerException;
import com.ahdyahmed.pymk.heavyranker.HeavyScoredCandidate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** Release-pinned L0/L1/L2 pipeline with opt-in blending/diversity and explicit rollback modes. */
@Service
public class RecommendationOrchestrator {
    @org.springframework.beans.factory.annotation.Autowired
    private ServingPipeline pipeline;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private io.micrometer.core.instrument.MeterRegistry metrics;

    private <T> T timed(String stage, java.util.function.Supplier<T> action) {
        long start = System.nanoTime();
        boolean success = false;
        try { T value = action.get(); success = true; return value; }
        finally {
            if (metrics != null) io.micrometer.core.instrument.Timer.builder("pymk.pipeline.stage")
                    .tag("stage", stage).tag("mode", pipeline == null ? "L1" : pipeline.mode())
                    .tag("outcome", success ? "success" : "failure")
                    .publishPercentiles(.5, .95, .99).register(metrics)
                    .record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.ahdyahmed.pymk.domain.service.ServingReleaseStore releases;
    @org.springframework.beans.factory.annotation.Autowired
    private com.ahdyahmed.pymk.candidategen.CandidateGenerationProperties candidateProperties;
    private org.springframework.transaction.support.TransactionTemplate releaseTransaction;
    private java.util.concurrent.Semaphore requestSlots;

    @org.springframework.beans.factory.annotation.Autowired
    void configureReleaseTransactions(org.springframework.transaction.PlatformTransactionManager manager,
            javax.sql.DataSource dataSource,
            com.ahdyahmed.pymk.candidategen.CandidateGenerationProperties budgets,
            @org.springframework.beans.factory.annotation.Value("${pymk.serving.max-concurrent-requests:2}") int concurrency) {
        if (concurrency < 1) throw new IllegalArgumentException("Serving concurrency must be positive");
        int workers = Math.min(budgets.getMaxConcurrency(), 3 * concurrency);
        if (dataSource instanceof com.zaxxer.hikari.HikariDataSource hikari
                && hikari.getMaximumPoolSize() < concurrency + workers + 1) {
            throw new IllegalStateException("JDBC pool must reserve connections for pinned requests, L0 workers and one writer");
        }
        releaseTransaction = new org.springframework.transaction.support.TransactionTemplate(manager);
        releaseTransaction.setReadOnly(true);
        requestSlots = new java.util.concurrent.Semaphore(concurrency, true);
    }

    public static final int MAX_RESULTS = 100;

    private final L0CandidateGenerator candidateGenerator;
    private final L1LightRanker lightRanker;
    private final MemberRepository members;
    private final RecommendationCache cache;

    public RecommendationOrchestrator(
            L0CandidateGenerator candidateGenerator,
            L1LightRanker lightRanker,
            MemberRepository members,
            RecommendationCache cache) {
        this.candidateGenerator = candidateGenerator;
        this.lightRanker = lightRanker;
        this.members = members;
        this.cache = cache;
    }

    /**
     * Returns an empty optional only when the requesting member does not exist.
     * A pipeline failure never masquerades as an empty recommendation list.
     */
    public Optional<RecommendationResult> getRecommendations(long memberId, int limit) {
        return timed("request", () -> getRecommendationsInTransaction(memberId, limit));
    }

    private Optional<RecommendationResult> getRecommendationsInTransaction(long memberId, int limit) {
        if (releaseTransaction == null) return getConsistentRecommendations(memberId, limit);
        boolean acquired = false;
        try {
            acquired = requestSlots.tryAcquire(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!acquired) throw new RecommendationGenerationException("Recommendation request capacity is exhausted", null);
            return releaseTransaction.execute(status -> getConsistentRecommendations(memberId, limit));
        } catch (DataAccessException | org.springframework.transaction.TransactionException ex) {
            throw new RecommendationGenerationException("Unable to read a consistent serving release", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RecommendationGenerationException("Interrupted waiting for recommendation capacity", ex);
        } finally { if (acquired) requestSlots.release(); }
    }

    private Optional<RecommendationResult> getConsistentRecommendations(long memberId, int limit) {
        try (var scope = releases == null ? null : releases.pin(); var namespace = cache.pinNamespace()) {
            if (releases != null) {
                try {
                    releases.verifyCandidateBudgets(candidateProperties.sourceLimit(CandidateSourceType.GRAPH_WALK),
                            candidateProperties.sourceLimit(CandidateSourceType.HEURISTIC), candidateProperties.sourceLimit(CandidateSourceType.EMBEDDING));
                } catch (IllegalStateException ex) { throw new RecommendationGenerationException("Incompatible candidate budgets", ex); }
            }
            return getPinnedRecommendations(memberId, limit);
        } catch (DataAccessException ex) {
            throw new RecommendationGenerationException("Unable to pin the serving release", ex);
        }
    }

    private Optional<RecommendationResult> getPinnedRecommendations(long memberId, int limit) {
        if (memberId <= 0) {
            throw new IllegalArgumentException("memberId must be positive");
        }
        if (limit <= 0 || limit > MAX_RESULTS) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_RESULTS);
        }
        if (!members.existsById(memberId)) {
            return Optional.empty();
        }

        try {
            cache.verifyModelVersion(lightRanker.modelVersion());
            if (pipeline != null && pipeline.usesHeavyRanker()) pipeline.heavyRanker().verifyRelease();
        } catch (LightRankerException | HeavyRankerException ex) {
            throw new RecommendationGenerationException("L1 model and cache configuration disagree", ex);
        }

        if (pipeline != null && pipeline.usesReranker()) {
            var pool = cache.getPool(memberId);
            if (pool.isPresent()) return Optional.of(select(pool.orElseThrow(), limit));
        }
        Optional<RecommendationResult> cachedResult = pipeline != null && pipeline.usesReranker()
                ? Optional.empty() : cache.getResult(memberId);
        if (cachedResult.isPresent()) {
            return Optional.of(limit(cachedResult.orElseThrow(), limit));
        }

        final List<MergedCandidate> candidates;
        Optional<List<MergedCandidate>> cachedCandidates = cache.getCandidates(memberId);
        if (cachedCandidates.isPresent()) {
            candidates = cachedCandidates.orElseThrow();
        } else {
            try {
                candidates = timed("l0", () -> candidateGenerator.generate(memberId));
                cache.putCandidates(memberId, candidates);
            } catch (CandidateGenerationException ex) {
                throw new RecommendationGenerationException(
                        "Unable to generate complete recommendations for member " + memberId, ex);
            }
        }

        if (candidates.isEmpty()) {
            if (pipeline != null && pipeline.usesReranker()) {
                var pool = new ScoredPool(memberId, List.of());
                cache.putPool(memberId, pool);
                return Optional.of(select(pool, limit));
            }
            RecommendationResult empty = new RecommendationResult(memberId, List.of());
            cache.putResult(memberId, empty);
            return Optional.of(empty);
        }

        final List<LightRankedCandidate> ranked;
        try {
            ranked = timed("l1", () -> lightRanker.rank(memberId, candidates));
        } catch (LightRankerException | DataAccessException ex) {
            throw new RecommendationGenerationException(
                    "Unable to rank complete recommendations for member " + memberId, ex);
        }

        final List<Recommendation> recommendations;
        if (pipeline != null && pipeline.usesHeavyRanker()) {
            try {
                var heavy = timed("l2", () -> pipeline.heavyRanker().score(memberId, ranked));
                if (heavy == null || heavy.size() != ranked.size()) throw new HeavyRankerException("Incomplete L2 batch");
                for (int i = 0; i < ranked.size(); i++) {
                    if (heavy.get(i) == null || !heavy.get(i).l1().equals(ranked.get(i))) throw new HeavyRankerException("L2 changed survivor membership/order");
                }
                if (pipeline.usesReranker()) {
                    var byId = new java.util.HashMap<Long, com.ahdyahmed.pymk.domain.entity.Member>();
                    members.findAllById(heavy.stream().map(HeavyScoredCandidate::candidateId).toList())
                            .forEach(member -> byId.put(member.getId(), member));
                    var rows = heavy.stream().map(row -> {
                        var member = byId.get(row.candidateId());
                        if (member == null) throw new HeavyRankerException("Missing member for employer selection");
                        return new ScoredPool.Row(new Recommendation(row.candidateId(),
                                pipeline.policy().blend(row.probabilities().inviteSent(), row.probabilities().inviteAccepted()),
                                row.l1().mutualConnectionCount(), reasons(row.l1().candidate().sourceHits(), row.l1().mutualConnectionCount())),
                                member.getCompany());
                    }).toList();
                    var pool = new ScoredPool(memberId, rows);
                    cache.putPool(memberId, pool);
                    return Optional.of(select(pool, limit));
                }
                recommendations = heavy.stream()
                        .sorted(java.util.Comparator.comparingDouble((HeavyScoredCandidate row) -> row.probabilities().inviteAccepted()).reversed()
                                .thenComparingLong(HeavyScoredCandidate::candidateId))
                        .limit(MAX_RESULTS)
                        .map(row -> new Recommendation(row.candidateId(), row.probabilities().inviteAccepted(),
                                row.l1().mutualConnectionCount(), reasons(row.l1().candidate().sourceHits(), row.l1().mutualConnectionCount())))
                        .toList();
            } catch (HeavyRankerException | DataAccessException ex) {
                throw new RecommendationGenerationException("Unable to score complete L2 recommendations for member " + memberId, ex);
            }
        } else {
            recommendations = ranked.stream().limit(MAX_RESULTS).map(RecommendationOrchestrator::toRecommendation).toList();
        }
        RecommendationResult fullResult = new RecommendationResult(memberId, recommendations);
        cache.putResult(memberId, fullResult);
        return Optional.of(limit(fullResult, limit));
    }

    private static RecommendationResult limit(RecommendationResult result, int limit) {
        if (result.recommendations().size() <= limit) {
            return result;
        }
        return new RecommendationResult(
                result.memberId(), result.recommendations().subList(0, limit));
    }

    private RecommendationResult select(ScoredPool pool, int limit) {
        return timed("rerank", () -> pool.select(pipeline.policy(), limit));
    }

    private static Recommendation toRecommendation(LightRankedCandidate ranked) {
        MergedCandidate candidate = ranked.candidate();
        long mutualCount = ranked.mutualConnectionCount();
        return new Recommendation(
                candidate.candidateId(),
                ranked.score(),
                mutualCount,
                reasons(candidate.sourceHits(), mutualCount));
    }

    private static List<String> reasons(List<CandidateHit> hits, long mutualCount) {
        LinkedHashSet<String> reasons = new LinkedHashSet<>();
        if (mutualCount > 0) {
            reasons.add(mutualCount + (mutualCount == 1
                    ? " mutual connection"
                    : " mutual connections"));
        }
        for (CandidateHit hit : hits) {
            if (hit.source() == CandidateSourceType.HEURISTIC) {
                addMetadataReason(reasons, hit, "company", "Same company: ");
                addMetadataReason(reasons, hit, "school", "Same school: ");
                addMetadataReason(reasons, hit, "geoRegion", "Same region: ");
            } else if (hit.source() == CandidateSourceType.GRAPH_WALK && mutualCount == 0) {
                reasons.add("Nearby in your connection graph");
            } else if (hit.source() == CandidateSourceType.EMBEDDING) {
                reasons.add("Similar network profile");
            }
        }
        return List.copyOf(reasons);
    }

    private static void addMetadataReason(
            LinkedHashSet<String> reasons, CandidateHit hit, String key, String prefix) {
        String value = hit.metadata().get(key);
        if (value != null && !value.isBlank()) {
            reasons.add(prefix + value);
        }
    }
}
