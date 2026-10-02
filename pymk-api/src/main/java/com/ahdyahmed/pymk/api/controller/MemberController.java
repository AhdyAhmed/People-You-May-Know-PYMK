package com.ahdyahmed.pymk.api.controller;

import com.ahdyahmed.pymk.api.dto.MemberResponse;
import com.ahdyahmed.pymk.api.service.MemberService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/members")
@Tag(name = "Members", description = "Member profile lookups")
public class MemberController {

    private final MemberService memberService;

    public MemberController(MemberService memberService) {
        this.memberService = memberService;
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a member by ID",
            description = "Returns the member's profile fields plus their first-degree connection count.")
    @ApiResponse(responseCode = "200", description = "Member found")
    @ApiResponse(responseCode = "400", description = "ID is not a positive number",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No member with that ID",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    public MemberResponse getMember(
            @Parameter(description = "Positive member ID", example = "42")
            @Positive @PathVariable long id) {
        return memberService.getMember(id);
    }
}
