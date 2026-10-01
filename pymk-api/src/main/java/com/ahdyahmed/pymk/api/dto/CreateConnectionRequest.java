package com.ahdyahmed.pymk.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** Body of {@code POST /api/v1/connections}: simulates accepting a PYMK suggestion. */
@Schema(description = "Request to connect two members")
public record CreateConnectionRequest(
        @Schema(description = "Member accepting the suggestion", example = "42")
        @NotNull @Positive Long memberId,
        @Schema(description = "Suggested member being connected to", example = "981")
        @NotNull @Positive Long connectedMemberId) {
}
