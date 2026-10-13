package com.ahdyahmed.pymk.batch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Standalone entry point for PYMK offline jobs. */
@SpringBootApplication(scanBasePackages = "com.ahdyahmed.pymk")
@EntityScan(basePackages = "com.ahdyahmed.pymk")
@EnableJpaRepositories(basePackages = "com.ahdyahmed.pymk")
public class PymkBatchApplication {

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(PymkBatchApplication.class, args)));
    }
}
