package com.ahdyahmed.pymk.reranker;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

/** Shared with the Python tuner: offline selection must preserve online policy semantics. */
class BlendParityTest {
    @Test void matchesSharedOfflinePolicyFixture() throws Exception {
        try (var input=getClass().getResourceAsStream("/blend-parity.json")) {
            var fixture=new ObjectMapper().readTree(input);
            for(var test:fixture) {
                var policy=new BlendDiversityPolicy(test.path("sentWeight").asDouble(),test.path("acceptedWeight").asDouble(),test.path("capFraction").asDouble());
                var pool=new ArrayList<BlendDiversityPolicy.Candidate>();
                for(var row:test.path("rows")) pool.add(new BlendDiversityPolicy.Candidate(row.path("id").asLong(),
                        policy.blend(row.path("sent").asDouble(),row.path("accepted").asDouble()),
                        row.path("employer").isNull()?null:row.path("employer").asText()));
                var result=policy.select(pool,test.path("limit").asInt());
                var expected=new ArrayList<Long>();
                test.path("expectedIds").forEach(id -> expected.add(id.asLong()));
                assertThat(result.candidateIds()).as(test.path("name").asText()).isEqualTo(expected);
                assertThat(result.exposure().knownGroupCap()).isEqualTo(test.path("expectedCap").asInt());
            }
        }
    }
}
