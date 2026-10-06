package com.ahdyahmed.pymk.candidategen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

class L0CandidateGeneratorTest {

    @Test
    void appliesBudgetsMergesProvenanceAndEnforcesGlobalCap() {
        AtomicInteger heuristicLimit = new AtomicInteger();
        AtomicInteger graphLimit = new AtomicInteger();
        AtomicInteger embeddingLimit = new AtomicInteger();

        CandidateSource heuristic = source(CandidateSourceType.HEURISTIC, (memberId, limit) -> {
            heuristicLimit.set(limit);
            // Deliberately violates the source contract with a third hit; the
            // union still enforces the configured source budget defensively.
            return List.of(
                    hit(1, CandidateSourceType.HEURISTIC),
                    hit(2, CandidateSourceType.HEURISTIC),
                    hit(99, CandidateSourceType.HEURISTIC));
        });
        CandidateSource graph = source(CandidateSourceType.GRAPH_WALK, (memberId, limit) -> {
            graphLimit.set(limit);
            return List.of(
                    hit(2, CandidateSourceType.GRAPH_WALK),
                    hit(3, CandidateSourceType.GRAPH_WALK),
                    hit(4, CandidateSourceType.GRAPH_WALK));
        });
        CandidateSource embedding = source(CandidateSourceType.EMBEDDING, (memberId, limit) -> {
            embeddingLimit.set(limit);
            return List.of(hit(2, CandidateSourceType.EMBEDDING), hit(4, CandidateSourceType.EMBEDDING));
        });

        CandidateGenerationProperties properties = properties(3, 3, Map.of(
                CandidateSourceType.HEURISTIC, 2,
                CandidateSourceType.GRAPH_WALK, 3,
                CandidateSourceType.EMBEDDING, 2));
        L0CandidateGenerator generator = new L0CandidateGenerator(
                List.of(embedding, heuristic, graph), properties);

        List<MergedCandidate> candidates = generator.generate(42);

        assertThat(heuristicLimit).hasValue(2);
        assertThat(graphLimit).hasValue(3);
        assertThat(embeddingLimit).hasValue(2);
        assertThat(candidates).extracting(MergedCandidate::candidateId)
                .containsExactly(2L, 4L, 1L);
        assertThat(candidates).extracting(MergedCandidate::candidateId).doesNotContain(99L);
        assertThat(candidates.getFirst().sourceHits())
                .extracting(CandidateHit::source)
                .containsExactly(
                        CandidateSourceType.HEURISTIC,
                        CandidateSourceType.GRAPH_WALK,
                        CandidateSourceType.EMBEDDING);
        assertThat(candidates.getFirst().fusionScore())
                .isGreaterThan(candidates.get(1).fusionScore());
        assertThatThrownBy(() -> candidates.getFirst().sourceHits().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void virtualThreadFanOutHonorsConcurrencyBound() {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        AtomicBoolean allVirtual = new AtomicBoolean(true);
        CountDownLatch firstWaveStarted = new CountDownLatch(2);

        List<CandidateSource> sources = new ArrayList<>();
        int candidateId = 1;
        for (CandidateSourceType type : CandidateSourceType.values()) {
            long id = candidateId++;
            sources.add(source(type, (memberId, limit) -> {
                allVirtual.compareAndSet(true, Thread.currentThread().isVirtual());
                int nowActive = active.incrementAndGet();
                maxActive.accumulateAndGet(nowActive, Math::max);
                firstWaveStarted.countDown();
                try {
                    if (!firstWaveStarted.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent sources did not start in time");
                    }
                    Thread.sleep(25);
                    return List.of(hit(id, type));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Stub source interrupted", ex);
                } finally {
                    active.decrementAndGet();
                }
            }));
        }

        L0CandidateGenerator generator = new L0CandidateGenerator(
                sources,
                properties(10, 2, Map.of(
                        CandidateSourceType.HEURISTIC, 1,
                        CandidateSourceType.GRAPH_WALK, 1,
                        CandidateSourceType.EMBEDDING, 1)));

        assertThat(generator.generate(42)).hasSize(3);
        assertThat(allVirtual).isTrue();
        assertThat(maxActive).hasValue(2);
    }

    @Test
    void sourceFailureIsReportedAndDuplicateSourceTypesAreRejected() {
        CandidateSource working = source(
                CandidateSourceType.HEURISTIC,
                (memberId, limit) -> List.of(hit(1, CandidateSourceType.HEURISTIC)));
        CandidateSource failing = source(CandidateSourceType.GRAPH_WALK, (memberId, limit) -> {
            throw new IllegalStateException("graph unavailable");
        });
        CandidateGenerationProperties properties = properties(10, 2, Map.of(
                CandidateSourceType.HEURISTIC, 1,
                CandidateSourceType.GRAPH_WALK, 1));

        L0CandidateGenerator generator = new L0CandidateGenerator(List.of(working, failing), properties);

        assertThatThrownBy(() -> generator.generate(42))
                .isInstanceOf(CandidateGenerationException.class)
                .hasRootCauseMessage("graph unavailable");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new L0CandidateGenerator(List.of(working, working), properties))
                .withMessageContaining("Duplicate candidate source type");
    }

    @Test
    void rejectsInvalidMemberAndConfiguration() {
        CandidateSource source = source(
                CandidateSourceType.HEURISTIC,
                (memberId, limit) -> List.of(hit(1, CandidateSourceType.HEURISTIC)));
        CandidateGenerationProperties properties = properties(
                1, 1, Map.of(CandidateSourceType.HEURISTIC, 1));
        L0CandidateGenerator generator = new L0CandidateGenerator(List.of(source), properties);

        assertThatIllegalArgumentException().isThrownBy(() -> generator.generate(0));
        properties.setGlobalLimit(0);
        assertThatThrownBy(() -> generator.generate(42))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("globalLimit");
    }

    private static CandidateGenerationProperties properties(
            int globalLimit,
            int maxConcurrency,
            Map<CandidateSourceType, Integer> sourceLimits) {
        CandidateGenerationProperties properties = new CandidateGenerationProperties();
        properties.setGlobalLimit(globalLimit);
        properties.setMaxConcurrency(maxConcurrency);
        properties.setSourceLimits(new EnumMap<>(sourceLimits));
        return properties;
    }

    private static CandidateSource source(
            CandidateSourceType type,
            BiFunction<Long, Integer, List<CandidateHit>> behavior) {
        return new CandidateSource() {
            @Override
            public CandidateSourceType type() {
                return type;
            }

            @Override
            public List<CandidateHit> generate(long memberId, int limit) {
                return behavior.apply(memberId, limit);
            }
        };
    }

    private static CandidateHit hit(long candidateId, CandidateSourceType source) {
        return new CandidateHit(candidateId, source, 1.0, Map.of("source", source.name()));
    }
}
