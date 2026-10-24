package com.ahdyahmed.pymk.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ahdyahmed.pymk.api.support.PostgresTestConfig;
import com.ahdyahmed.pymk.heavyranker.*;
import com.ahdyahmed.pymk.lightranker.LightRankerProperties;
import com.fasterxml.jackson.databind.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"pymk.heavy-ranker.enabled=true","pymk.serving.ranker=L2"})
@Import(PostgresTestConfig.class)
class HeavyPipelineHttpTest {
    @DynamicPropertySource static void paths(DynamicPropertyRegistry registry) throws Exception {
        HeavyRankerIntegrationTest.paths(registry);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired LightRankerProperties lightProperties;
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper mapper;
    @Autowired StringRedisTemplate redis;
    @Autowired MeterRegistry metrics;
    @MockitoSpyBean L2HeavyRanker heavy;
    final Instant snapshot = Instant.parse("2026-02-01T00:00:00Z");

    void flush() { redis.execute((RedisCallback<Void>) c -> { c.serverCommands().flushDb(); return null; }); }

    @BeforeEach void seed() throws Exception {
        flush();
        var fixture = HeavyRankerIntegrationTest.fixture;
        var contract = fixture.path("metadata").path("embeddingCompatibility");
        jdbc.update("""
                INSERT INTO members(id,full_name,company,created_at) VALUES
                (870001,'Requester','Acme','2026-01-01'),(870002,'A','Acme','2026-01-01'),
                (870003,'B','Acme','2026-01-01'),(870004,'Already connected','Acme','2026-01-01')
                """);
        jdbc.update("INSERT INTO connections(member_id,connected_member_id) VALUES (870001,870004),(870004,870001)");
        jdbc.update("INSERT INTO pymk_embedding_transforms VALUES (?,?,?,?)",contract.path("embeddingVersion").asText(),contract.path("transformSha256").asText(),Timestamp.from(Instant.parse(contract.path("fitCutoff").asText())),new byte[]{1});
        jdbc.update("""
                INSERT INTO pymk_serving_releases(release_id,manifest_sha256,manifest,embedding_version,feature_as_of,source_fingerprint,state,model_version,model_bytes,model_metadata)
                VALUES ('http-heavy',?,'{"candidateLimits":{"graph":2000,"heuristic":1000,"embedding":1000}}'::jsonb,?,?,'fixture','READY','light-logreg-v1',?,?)
                ""","a".repeat(64),contract.path("embeddingVersion").asText(),Timestamp.from(snapshot),Files.readAllBytes(lightProperties.getModelPath()),Files.readAllBytes(lightProperties.getMetadataPath()));
        jdbc.update("UPDATE pymk_active_release SET release_id='http-heavy',epoch=epoch+1");
        for (int i=0;i<2;i++) {
            var f = fixture.path("features").get(i);
            jdbc.update("INSERT INTO pymk_release_features VALUES ('http-heavy',870001,?,?,?,?,?,?,?,?,?)",
                    870002L+i,f.get(0).asInt(),f.get(1).asInt()==1,f.get(2).asInt()==1,f.get(3).asInt()==1,
                    f.get(4).asDouble(),f.get(5).asInt(),Timestamp.from(snapshot),Timestamp.from(snapshot));
        }
    }

    @AfterEach void clean() {
        reset(heavy);
        flush();
        jdbc.update("UPDATE pymk_active_release SET release_id=null,epoch=epoch+1");
        jdbc.update("DELETE FROM pymk_release_features WHERE release_id='http-heavy'");
        jdbc.update("DELETE FROM pymk_serving_releases WHERE release_id='http-heavy'");
        jdbc.update("DELETE FROM pymk_embedding_transforms");
        jdbc.update("DELETE FROM connections");
        jdbc.update("DELETE FROM members");
    }

    JsonNode request(int limit) throws Exception {
        var result=http.getForEntity("/api/v1/pymk/870001?limit="+limit,String.class);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getHeaders().getFirst("X-PYMK-Scorer")).isEqualTo("L2");
        return mapper.readTree(result.getBody()).path("recommendations");
    }

    @Test void realHttpMatchesBothHeadArtifactAcceptanceAndCachesFullResult() throws Exception {
        var small=request(1);
        var full=request(100);
        assertThat(full.size()).isEqualTo(2);
        assertThat(small.get(0)).isEqualTo(full.get(0));
        for (var item:full) {
            long id=item.path("candidateId").asLong();
            assertThat(id).isIn(870002L,870003L);
            assertThat(item.path("score").asDouble()).isCloseTo(HeavyRankerIntegrationTest.fixture.path("expected").get((int)(id-870002)).get(1).asDouble(),within(1e-6));
        }
        verify(heavy,times(1)).score(eq(870001L),anyList());
        assertThat(redis.keys("*:scorer:day28-l2-accepted-v1-*" )).isNotEmpty();
        assertThat(http.getForEntity("/api/v1/pymk/999999",String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.getForEntity("/api/v1/pymk/870001?limit=101",String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test void missingFeaturesIncompatibleReleaseAndModelFailureReturn503WithoutPartialResult() throws Exception {
        request(2); // Even a warm result must not bypass release compatibility.
        jdbc.update("UPDATE pymk_embedding_transforms SET transform_sha256=?","b".repeat(64));
        assertThat(http.getForEntity("/api/v1/pymk/870001",String.class).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        jdbc.update("UPDATE pymk_embedding_transforms SET transform_sha256=?",HeavyRankerIntegrationTest.fixture.path("metadata").path("embeddingCompatibility").path("transformSha256").asText());
        flush();
        doThrow(new HeavyRankerException("native inference failed")).when(heavy).score(eq(870001L),anyList());
        assertThat(http.getForEntity("/api/v1/pymk/870001",String.class).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(redis.keys("*:result:*" )).isEmpty();
        reset(heavy);
        jdbc.update("DELETE FROM pymk_release_features WHERE candidate_id=870003");
        assertThat(http.getForEntity("/api/v1/pymk/870001",String.class).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        jdbc.update("UPDATE pymk_active_release SET release_id=null,epoch=epoch+1");
        assertThat(http.getForEntity("/api/v1/pymk/870001",String.class).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test void connectionMutationInvalidatesWarmResultNamespaceAndEligibility() throws Exception {
        request(2);
        var response=http.postForEntity("/api/v1/connections",Map.of("memberId",870001,"connectedMemberId",870002),String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        var after=request(2);
        assertThat(after.size()).isEqualTo(1);
        assertThat(after.get(0).path("candidateId").asLong()).isEqualTo(870003L);
    }

    @Test void recordsBoundedColdWarmLatencyEvidence() throws Exception {
        for(int i=0;i<5;i++) { flush(); request(2); } // JVM/model warm-up excluded.
        var report=mapper.createObjectNode();
        report.put("members",4).put("directedEdges",2).put("requester",870001).put("l1Survivors",2)
                .put("concurrency",1).put("requestsPerPhase",30).put("requestMix","GET limit=2; fixed member; localhost; cold Redis flush excluded from HTTP timing")
                .put("os",System.getProperty("os.name")).put("java",System.getProperty("java.version"))
                .put("processors",Runtime.getRuntime().availableProcessors())
                .put("processorIdentifier",Objects.toString(System.getenv("PROCESSOR_IDENTIFIER"),"unavailable"))
                .put("maxJvmHeapBytes",Runtime.getRuntime().maxMemory());
        for(String phase:List.of("cold","warm")) {
            metrics.find("pymk.pipeline.stage").timers().forEach(metrics::remove);
            double[] elapsed=new double[30];
            for(int i=0;i<30;i++) {
                if(phase.equals("cold")) flush();
                long start=System.nanoTime(); request(2); elapsed[i]=(System.nanoTime()-start)/1_000_000.0;
            }
            Arrays.sort(elapsed);
            var result=report.putObject(phase);
            result.putObject("httpMs").put("p50",elapsed[14]).put("p95",elapsed[28]).put("p99",elapsed[29]);
            for(String stage:List.of("l0","l1","l2","request")) {
                var timer=metrics.find("pymk.pipeline.stage").tag("stage",stage).tag("outcome","success").timer();
                var summary=result.putObject(stage);
                summary.put("calls",timer==null?0:timer.count());
                if(timer!=null) for(var value:timer.takeSnapshot().percentileValues()) summary.put("p"+Math.round(value.percentile()*100)+"Ms",value.value(TimeUnit.MILLISECONDS));
                if(phase.equals("warm") && !stage.equals("request")) assertThat(timer).isNull();
            }
        }
        Files.writeString(Path.of("target/day28-latency.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }
}
