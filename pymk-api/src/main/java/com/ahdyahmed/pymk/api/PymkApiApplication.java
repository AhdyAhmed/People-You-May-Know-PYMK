package com.ahdyahmed.pymk.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the pymk-api module.
 *
 * <p>Day 1 goal: prove the multi-module build + Spring Boot toolchain work
 * end to end. Real endpoints ({@code GET /api/v1/members/{id}},
 * {@code POST /api/v1/connections}) land on Day 6 per PYMK_ROADMAP.md.</p>
 */
@SpringBootApplication(scanBasePackages = "com.ahdyahmed.pymk")
public class PymkApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(PymkApiApplication.class, args);
    }
}
