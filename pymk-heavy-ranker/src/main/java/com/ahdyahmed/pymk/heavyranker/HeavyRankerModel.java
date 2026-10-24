package com.ahdyahmed.pymk.heavyranker;

import java.time.Instant;
import java.util.List;

public interface HeavyRankerModel extends AutoCloseable {
    List<String> OUTPUTS = List.of("invite_sent_probability", "invite_accepted_probability");
    record Probabilities(float inviteSent, float inviteAccepted) {
        public Probabilities {
            if (!Float.isFinite(inviteSent) || !Float.isFinite(inviteAccepted)
                    || inviteSent < 0 || inviteSent > 1 || inviteAccepted < 0 || inviteAccepted > 1) {
                throw new HeavyRankerException("L2 returned invalid probabilities");
            }
        }
    }
    record Compatibility(String featureSchema, String embeddingVersion, String transformSha256,
                         Instant fitCutoff, Instant selectionCutoff) {}
    List<Probabilities> score(float[][] features);
    String modelVersion();
    /** Content identity for public serving cache isolation; custom models must supply it. */
    default String artifactIdentity() { throw new HeavyRankerException("L2 artifact identity is unavailable"); }
    Compatibility compatibility();
    @Override default void close() {}
}
