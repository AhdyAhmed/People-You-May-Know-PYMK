package com.ahdyahmed.pymk.api;

import static org.assertj.core.api.Assertions.*;
import com.ahdyahmed.pymk.api.support.PostgresTestConfig;
import com.ahdyahmed.pymk.candidategen.*;
import com.ahdyahmed.pymk.heavyranker.*;
import com.ahdyahmed.pymk.lightranker.LightRankedCandidate;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

/** Real Spring transaction/release lock, PostgreSQL serving view and two-head ONNX runtime. */
@SpringBootTest(properties = "pymk.heavy-ranker.enabled=true")
@Import(PostgresTestConfig.class)
class HeavyRankerIntegrationTest {
    static JsonNode fixture;
    @DynamicPropertySource static void paths(DynamicPropertyRegistry registry) throws Exception {
        OnnxRecommendationIntegrationTest.ModelFixture.configureModelPaths(registry);
        fixture = new ObjectMapper().readTree(Files.readAllBytes(Path.of("../pymk-heavy-ranker/src/test/resources/heavy-serving-fixture.json")));
        Path dir = Files.createTempDirectory("pymk-heavy-integration-");
        Path model = dir.resolve("model.onnx"), metadata = dir.resolve("metadata.json");
        dir.toFile().deleteOnExit(); model.toFile().deleteOnExit(); metadata.toFile().deleteOnExit();
        Files.write(model, Base64.getDecoder().decode(fixture.path("modelBase64").asText()));
        Files.writeString(metadata, fixture.path("metadata").toString());
        registry.add("pymk.heavy-ranker.model-path", model::toString);
        registry.add("pymk.heavy-ranker.metadata-path", metadata::toString);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired L2HeavyRanker ranker;
    final Instant snapshot = Instant.parse("2026-02-01T00:00:00Z");

    @Test @Transactional void actualReleaseFeaturesProducePythonParityAndMissingRowsFailClosed() {
        var contract = fixture.path("metadata").path("embeddingCompatibility");
        jdbc.update("INSERT INTO members(id,full_name,created_at) VALUES (860001,'Requester',now()),(860002,'Candidate A',now()),(860003,'Candidate B',now())");
        jdbc.update("INSERT INTO pymk_embedding_transforms VALUES (?,?,?,?)", contract.path("embeddingVersion").asText(), contract.path("transformSha256").asText(), Timestamp.from(Instant.parse(contract.path("fitCutoff").asText())), new byte[]{1});
        jdbc.update("""
                INSERT INTO pymk_serving_releases(release_id,manifest_sha256,manifest,embedding_version,feature_as_of,source_fingerprint,state,model_version,model_bytes,model_metadata)
                VALUES ('heavy-test',?,'{}'::jsonb,?,?, 'fixture','READY','light-logreg-v1',?,?)
                """, "a".repeat(64), contract.path("embeddingVersion").asText(), Timestamp.from(snapshot), new byte[]{1}, new byte[]{1});
        jdbc.update("UPDATE pymk_active_release SET release_id='heavy-test',epoch=26");
        var candidates = new ArrayList<LightRankedCandidate>();
        for (int i = 0; i < 2; i++) {
            long id = 860002L + i;
            var values = fixture.path("features").get(i);
            jdbc.update("""
                    INSERT INTO pymk_release_features VALUES ('heavy-test',860001,?,?,?,?,?,?,?,?,?)
                    """, id, values.get(0).asInt(), values.get(1).asInt()==1, values.get(2).asInt()==1, values.get(3).asInt()==1, values.get(4).asDouble(), values.get(5).asInt(), Timestamp.from(snapshot), Timestamp.from(snapshot));
            candidates.add(new LightRankedCandidate(new MergedCandidate(id,.1,List.of(new CandidateHit(id,CandidateSourceType.HEURISTIC,1,Map.of()))),.5,values.get(0).asInt(),snapshot,"light-logreg-v1"));
        }
        var scores = ranker.score(860001, candidates);
        assertThat(scores).extracting(HeavyScoredCandidate::candidateId).containsExactly(860002L,860003L);
        for (int i = 0; i < 2; i++) {
            assertThat(scores.get(i).releaseEpoch()).isEqualTo(26);
            assertThat(scores.get(i).probabilities().inviteSent()).isCloseTo((float) fixture.path("expected").get(i).get(0).asDouble(),within(1e-6f));
            assertThat(scores.get(i).probabilities().inviteAccepted()).isCloseTo((float) fixture.path("expected").get(i).get(1).asDouble(),within(1e-6f));
        }
        jdbc.update("DELETE FROM pymk_release_features WHERE release_id='heavy-test' AND candidate_id=860003");
        assertThatThrownBy(() -> ranker.score(860001,candidates)).hasMessageContaining("Missing materialized");
    }
}
