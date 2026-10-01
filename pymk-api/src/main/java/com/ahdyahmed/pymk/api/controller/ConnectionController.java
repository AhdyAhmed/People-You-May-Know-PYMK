package com.ahdyahmed.pymk.api.controller;

import com.ahdyahmed.pymk.api.dto.ConnectionResponse;
import com.ahdyahmed.pymk.api.dto.CreateConnectionRequest;
import com.ahdyahmed.pymk.api.service.ConnectionRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/connections")
@Tag(name = "Connections", description = "Create connections between members")
public class ConnectionController {

    private final ConnectionRequestService service;

    public ConnectionController(ConnectionRequestService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Connect two members",
            description = "Simulates accepting a PYMK suggestion. Idempotent: connecting two members "
                    + "who are already connected returns 200 with created=false.")
    @ApiResponse(responseCode = "201", description = "Connection created")
    @ApiResponse(responseCode = "200", description = "Members were already connected")
    @ApiResponse(responseCode = "400", description = "Invalid request body or self-connection",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "One of the members does not exist",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Concurrent request created the same connection first",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ConnectionResponse> connect(@Valid @RequestBody CreateConnectionRequest request) {
        ConnectionResponse response = service.connect(request);
        return ResponseEntity.status(response.created() ? HttpStatus.CREATED : HttpStatus.OK).body(response);
    }
}
