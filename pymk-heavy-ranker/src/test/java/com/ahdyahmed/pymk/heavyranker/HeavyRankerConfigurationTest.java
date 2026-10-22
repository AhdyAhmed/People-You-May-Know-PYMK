package com.ahdyahmed.pymk.heavyranker;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import com.ahdyahmed.pymk.domain.service.ServingReleaseStore;
import com.ahdyahmed.pymk.featurestore.PymkPairFeatureRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

class HeavyRankerConfigurationTest {
    @TempDir Path directory;
    final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(HeavyRankerConfiguration.class)
            .withBean(ObjectMapper.class,ObjectMapper::new)
            .withBean(ServingReleaseStore.class,() -> mock(ServingReleaseStore.class))
            .withBean(JdbcTemplate.class,() -> mock(JdbcTemplate.class))
            .withBean(PymkPairFeatureRepository.class,() -> mock(PymkPairFeatureRepository.class));
    @Test void disabledByDefaultNeedsNoArtifact() {
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(HeavyRankerModel.class).doesNotHaveBean(L2HeavyRanker.class));
    }
    @Test void enabledMissingArtifactFailsStartup() {
        runner.withPropertyValues("pymk.heavy-ranker.enabled=true","pymk.heavy-ranker.model-path="+directory.resolve("missing.onnx"))
                .run(context -> assertThat(context).hasFailed());
    }
    @Test void enabledLoadsRealModelAndClosesItOnShutdown() throws Exception {
        var f = OnnxHeavyRankerModelTest.fixture();
        Path model = directory.resolve("model.onnx"), metadata = directory.resolve("metadata.json");
        Files.write(model,OnnxHeavyRankerModelTest.bytes(f)); Files.writeString(metadata,f.path("metadata").toString());
        var reference = new AtomicReference<HeavyRankerModel>();
        runner.withPropertyValues("pymk.heavy-ranker.enabled=true","pymk.heavy-ranker.model-path="+model,"pymk.heavy-ranker.metadata-path="+metadata)
                .run(context -> { assertThat(context).hasNotFailed().hasSingleBean(L2HeavyRanker.class); reference.set(context.getBean(HeavyRankerModel.class)); });
        assertThatThrownBy(() -> reference.get().score(new float[0][])).hasMessageContaining("closed");
    }
}
