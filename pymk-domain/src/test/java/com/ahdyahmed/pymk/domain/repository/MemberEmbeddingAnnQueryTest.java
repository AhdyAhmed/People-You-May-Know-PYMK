package com.ahdyahmed.pymk.domain.repository;

import static com.ahdyahmed.pymk.domain.support.TestData.member;
import static org.assertj.core.api.Assertions.assertThat;

import com.ahdyahmed.pymk.domain.entity.Member;
import com.ahdyahmed.pymk.domain.entity.MemberEmbedding;
import com.ahdyahmed.pymk.domain.support.PostgresDataJpaTest;
import java.time.Instant;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Backs the Day 5 roadmap claim: "Confirm an ANN query (the {@code <=>}
 * operator) runs and returns sensible neighbors." Builds two well-separated
 * clusters of embeddings and checks that ANN search for a member in cluster
 * A returns mostly other cluster-A members - a much stronger claim than
 * "the query doesn't throw".
 *
 * <p>The bulk placeholder embeddings pymk-datagen generates are pure random
 * noise (per PYMK_ROADMAP.md Day 5: "random vectors is fine for now"), so
 * they intentionally do <b>not</b> cluster - this test is what proves the
 * ANN mechanism itself works correctly, using embeddings constructed to
 * actually be meaningful.</p>
 */
@PostgresDataJpaTest
class MemberEmbeddingAnnQueryTest {

    private static final int DIMENSIONS = MemberEmbedding.DIMENSIONS;
    private static final int MEMBERS_PER_CLUSTER = 30;

    @Autowired
    MemberRepository members;

    @Autowired
    MemberEmbeddingRepository embeddings;

    @Autowired
    EmbeddingSearchRepository search;

    private final Set<Long> clusterAIds = new HashSet<>();
    private final Set<Long> clusterBIds = new HashSet<>();

    @BeforeEach
    void seedTwoClusters() {
        Random random = new Random(11);

        // Antipodal centroids on the unit sphere: as far apart as two unit
        // vectors can be, so cluster separation can't be a coincidence of
        // the noise draw.
        double[] centroidA = constant(DIMENSIONS, 1.0);
        double[] centroidB = constant(DIMENSIONS, -1.0);

        long nextId = 1;
        List<Member> memberRows = new ArrayList<>();
        List<MemberEmbedding> embeddingRows = new ArrayList<>();

        for (int i = 0; i < MEMBERS_PER_CLUSTER; i++) {
            long id = nextId++;
            memberRows.add(member(id));
            embeddingRows.add(new MemberEmbedding(id, clusteredVector(centroidA, random, 0.2), Instant.now()));
            clusterAIds.add(id);
        }
        for (int i = 0; i < MEMBERS_PER_CLUSTER; i++) {
            long id = nextId++;
            memberRows.add(member(id));
            embeddingRows.add(new MemberEmbedding(id, clusteredVector(centroidB, random, 0.2), Instant.now()));
            clusterBIds.add(id);
        }

        members.saveAll(memberRows);
        members.flush();
        embeddings.saveAll(embeddingRows);
        embeddings.flush();
    }

    @Test
    void nearestNeighborsOfAStoredEmbeddingComeFromItsOwnCluster() {
        long queryMember = clusterAIds.iterator().next();

        List<Long> neighbors = search.findNearestNeighborIds(queryMember, 20);

        assertThat(neighbors).doesNotContain(queryMember);
        long fromClusterA = neighbors.stream().filter(clusterAIds::contains).count();
        assertThat(fromClusterA)
                .as("of %d neighbors, how many came from the query member's own cluster", neighbors.size())
                .isGreaterThanOrEqualTo((long) (neighbors.size() * 0.9));
    }

    @Test
    void nearestNeighborsOfAnArbitraryVectorFindTheRightCluster() {
        float[] nearClusterB = clusteredVector(constant(DIMENSIONS, -1.0), new Random(99), 0.05);

        List<Long> neighbors = search.findNearestNeighborIds(nearClusterB, null, 10);

        long fromClusterB = neighbors.stream().filter(clusterBIds::contains).count();
        assertThat(fromClusterB)
                .as("of %d neighbors, how many came from cluster B", neighbors.size())
                .isGreaterThanOrEqualTo((long) (neighbors.size() * 0.9));
    }

    @Test
    void exportedPythonProfileVectorsRetrieveSemanticNeighborsAndExcludeBlankAndOtherVersions() throws Exception {
        Instant asOf = Instant.parse("2026-01-03T00:00:00Z");
        try (var reader = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("/embeddings/profile-svd-v1.tsv"), StandardCharsets.UTF_8))) {
            for (String line : reader.lines().toList()) {
                String[] columns = line.split("\t");
                long id = Long.parseLong(columns[0]) + 100;
                String literal = columns[1];
                String[] values = literal.substring(1, literal.length() - 1).split(",");
                float[] vector = new float[128];
                assertThat(values).hasSize(128);
                for (int i = 0; i < values.length; i++) vector[i] = Float.parseFloat(values[i].trim());
                members.saveAndFlush(member(id));
                embeddings.saveAndFlush(new MemberEmbedding(id, vector, asOf, "profile-svd-v1", asOf));
            }
        }
        assertThat(search.findNearestNeighborIds(101, 10)).containsExactly(102L, 103L);
        assertThat(search.findNearestUnconnectedNeighbors(101, 10))
                .extracting(EmbeddingSearchRepository.EmbeddingNeighbor::memberId)
                .containsExactly(102L, 103L);
        assertThat(search.findNearestNeighbors(101, 10).getFirst().cosineDistance()).isCloseTo(
                0.0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(search.findNearestNeighborIds(104, 10)).isEmpty();
        assertThat(search.findNearestNeighborIds(new float[128], null, 10)).isEmpty();
        var saved = embeddings.findById(101L).orElseThrow();
        assertThat(saved.getEmbeddingVersion()).isEqualTo("profile-svd-v1");
        assertThat(saved.getFitCutoff()).isEqualTo(asOf);
        assertThat(search.findNearestNeighborIds(saved, 10)).containsExactly(102L, 103L);
        assertThat(search.findNearestNeighborIds(saved.getEmbedding(), 101L, 100))
                .doesNotContain(102L, 103L, 104L).hasSize(60);
        assertThat(search.findNearestNeighborIds(saved.getEmbedding(), null, 100))
                .doesNotContain(101L, 102L, 103L, 104L).hasSize(60);

        // Reusing a version label with a different fit cutoff must not mix spaces.
        embeddings.saveAndFlush(new MemberEmbedding(102L, saved.getEmbedding(), asOf,
                "profile-svd-v1", asOf.minusSeconds(1)));
        assertThat(search.findNearestNeighborIds(101, 10)).containsExactly(103L);
        assertThat(search.findNearestNeighborIds(saved, 10)).containsExactly(103L);
        assertThat(search.findNearestUnconnectedNeighbors(101, 10))
                .extracting(EmbeddingSearchRepository.EmbeddingNeighbor::memberId).containsExactly(103L);
    }

    private static double[] constant(int dimensions, double value) {
        double[] v = new double[dimensions];
        java.util.Arrays.fill(v, value);
        return v;
    }

    private static float[] clusteredVector(double[] centroid, Random random, double noiseStd) {
        float[] v = new float[centroid.length];
        double sumSquares = 0;
        for (int i = 0; i < centroid.length; i++) {
            double value = centroid[i] + random.nextGaussian() * noiseStd;
            v[i] = (float) value;
            sumSquares += value * value;
        }
        float norm = (float) Math.sqrt(sumSquares);
        for (int i = 0; i < v.length; i++) {
            v[i] /= norm;
        }
        return v;
    }
}
