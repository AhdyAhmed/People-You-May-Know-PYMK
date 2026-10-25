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
    @org.springframework.beans.factory.annotation.Autowired
    private com.ahdyahmed.pymk.orchestrator.ServingPipeline pipeline;

    private final PymkService pymkService;

    public PymkController(PymkService pymkService) {
        this.pymkService = pymkService;
    }

    @GetMapping("/{memberId}")
    @Operation(summary = "Get people a member may know",
            description = "Configured L1, L2 or RERANK pipeline. X-PYMK-Scorer identifies the active mode. "
                    + "score is raw L1 acceptance probability in L1 mode, or the heavy invite_accepted_probability in L2 mode. "
                    + "RERANK blends both heavy heads using normalized weights and caps each known employer at max(1,floor(capFraction*requestedLimit)). "
                    + "Blank employers are exempt. Results may underfill; exposure reports realized counts and maximum fraction. "
                    + "RERANK display order is descending blend score after filtering, with ascending candidate-ID ties. "
                    + "L2 ties use ascending candidate ID; scores are not calibrated acceptance-rate or protected-attribute fairness claims. No automatic failure fallback.")
    @ApiResponse(responseCode = "200", description = "Recommendations generated",
            headers = @io.swagger.v3.oas.annotations.headers.Header(name = "X-PYMK-Scorer",
                    description = "Active scoring mode; identifies score semantics",
                    schema = @Schema(type = "string", allowableValues = {"L1", "L2", "RERANK"})))
    @ApiResponse(responseCode = "400", description = "Member ID or limit is invalid",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No member with that ID",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Candidate generation, release compatibility, feature loading, L1 or L2 scoring failed",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    public PymkResponse getRecommendations(
            @Parameter(description = "Positive member ID", example = "42")
            @Positive @PathVariable long memberId,
            @Parameter(description = "Result count from 1 to 100", example = "20")
            @Min(1) @Max(RecommendationOrchestrator.MAX_RESULTS)
            @RequestParam(defaultValue = "20") int limit,
            jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("X-PYMK-Scorer", pipeline.mode());
        return pymkService.getRecommendations(memberId, limit);
    }
}
