package com.ahdyahmed.pymk.api.controller;

import com.ahdyahmed.pymk.api.dto.PymkResponse;
import com.ahdyahmed.pymk.api.service.PymkService;
import com.ahdyahmed.pymk.orchestrator.RecommendationOrchestrator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pymk")
@Tag(name = "People You May Know", description = "Member connection recommendations")
public class PymkController {

    private final PymkService pymkService;

    public PymkController(PymkService pymkService) {
        this.pymkService = pymkService;
    }

    @GetMapping("/{memberId}")
    @Operation(summary = "Get people a member may know",
            description = "Naive v1 baseline ordered by mutual connections; later ranking stages will replace the score.")
    @ApiResponse(responseCode = "200", description = "Recommendations generated")
    @ApiResponse(responseCode = "400", description = "Member ID or limit is invalid",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No member with that ID",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "A candidate source failed",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    public PymkResponse getRecommendations(
            @Parameter(description = "Positive member ID", example = "42")
            @Positive @PathVariable long memberId,
            @Parameter(description = "Result count from 1 to 100", example = "20")
            @Min(1) @Max(RecommendationOrchestrator.MAX_RESULTS)
            @RequestParam(defaultValue = "20") int limit) {
        return pymkService.getRecommendations(memberId, limit);
    }
}
