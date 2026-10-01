package com.ahdyahmed.pymk.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Result of a connect request")
public record ConnectionResponse(
        @Schema(example = "42") Long memberId,
        @Schema(example = "981") Long connectedMemberId,
        @Schema(description = "true if a new connection was created; false if the members were already connected")
        boolean created) {
}
