package com.ahdyahmed.pymk.batch;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkflowBatchRunnerTest {
    @TempDir Path directory;

    @Test void rejectsLegacyWriterBeforeStartingApplication() throws Exception {
        rejects("{\"job\":\"featureComputationJob\"}", "Unsupported");
    }

    @Test void rejectsActivationBeforeStartingApplication() throws Exception {
        rejects("{\"job\":\"servingReleaseJob\",\"parameters\":{\"action\":\"activate\"}}", "immutable exports");
    }

    @Test void requiresExplicitSourceRevision() throws Exception {
        rejects("{\"job\":\"embeddingRefreshJob\",\"sourceRevision\":\"current\"}", "source revision");
    }

    @Test void approvedActivationRequiresExplicitRequestIntentEvenIfEnvironmentEnabled() throws Exception {
        rejects("{\"job\":\"servingReleaseJob\",\"approvedActivation\":false,\"parameters\":{\"action\":\"activate-approved\"}}", "immutable exports");
    }

    private void rejects(String json, String message) throws Exception {
        Path request = directory.resolve("request.json");
        Files.writeString(request, json);
        assertThatThrownBy(() -> WorkflowBatchRunner.main(new String[]{request.toString(), directory.resolve("receipt.json").toString()}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(message);
    }
}
