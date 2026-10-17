package com.ahdyahmed.pymk.api.support;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.ahdyahmed.pymk.domain.entity.Member;
import com.ahdyahmed.pymk.domain.repository.ConnectionRepository;
import com.ahdyahmed.pymk.featurestore.PairId;
import com.ahdyahmed.pymk.featurestore.PymkPairFeature;
import com.ahdyahmed.pymk.featurestore.PymkPairFeatureRepository;
import com.ahdyahmed.pymk.lightranker.LightRankerModel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Deterministic model and materialized-feature fixture for full API tests. */
public final class LightRankerTestSupport {

    private static final Instant FEATURE_AS_OF = Instant.parse("2026-09-30T00:00:00Z");
    private static final Instant COMPUTED_AT = Instant.parse("2026-10-01T00:00:00Z");

    private LightRankerTestSupport() {}

    public static void configureModel(LightRankerModel model) {
        when(model.modelVersion()).thenReturn("light-logreg-v1");
        when(model.score(any(float[][].class))).thenAnswer(invocation -> {
            float[][] matrix = invocation.getArgument(0);
            float[] scores = new float[matrix.length];
            for (int row = 0; row < matrix.length; row++) {
                float[] feature = matrix[row];
                scores[row] = Math.min(
                        0.99f,
                        0.125f
                                + 0.25f * feature[0]
                                + 0.0625f * feature[1]
                                + 0.03125f * feature[2]
                                + 0.015625f * feature[3]
                                + 0.0078125f * feature[4]
                                + 0.0078125f * feature[5]);
            }
            return scores;
        });
    }

    public static void materializeAllPairs(
            List<Member> fixtureMembers,
            ConnectionRepository connections,
            PymkPairFeatureRepository features) {
        List<PymkPairFeature> rows = new ArrayList<>();
        for (Member member : fixtureMembers) {
            Set<Long> memberConnections = new HashSet<>(
                    connections.findConnectedMemberIds(member.getId()));
            for (Member candidate : fixtureMembers) {
                if (member.getId().equals(candidate.getId())) {
                    continue;
                }
                Set<Long> candidateConnections = new HashSet<>(
                        connections.findConnectedMemberIds(candidate.getId()));
                candidateConnections.retainAll(memberConnections);
                rows.add(new PymkPairFeature(
                        new PairId(member.getId(), candidate.getId()),
                        candidateConnections.size(),
                        sameNonBlank(member.getCompany(), candidate.getCompany()),
                        sameNonBlank(member.getSchool(), candidate.getSchool()),
                        sameNonBlank(member.getGeoRegion(), candidate.getGeoRegion()),
                        0.0,
                        0,
                        FEATURE_AS_OF,
                        COMPUTED_AT));
            }
        }
        features.saveAllAndFlush(rows);
    }

    private static boolean sameNonBlank(String left, String right) {
        return left != null && !left.isBlank() && Objects.equals(left, right);
    }
}
