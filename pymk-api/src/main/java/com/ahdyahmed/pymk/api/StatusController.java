package com.ahdyahmed.pymk.api;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lightweight service metadata endpoint for local smoke tests. */
@RestController
public class StatusController {
    private final com.ahdyahmed.pymk.orchestrator.ServingPipeline pipeline;

    public StatusController(com.ahdyahmed.pymk.orchestrator.ServingPipeline pipeline) { this.pipeline = pipeline; }

    @GetMapping("/")
    public Map<String, String> status() {
        return Map.of(
                "service", "pymk-api",
                "status", "up",
                "milestone", "M5 - Day 30: reproducible offline blend tuning and diversified serving",
                "scorer", pipeline.mode()
        );
    }
}
