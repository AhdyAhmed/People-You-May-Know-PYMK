package com.ahdyahmed.pymk.datagen.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from {@code pymk.datagen.*} in application.yml, overridable on the
 * command line, e.g.:
 * {@code mvn -pl pymk-datagen spring-boot:run -Dspring-boot.run.arguments=--pymk.datagen.members=50000}
 */
@ConfigurationProperties(prefix = "pymk.datagen")
public class DataGenProperties {

    /** Total members to generate. Design doc targets 50K-1M; default is the 100K portfolio-scale point. */
    private int members = 100_000;

    /** Roughly the average node degree in the generated graph (edges per new member is half this). */
    private int avgDegree = 12;

    /** Target average number of member_events rows per member (approximate; actual count may run slightly over). */
    private double eventsPerMember = 6.0;

    /** Master seed. Same seed + same member count always produces the same dataset. */
    private long seed = 42L;

    /** If true, truncates members/connections/member_events before loading. */
    private boolean truncateExisting = true;

    /** Distinct companies/schools/regions in the fixed pool (see OrganizationPool). */
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
