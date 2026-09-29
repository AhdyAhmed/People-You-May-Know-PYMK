package com.ahdyahmed.pymk.datagen.generate;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.function.Function;
import net.datafaker.Faker;

/**
 * A small, fixed pool of organizations (companies/schools/regions), sampled
 * with a Zipf-distributed popularity so a handful of entries dominate
 * membership - mirroring how real professional networks cluster around a
 * few large employers, universities, and metro areas.
 *
 * <p>This is what makes {@code HeuristicCandidateSource} (Day 8) meaningful:
 * if every member had a unique company string, "same company" candidates
 * would never exist.</p>
 */
public final class OrganizationPool {

    private final List<String> values;
    private final double[] cumulativeWeights;

    private OrganizationPool(List<String> values) {
        this.values = values;
        this.cumulativeWeights = zipfCumulativeWeights(values.size());
    }

    public static OrganizationPool companies(Faker faker, int size) {
        return build(faker, size, f -> f.company().name());
    }

    public static OrganizationPool schools(Faker faker, int size) {
        return build(faker, size, f -> f.university().name());
    }

    public static OrganizationPool regions(Faker faker, int size) {
        return build(faker, size, f -> f.address().city());
    }

    private static OrganizationPool build(Faker faker, int size, Function<Faker, String> generator) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        int guardAttempts = size * 20;
        while (unique.size() < size && guardAttempts-- > 0) {
            unique.add(generator.apply(faker));
        }
        return new OrganizationPool(List.copyOf(unique));
    }

    private static double[] zipfCumulativeWeights(int size) {
        double[] cumulative = new double[size];
        double total = 0;
        for (int rank = 1; rank <= size; rank++) {
            total += 1.0 / rank;
            cumulative[rank - 1] = total;
        }
        for (int i = 0; i < size; i++) {
            cumulative[i] /= total;
        }
        return cumulative;
    }

    /** Samples one value; lower-index (rank 1) values are drawn far more often. */
    public String sample(Random random) {
        double r = random.nextDouble();
        int idx = Arrays.binarySearch(cumulativeWeights, r);
        if (idx < 0) {
            idx = -idx - 1;
        }
        return values.get(Math.min(idx, values.size() - 1));
    }

    public int size() {
        return values.size();
    }
}
