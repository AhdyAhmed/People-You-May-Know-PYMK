package com.ahdyahmed.pymk.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Entry point for the pymk-api module.
 *
 * <p>{@code scanBasePackages} only covers Spring components. JPA entities and
 * Spring Data repositories live in other modules (pymk-domain), and are
 * discovered through {@code @EntityScan} / {@code @EnableJpaRepositories}
 * instead, so both are pointed at the shared root package.</p>
 */
@SpringBootApplication(scanBasePackages = "com.ahdyahmed.pymk")
@EntityScan(basePackages = "com.ahdyahmed.pymk")
@EnableJpaRepositories(basePackages = "com.ahdyahmed.pymk")
public class PymkApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(PymkApiApplication.class, args);
    }
}
