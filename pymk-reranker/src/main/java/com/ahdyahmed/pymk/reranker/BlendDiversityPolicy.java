package com.ahdyahmed.pymk.reranker;

import java.util.*;

/** Immutable, deterministic exposure policy. This is not a protected-attribute fairness policy. */
public final class BlendDiversityPolicy {
    private final double sentWeight;
    private final double acceptedWeight;
    private final double capFraction;

    public BlendDiversityPolicy(double sentWeight, double acceptedWeight, double capFraction) {
        if (!Double.isFinite(sentWeight) || !Double.isFinite(acceptedWeight)
                || sentWeight < 0 || acceptedWeight < 0 || Math.max(sentWeight, acceptedWeight) == 0) {
            throw new IllegalArgumentException("Blend weights must be finite, nonnegative and not both zero");
        }
        if (!Double.isFinite(capFraction) || capFraction <= 0 || capFraction > 1) {
            throw new IllegalArgumentException("Employer cap fraction must be in (0,1]");
        }
        // Scaling first prevents overflow even for two Double.MAX_VALUE weights.
        double maximum = Math.max(sentWeight, acceptedWeight);
        double sent = sentWeight / maximum;
        double accepted = acceptedWeight / maximum;
        this.sentWeight = sent / (sent + accepted);
        this.acceptedWeight = 1 - this.sentWeight;
        this.capFraction = capFraction;
    }

    public double blend(double sent, double accepted) {
        probability(sent);
        probability(accepted);
        return Math.min(1, sentWeight * sent + acceptedWeight * accepted);
    }

    private static void probability(double value) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException("Scores must be finite probabilities");
        }
    }

    public static String employerGroup(String employer) {
        return employer == null ? "" : employer.strip().toLowerCase(Locale.ROOT);
    }

    public record Candidate(long id, double score, String group) {
        public Candidate {
            if (id <= 0) throw new IllegalArgumentException("Candidate ID must be positive");
            probability(score);
            group = employerGroup(group);
        }
    }

    public record Exposure(int requestedLimit, int returnedSize, int knownGroupCap,
            Map<String, Integer> knownGroupCounts, int unknownGroupCount, double maxKnownGroupFraction) {
        public Exposure { knownGroupCounts = Collections.unmodifiableMap(new TreeMap<>(knownGroupCounts)); }
    }

    public record Selection(List<Long> candidateIds, Exposure exposure) {
        public Selection { candidateIds = List.copyOf(candidateIds); }
    }

    public Selection select(List<Candidate> pool, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Limit must be between 1 and 100");
        var ids = new HashSet<Long>();
        for (var row : pool) if (!ids.add(row.id())) throw new IllegalArgumentException("Duplicate candidate ID");
        // Decimal configuration such as 0.29 * 100 must not lose a slot to binary rounding.
        int cap = Math.max(1, java.math.BigDecimal.valueOf(capFraction)
                .multiply(java.math.BigDecimal.valueOf(limit)).intValue());
        var counts = new TreeMap<String, Integer>();
        var selected = new ArrayList<Long>();
        int unknown = 0;
        for (var row : pool.stream().sorted(Comparator.comparingDouble(Candidate::score).reversed()
                .thenComparingLong(Candidate::id)).toList()) {
            // Unknown employers are exempt, not one artificial dominant employer.
            if (!row.group().isEmpty() && counts.getOrDefault(row.group(), 0) >= cap) continue;
            selected.add(row.id());
            if (row.group().isEmpty()) unknown++;
            else counts.merge(row.group(), 1, Integer::sum);
            if (selected.size() == limit) break;
        }
        int max = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        return new Selection(selected, new Exposure(limit, selected.size(), cap, counts, unknown,
                selected.isEmpty() ? 0 : (double) max / selected.size()));
    }

    public double sentWeight() { return sentWeight; }
    public double acceptedWeight() { return acceptedWeight; }
    public double capFraction() { return capFraction; }
    public String identity() {
        return "day29-blend-employer-v1-" + Double.toHexString(sentWeight) + "-"
                + Double.toHexString(acceptedWeight) + "-" + Double.toHexString(capFraction);
    }
}
