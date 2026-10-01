package com.ahdyahmed.pymk.api.dto;

import com.ahdyahmed.pymk.domain.entity.Member;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * Public view of a {@link Member}. Deliberately a separate type from the JPA
 * entity so the API contract can evolve independently of the persistence model.
 */
@Schema(description = "A member of the network")
public record MemberResponse(
        @Schema(example = "42") Long id,
        @Schema(example = "Ada Lovelace") String fullName,
        @Schema(example = "Senior Software Engineer") String headline,
        @Schema(example = "Acme Corp") String company,
        @Schema(example = "MIT") String school,
        @Schema(example = "Cairo") String geoRegion,
        @Schema(description = "Number of first-degree connections", example = "137") long connectionCount,
        Instant createdAt) {

    public static MemberResponse from(Member member, long connectionCount) {
        return new MemberResponse(
                member.getId(),
                member.getFullName(),
                member.getHeadline(),
                member.getCompany(),
                member.getSchool(),
                member.getGeoRegion(),
                connectionCount,
                member.getCreatedAt());
    }
}
