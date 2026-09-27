package com.ahdyahmed.pymk.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

/**
 * A member of the network. Matches PYMK_DESIGN.md section 5.1.
 *
 * <p>{@code id} is intentionally <b>not</b> {@code @GeneratedValue} — member
 * IDs are assigned by the synthetic data generator (Day 4) rather than by
 * the database, mirroring how a real system would already have a member ID
 * space owned upstream (e.g. by an identity service).</p>
 */
@Entity
@Table(name = "members")
public class Member {

    @Id
    private Long id;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "headline")
    private String headline;

    @Column(name = "company")
    private String company;

    @Column(name = "school")
    private String school;

    @Column(name = "geo_region")
    private String geoRegion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Member() {
        // required by JPA
    }

    public Member(Long id, String fullName, String headline, String company,
                  String school, String geoRegion, Instant createdAt) {
        this.id = id;
        this.fullName = fullName;
        this.headline = headline;
        this.company = company;
        this.school = school;
        this.geoRegion = geoRegion;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getHeadline() {
        return headline;
    }

    public void setHeadline(String headline) {
        this.headline = headline;
    }

    public String getCompany() {
        return company;
    }

    public void setCompany(String company) {
        this.company = company;
    }

    public String getSchool() {
        return school;
    }

    public void setSchool(String school) {
        this.school = school;
    }

    public String getGeoRegion() {
        return geoRegion;
    }

    public void setGeoRegion(String geoRegion) {
        this.geoRegion = geoRegion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Member member)) return false;
        return Objects.equals(id, member.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "Member{id=%s, fullName='%s', company='%s', school='%s', geoRegion='%s'}"
                .formatted(id, fullName, company, school, geoRegion);
    }
}
