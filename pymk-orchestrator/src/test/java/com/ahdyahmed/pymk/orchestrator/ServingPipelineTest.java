package com.ahdyahmed.pymk.orchestrator;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ahdyahmed.pymk.heavyranker.*;
import org.junit.jupiter.api.Test;

class ServingPipelineTest {
    @Test void springWiringDefaultsToL1AndRejectsL2WithoutArtifacts() {
        var runner = new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(ServingPipeline.class);
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ServingPipeline.class).mode()).isEqualTo("L1");
        });
        runner.withPropertyValues("pymk.serving.ranker=L2").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("pymk.serving.ranker=RERANK").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("pymk.reranker.sent-weight=-1").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("pymk.reranker.employer-cap-fraction=0").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("pymk.serving.ranker=invalid").run(context -> assertThat(context).hasFailed());
    }

    @Test void rerankIdentityIncludesNormalizedWeightsCapBudgetAndArtifact() {
        var ranker=mock(L2HeavyRanker.class);
        var model=mock(HeavyRankerModel.class);
        when(model.artifactIdentity()).thenReturn("a".repeat(64));
        var base=new ServingPipeline("RERANK",ranker,model,500,500);
        assertThat(base.usesHeavyRanker()).isTrue();
        assertThat(base.usesReranker()).isTrue();
        assertThat(base.identity()).startsWith("day29-").isNotEqualTo(new ServingPipeline("L2",ranker,model,500,500).identity());
        assertThat(base.identity()).isNotEqualTo(new ServingPipeline("RERANK",ranker,model,500,500,
                new com.ahdyahmed.pymk.reranker.BlendDiversityPolicy(1,3,.4)).identity());
        assertThat(base.identity()).isNotEqualTo(new ServingPipeline("RERANK",ranker,model,500,500,
                new com.ahdyahmed.pymk.reranker.BlendDiversityPolicy(1,1,.5)).identity());
    }
    @Test void validatesOptInCapacityAndContentIdentity() {
        var ranker = mock(L2HeavyRanker.class);
        var model = mock(HeavyRankerModel.class);
        when(model.artifactIdentity()).thenReturn("a".repeat(64));
        var heavy = new ServingPipeline("L2",ranker,model,500,500);
        assertThat(heavy.identity()).contains("l2-accepted").contains("a".repeat(64));
        assertThat(new ServingPipeline("L1",null,null,500,500).usesHeavyRanker()).isFalse();
        assertThatThrownBy(() -> new ServingPipeline("unknown",null,null,500,500)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ServingPipeline("L2",null,null,500,500)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ServingPipeline("L2",ranker,model,501,500)).isInstanceOf(IllegalStateException.class);
        when(model.artifactIdentity()).thenReturn("b".repeat(64));
        assertThat(new ServingPipeline("L2",ranker,model,500,500).identity()).isNotEqualTo(heavy.identity());
        assertThat(new ServingPipeline("L2",ranker,model,200,500).identity()).isNotEqualTo(heavy.identity());
    }
}
