package com.ahdyahmed.pymk.reranker;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BlendDiversityPolicyTest {
    @Test void validatesAndNormalizesWithoutOverflow() {
        var policy = new BlendDiversityPolicy(2, 6, .4);
        assertThat(policy.sentWeight()).isEqualTo(.25);
        assertThat(policy.blend(.8, .4)).isCloseTo(.5, within(1e-15));
        assertThat(new BlendDiversityPolicy(Double.MAX_VALUE, Double.MAX_VALUE, 1).blend(1, 1)).isEqualTo(1);
        assertThat(new BlendDiversityPolicy(0, 1, .4).blend(.9, .1)).isEqualTo(.1);
        assertThat(new BlendDiversityPolicy(1, 0, .4).blend(.9, .1)).isEqualTo(.9);
        assertThat(policy.identity()).isEqualTo(new BlendDiversityPolicy(1, 3, .4).identity());
        for (double invalid : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThatThrownBy(() -> new BlendDiversityPolicy(invalid, 1, .4)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new BlendDiversityPolicy(1, invalid, .4)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new BlendDiversityPolicy(0, 0, .4)).isInstanceOf(IllegalArgumentException.class);
        for (double invalid : new double[]{0, -1, 1.1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThatThrownBy(() -> new BlendDiversityPolicy(1, 1, invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> policy.blend(Double.NaN, .5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.blend(.5, 1.1)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest @ValueSource(ints={1,2,20,100})
    void concentratedAndUnknownPoolsHaveExplicitUnderfillSemantics(int limit) {
        var policy = new BlendDiversityPolicy(1, 1, .4);
        var concentrated = IntStream.rangeClosed(1, 200).mapToObj(i -> new BlendDiversityPolicy.Candidate(i, .5, " AcMe ")).toList();
        var result = policy.select(concentrated, limit);
        int cap = Math.max(1, (int)Math.floor(.4 * limit));
        assertThat(result.candidateIds()).hasSize(cap);
        assertThat(result.exposure().knownGroupCounts()).containsEntry("acme", cap);
        assertThat(result.exposure().maxKnownGroupFraction()).isEqualTo(1); // Not a returned-page fraction guarantee.
        var unknown = concentrated.stream().map(row -> new BlendDiversityPolicy.Candidate(row.id(), row.score(), row.id()%2==0 ? null : " \t ")).toList();
        var blank = policy.select(unknown, limit);
        assertThat(blank.candidateIds()).hasSize(limit).isSorted();
        assertThat(blank.exposure().unknownGroupCount()).isEqualTo(limit);
        assertThat(blank.exposure().knownGroupCounts()).isEmpty();
        assertThat(blank.exposure().maxKnownGroupFraction()).isZero();
    }

    @Test void skipsCappedRowsAndTiesByIdDeterministicallyBeyondTop100() {
        var pool = new ArrayList<BlendDiversityPolicy.Candidate>();
        for(int i=1;i<=240;i++) pool.add(new BlendDiversityPolicy.Candidate(i, 1-i/1000., i<=120 ? "A" : i<=180 ? "B" : "C"));
        Collections.reverse(pool);
        var policy = new BlendDiversityPolicy(1, 1, .4);
        var result = policy.select(pool, 100);
        assertThat(result.candidateIds()).hasSize(100).contains(181L).isSorted();
        assertThat(result.exposure().knownGroupCounts()).containsExactlyInAnyOrderEntriesOf(Map.of("a",40,"b",40,"c",20));
        assertThat(policy.select(List.of(new BlendDiversityPolicy.Candidate(2,.5,"x"),new BlendDiversityPolicy.Candidate(1,.5,"y")),2).candidateIds()).containsExactly(1L,2L);
        assertThat(policy.select(List.of(),20).exposure().returnedSize()).isZero();
        assertThatThrownBy(() -> policy.select(List.of(pool.getFirst(),pool.getFirst()),20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.select(pool,101)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void decimalCapDoesNotLoseASlotAtAnIntegerBoundary() {
        assertThat(new BlendDiversityPolicy(1,1,.29).select(List.of(),100).exposure().knownGroupCap()).isEqualTo(29);
        assertThat(new BlendDiversityPolicy(1,1,.299).select(List.of(),100).exposure().knownGroupCap()).isEqualTo(29);
    }
}
