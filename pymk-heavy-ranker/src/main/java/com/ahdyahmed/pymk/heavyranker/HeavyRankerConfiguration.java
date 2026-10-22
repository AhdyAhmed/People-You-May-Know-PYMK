package com.ahdyahmed.pymk.heavyranker;

import com.ahdyahmed.pymk.domain.service.ServingReleaseStore;
import com.ahdyahmed.pymk.featurestore.PymkPairFeatureRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Explicit opt-in loading only. No HTTP/orchestrator cutover before Day 28. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "pymk.heavy-ranker.enabled", havingValue = "true")
public class HeavyRankerConfiguration {
    @Bean(destroyMethod = "close")
    HeavyRankerModel heavyRankerModel(ObjectMapper mapper,
            @Value("${pymk.heavy-ranker.model-path:artifacts/models/heavy/heavy-mlp-v1.onnx}") Path model,
            @Value("${pymk.heavy-ranker.metadata-path:artifacts/models/heavy/heavy-mlp-v1.metadata.json}") Path metadata) {
        return OnnxHeavyRankerModel.load(model, metadata, mapper);
    }
    @Bean
    L2HeavyRanker l2HeavyRanker(ServingReleaseStore releases, JdbcTemplate jdbc, PymkPairFeatureRepository features,
            HeavyRankerModel model, @Value("${pymk.heavy-ranker.max-candidates:500}") int limit) {
        return new L2HeavyRanker(releases, jdbc, features, model, limit);
    }
}
