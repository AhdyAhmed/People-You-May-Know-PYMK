package com.ahdyahmed.pymk.api;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal placeholder endpoint so {@code docker compose up} + {@code mvn
 * spring-boot:run} has something to verify against on Day 1. Replaced by the
 * real PYMK endpoints starting Day 6 (see PYMK_ROADMAP.md).
 */
@RestController
public class StatusController {

    @GetMapping("/")
    public Map<String, String> status() {
        return Map.of(
                "service", "pymk-api",
                "status", "up",
                "milestone", "M1 - Day 1: repo & environment setup"
        );
    }
}
