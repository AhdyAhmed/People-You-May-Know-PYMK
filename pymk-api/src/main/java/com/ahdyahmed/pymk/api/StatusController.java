package com.ahdyahmed.pymk.api;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lightweight service metadata endpoint for local smoke tests. */
@RestController
public class StatusController {

    @GetMapping("/")
    public Map<String, String> status() {
        return Map.of(
                "service", "pymk-api",
                "status", "up",
                "milestone", "M1 - Day 6: API skeleton complete"
        );
    }
}
