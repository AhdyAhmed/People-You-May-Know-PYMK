package com.ahdyahmed.pymk.batch;

import java.time.Instant;
import java.util.Locale;

/** One temporally safe, directional training example exported by Day 18. */
public record TrainingExample(
        long memberId,
        long candidateId,
        int mutualConnectionCount,
        boolean sameCompany,
        boolean sameSchool,
        boolean sameGeo,
        double embeddingCosineSim,
        int profileViewCountLast30d,
        Instant observationWindowStart,
        Instant featureAsOf,
        Instant labelWindowEnd,
        int label) {

    static final String CSV_HEADER = String.join(",",
            "member_id",
            "candidate_id",
            "mutual_connection_count",
            "same_company",
            "same_school",
            "same_geo",
            "embedding_cosine_sim",
            "profile_view_count_last_30d",
            "observation_window_start",
            "feature_as_of",
            "label_window_end",
            "label");

    static final String CSV_V2_HEADER = "schema_version," + CSV_HEADER.substring(0, CSV_HEADER.lastIndexOf(','))
            + ",invite_sent,invite_accepted";

    String toCsvV2(int inviteSent) {
        if (inviteSent != 0 && inviteSent != 1) throw new IllegalArgumentException("invite_sent must be binary");
        String v1 = toCsv();
        return "2," + v1.substring(0, v1.lastIndexOf(',')) + "," + inviteSent + "," + label;
    }

    public TrainingExample {
        if (memberId <= 0 || candidateId <= 0 || memberId == candidateId) {
            throw new IllegalArgumentException("training example pair must contain distinct positive IDs");
        }
        if (mutualConnectionCount < 0 || profileViewCountLast30d < 0) {
            throw new IllegalArgumentException("training example counts must not be negative");
        }
        if (!Double.isFinite(embeddingCosineSim)
                || embeddingCosineSim < -1.0 || embeddingCosineSim > 1.0) {
            throw new IllegalArgumentException("embedding cosine similarity must be in [-1, 1]");
        }
        if (observationWindowStart == null || featureAsOf == null || labelWindowEnd == null
                || !observationWindowStart.isBefore(featureAsOf)
                || !featureAsOf.isBefore(labelWindowEnd)) {
            throw new IllegalArgumentException("training example windows must be strictly ordered");
        }
        if (label != 0 && label != 1) {
            throw new IllegalArgumentException("training label must be binary");
        }
    }

    String toCsv() {
        return String.format(Locale.ROOT,
                "%d,%d,%d,%s,%s,%s,%.17g,%d,%s,%s,%s,%d",
                memberId,
                candidateId,
                mutualConnectionCount,
                sameCompany,
                sameSchool,
                sameGeo,
                embeddingCosineSim,
                profileViewCountLast30d,
                observationWindowStart,
                featureAsOf,
                labelWindowEnd,
                label);
    }
}
