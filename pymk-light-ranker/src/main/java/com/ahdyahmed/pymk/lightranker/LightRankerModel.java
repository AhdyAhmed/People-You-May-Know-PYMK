package com.ahdyahmed.pymk.lightranker;

import java.util.List;

/** Runtime-neutral scoring contract used by the L1 candidate ranker. */
public interface LightRankerModel extends AutoCloseable {

    List<String> FEATURE_NAMES = List.of(
            "mutual_connection_count",
            "same_company",
            "same_school",
            "same_geo",
            "embedding_cosine_sim",
            "profile_view_count_last_30d");

    float[] score(float[][] features);

    String modelVersion();

    @Override
    default void close() {
        // Most test or alternative implementations do not own native resources.
    }
}
