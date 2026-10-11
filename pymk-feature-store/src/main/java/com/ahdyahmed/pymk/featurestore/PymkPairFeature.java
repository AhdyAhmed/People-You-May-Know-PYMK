package com.ahdyahmed.pymk.featurestore;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

/** Materialized, snapshot-safe features for one directional member-candidate pair. */
@Entity
@Table(
        name = "pymk_features",
        indexes = {
                @Index(name = "idx_pymk_features_candidate", columnList = "candidate_id"),
                @Index(name = "idx_pymk_features_as_of", columnList = "feature_as_of")
        })
public class PymkPairFeature {

    @EmbeddedId
    private PairId id;

    @Column(name = "mutual_connection_count", nullable = false)
    private int mutualConnectionCount;

    @Column(name = "same_company", nullable = false)
    private boolean sameCompany;

    @Column(name = "same_school", nullable = false)
    private boolean sameSchool;

    @Column(name = "same_geo", nullable = false)
    private boolean sameGeo;

    @Column(name = "embedding_cosine_sim", nullable = false)
    private double embeddingCosineSim;

    @Column(name = "profile_view_count_last_30d", nullable = false)
    private int profileViewCountLast30d;

    /** Latest source-data timestamp included in this row. */
    @Column(name = "feature_as_of", nullable = false)
    private Instant featureAsOf;

    /** Wall-clock time at which the row was materialized. */
    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    protected PymkPairFeature() {
        // required by JPA
    }

    public PymkPairFeature(
            PairId id,
            int mutualConnectionCount,
            boolean sameCompany,
            boolean sameSchool,
            boolean sameGeo,
            double embeddingCosineSim,
            int profileViewCountLast30d,
            Instant featureAsOf,
            Instant computedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        if (mutualConnectionCount < 0) {
            throw new IllegalArgumentException("mutualConnectionCount must not be negative");
        }
        if (!Double.isFinite(embeddingCosineSim)
                || embeddingCosineSim < -1.0
                || embeddingCosineSim > 1.0) {
            throw new IllegalArgumentException("embeddingCosineSim must be finite and between -1 and 1");
        }
        if (profileViewCountLast30d < 0) {
            throw new IllegalArgumentException("profileViewCountLast30d must not be negative");
        }
        this.featureAsOf = Objects.requireNonNull(featureAsOf, "featureAsOf must not be null");
        this.computedAt = Objects.requireNonNull(computedAt, "computedAt must not be null");
        if (featureAsOf.isAfter(computedAt)) {
            throw new IllegalArgumentException("featureAsOf must not be after computedAt");
        }
        this.mutualConnectionCount = mutualConnectionCount;
        this.sameCompany = sameCompany;
        this.sameSchool = sameSchool;
        this.sameGeo = sameGeo;
        this.embeddingCosineSim = embeddingCosineSim;
        this.profileViewCountLast30d = profileViewCountLast30d;
    }

    public PairId getId() {
        return id;
    }

    public int getMutualConnectionCount() {
        return mutualConnectionCount;
    }

    public boolean isSameCompany() {
        return sameCompany;
    }

    public boolean isSameSchool() {
        return sameSchool;
    }

    public boolean isSameGeo() {
        return sameGeo;
    }

    public double getEmbeddingCosineSim() {
        return embeddingCosineSim;
    }

    public int getProfileViewCountLast30d() {
        return profileViewCountLast30d;
    }

    public Instant getFeatureAsOf() {
        return featureAsOf;
    }

    public Instant getComputedAt() {
        return computedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PymkPairFeature that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
