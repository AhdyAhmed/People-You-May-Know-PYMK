package com.ahdyahmed.pymk.lightranker;

import com.ahdyahmed.pymk.featurestore.PymkPairFeatureRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring wiring kept in the module so Day 21 only has to compose the pipeline. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LightRankerProperties.class)
public class LightRankerConfiguration {

    @Bean(destroyMethod = "close")
    LightRankerModel lightRankerModel(
            LightRankerProperties properties,
            ObjectMapper objectMapper) {
        properties.validate();
        return OnnxLightRankerModel.load(
                properties.getModelPath(), properties.getMetadataPath(), objectMapper);
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
