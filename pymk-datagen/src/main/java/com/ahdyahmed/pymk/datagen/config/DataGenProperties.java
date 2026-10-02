package com.ahdyahmed.pymk.datagen.config;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Bound from {@code pymk.datagen.*} in application.yml, overridable on the
 * command line, e.g.:
 * {@code mvn -pl pymk-datagen spring-boot:run -Dspring-boot.run.arguments=--pymk.datagen.members=50000}
 */
@ConfigurationProperties(prefix = "pymk.datagen")
@Validated
public class DataGenProperties {

    /** Total members to generate. Design doc targets 50K-1M; default is the 100K portfolio-scale point. */
    @Min(2)
    private int members = 100_000;

    /** Roughly the average node degree in the generated graph (edges per new member is half this). */
    @Min(2)
    private int avgDegree = 12;

    /** Additional non-accepted-connection event attempts per member. */
    @DecimalMin("0.0")
    private double eventsPerMember = 2.0;

    /** Master seed. Same seed + same member count always produces the same dataset. */
    private long seed = 42L;

    /** If true, truncates members/connections/member_events/member_embeddings before loading. */
    private boolean truncateExisting = true;

    /** Distinct companies/schools/regions in the fixed pool (see OrganizationPool). */
    @Min(1)
    private int organizationPoolSize = 250;

    public int getMembers() {
        return members;
    }

    public void setMembers(int members) {
        this.members = members;
    }

    public int getAvgDegree() {
        return avgDegree;
    }

    public void setAvgDegree(int avgDegree) {
        this.avgDegree = avgDegree;
    }

    public double getEventsPerMember() {
        return eventsPerMember;
    }

    public void setEventsPerMember(double eventsPerMember) {
        this.eventsPerMember = eventsPerMember;
    }

    public long getSeed() {
        return seed;
    }

    public void setSeed(long seed) {
        this.seed = seed;
    }

    public boolean isTruncateExisting() {
        return truncateExisting;
    }

    public void setTruncateExisting(boolean truncateExisting) {
        this.truncateExisting = truncateExisting;
    }

    public int getOrganizationPoolSize() {
        return organizationPoolSize;
    }

    public void setOrganizationPoolSize(int organizationPoolSize) {
        this.organizationPoolSize = organizationPoolSize;
    }
}
