package com.ahdyahmed.pymk.featurestore;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Test-only bootstrap for the feature-store library module. */
@SpringBootApplication
@EntityScan(basePackages = "com.ahdyahmed.pymk")
@EnableJpaRepositories(basePackages = "com.ahdyahmed.pymk")
public class FeatureStoreTestApplication {
}
