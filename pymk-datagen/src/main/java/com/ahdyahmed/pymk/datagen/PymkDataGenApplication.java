package com.ahdyahmed.pymk.datagen;

import com.ahdyahmed.pymk.datagen.config.DataGenProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Entry point for the synthetic data generator (Day 4, PYMK_ROADMAP.md).
 *
 * <p>JPA/Hibernate autoconfiguration is excluded on purpose: this module
 * writes with raw JDBC batches for bulk-insert throughput, and has no
 * entities of its own to manage. Flyway still runs (it only needs a
 * DataSource), so the schema is always migrated before seeding starts.</p>
 */
@SpringBootApplication(exclude = HibernateJpaAutoConfiguration.class)
@EnableConfigurationProperties(DataGenProperties.class)
public class PymkDataGenApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(PymkDataGenApplication.class, args);
        System.exit(SpringApplication.exit(context));
    }
}
