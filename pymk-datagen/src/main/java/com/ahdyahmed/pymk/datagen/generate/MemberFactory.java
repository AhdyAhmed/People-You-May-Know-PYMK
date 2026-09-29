package com.ahdyahmed.pymk.datagen.generate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Random;
import net.datafaker.Faker;

/** Builds one synthetic Member row at a time, matching the members table shape. */
public final class MemberFactory {

    public record MemberRecord(
            long id,
            String fullName,
            String headline,
            String company,
            String school,
            String geoRegion,
            Instant createdAt) {
    }

    private final Faker faker;
    private final OrganizationPool companies;
    private final OrganizationPool schools;
    private final OrganizationPool regions;
    private final Random random;
    private final Instant now;

    public MemberFactory(Faker faker, OrganizationPool companies, OrganizationPool schools,
                          OrganizationPool regions, Random random, Instant now) {
        this.faker = faker;
        this.companies = companies;
        this.schools = schools;
        this.regions = regions;
        this.random = random;
        this.now = now;
    }

    public MemberRecord create(long id) {
        // Spread join dates over the last ~6 years so createdAt has real variance.
        Instant createdAt = now.minus(random.nextInt(6 * 365), ChronoUnit.DAYS);
        return new MemberRecord(
                id,
                faker.name().fullName(),
                faker.job().title(),
                companies.sample(random),
                schools.sample(random),
                regions.sample(random),
                createdAt);
    }
}
