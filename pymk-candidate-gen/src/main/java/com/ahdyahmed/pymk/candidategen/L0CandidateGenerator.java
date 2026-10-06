package com.ahdyahmed.pymk.candidategen;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Service;

/** Runs every L0 source concurrently and merges results with provenance. */
@Service
public class L0CandidateGenerator {

    private static final double RECIPROCAL_RANK_OFFSET = 60.0;

    private final List<CandidateSource> sources;
    private final CandidateGenerationProperties properties;

    public L0CandidateGenerator(
            List<CandidateSource> sources,
            CandidateGenerationProperties properties) {
        Objects.requireNonNull(sources, "sources must not be null");
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("At least one candidate source is required");
        }
        this.sources = validatedSourceOrder(sources);
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    public List<MergedCandidate> generate(long memberId) {
        if (memberId <= 0) {
            throw new IllegalArgumentException("memberId must be positive");
        }
        properties.validate();
        for (CandidateSource source : sources) {
            properties.sourceLimit(source.type());
        }

        Semaphore concurrency = new Semaphore(properties.getMaxConcurrency());
        Thread.Builder.OfVirtual threadBuilder = Thread.ofVirtual().name("pymk-l0-", 0);
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(threadBuilder.factory())) {
            List<Future<SourceResult>> futures = sources.stream()
                    .map(source -> executor.submit(() -> runSource(source, memberId, concurrency)))
                    .toList();
            try {
                List<SourceResult> results = new ArrayList<>(futures.size());
                for (Future<SourceResult> future : futures) {
                    results.add(future.get());
                }
                return merge(results, properties.getGlobalLimit());
            } catch (InterruptedException ex) {
                cancel(futures);
                Thread.currentThread().interrupt();
                throw new CandidateGenerationException("Candidate generation was interrupted", ex);
            } catch (ExecutionException ex) {
                cancel(futures);
                Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                throw new CandidateGenerationException("A candidate source failed", cause);
            }
        }
    }

    private SourceResult runSource(
            CandidateSource source,
            long memberId,
            Semaphore concurrency) throws InterruptedException {
        concurrency.acquire();
        try {
            int budget = properties.sourceLimit(source.type());
            List<CandidateHit> generated = List.copyOf(source.generate(memberId, budget));
            List<CandidateHit> hits = generated.stream().limit(budget).toList();
            for (CandidateHit hit : hits) {
                if (hit.source() != source.type()) {
                    throw new IllegalStateException(
                            source.type() + " returned a hit labeled as " + hit.source());
                }
            }
            return new SourceResult(source.type(), hits);
        } finally {
            concurrency.release();
        }
    }

    private static List<MergedCandidate> merge(List<SourceResult> results, int globalLimit) {
        Map<Long, Accumulator> candidates = new LinkedHashMap<>();
        for (SourceResult result : results) {
            int rank = 1;
            for (CandidateHit hit : result.hits()) {
                Accumulator candidate = candidates.computeIfAbsent(
                        hit.candidateId(), ignored -> new Accumulator());
                if (candidate.add(hit, reciprocalRank(rank))) {
                    rank++;
                }
            }
        }

        return candidates.entrySet().stream()
                .map(entry -> entry.getValue().toCandidate(entry.getKey()))
                .sorted(Comparator.comparingDouble(MergedCandidate::fusionScore)
                        .reversed()
                        .thenComparingLong(MergedCandidate::candidateId))
                .limit(globalLimit)
                .toList();
    }

    private static double reciprocalRank(int rank) {
        return 1.0 / (RECIPROCAL_RANK_OFFSET + rank);
    }

    private static List<CandidateSource> validatedSourceOrder(Collection<CandidateSource> sources) {
        Map<CandidateSourceType, CandidateSource> byType = new EnumMap<>(CandidateSourceType.class);
        for (CandidateSource source : sources) {
            Objects.requireNonNull(source, "sources must not contain null");
            CandidateSource previous = byType.putIfAbsent(source.type(), source);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate candidate source type: " + source.type());
            }
        }
        return byType.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .toList();
    }

    private static void cancel(List<? extends Future<?>> futures) {
        futures.forEach(future -> future.cancel(true));
    }

    private record SourceResult(CandidateSourceType source, List<CandidateHit> hits) {
    }

    private static final class Accumulator {
        private final Map<CandidateSourceType, CandidateHit> hits =
                new EnumMap<>(CandidateSourceType.class);
        private double fusionScore;

        boolean add(CandidateHit hit, double rankContribution) {
            if (hits.putIfAbsent(hit.source(), hit) != null) {
                return false;
            }
            fusionScore += rankContribution;
            return true;
        }

        MergedCandidate toCandidate(long candidateId) {
            return new MergedCandidate(candidateId, fusionScore, List.copyOf(hits.values()));
        }
    }
}
