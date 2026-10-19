package com.ahdyahmed.pymk.lightranker;

import com.ahdyahmed.pymk.featurestore.PymkPairFeatureRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring wiring kept in the module so Day 21 only has to compose the pipeline. */
@Configuration(proxyBeanMethods = false)
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "pymk.light-ranker.serving-enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(LightRankerProperties.class)
public class LightRankerConfiguration {

    @Bean(destroyMethod = "close")
    @org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
    @ConditionalOnMissingBean(LightRankerModel.class)
    LightRankerModel lightRankerModel(
            LightRankerProperties properties,
            ObjectMapper objectMapper,
            com.ahdyahmed.pymk.domain.service.ServingReleaseStore releases,
            org.springframework.jdbc.core.JdbcTemplate jdbc) {
        properties.validate();
        return new ReleaseLightRankerModel(releases, jdbc, objectMapper, properties);
    }

    @Bean
    L1LightRanker l1LightRanker(
            PymkPairFeatureRepository featureRepository,
            LightRankerModel model,
            LightRankerProperties properties) {
        properties.validate();
        return new L1LightRanker(featureRepository, model, properties.getMaxCandidates());
    }
}
