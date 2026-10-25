package com.ahdyahmed.pymk.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ahdyahmed.pymk.api.support.PostgresTestConfig;
import com.ahdyahmed.pymk.heavyranker.*;
import com.ahdyahmed.pymk.lightranker.LightRankerProperties;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "pymk.heavy-ranker.enabled=true", "pymk.serving.ranker=RERANK",
        "pymk.reranker.sent-weight=2", "pymk.reranker.accepted-weight=6"})
@Import(PostgresTestConfig.class)
class RerankerHttpTest {
    @DynamicPropertySource static void paths(DynamicPropertyRegistry registry) throws Exception {
        HeavyRankerIntegrationTest.paths(registry);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired LightRankerProperties lightProperties;
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper mapper;
    @Autowired StringRedisTemplate redis;
    @MockitoSpyBean L2HeavyRanker heavy;
    private HeavyPipelineHttpTest fixture;

    @BeforeEach void seed() throws Exception {
        fixture = new HeavyPipelineHttpTest();
        fixture.jdbc=jdbc; fixture.lightProperties=lightProperties; fixture.redis=redis; fixture.heavy=heavy;
        fixture.seed();
    }
    @AfterEach void clean() { fixture.clean(); }

    JsonNode request(int limit) throws Exception {
        var response = http.getForEntity("/api/v1/pymk/870001?limit="+limit,String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("X-PYMK-Scorer")).isEqualTo("RERANK");
        return mapper.readTree(response.getBody());
    }

    @Test void realNativeBlendAndLimitSpecificSelectionReuseOnePool() throws Exception {
        for (int limit : new int[]{1,2,20,100,2}) {
            var result = request(limit);
            int expected = limit <= 2 ? 1 : 2;
            assertThat(result.path("recommendations").size()).isEqualTo(expected);
            assertThat(result.path("exposure").path("returnedSize").asInt()).isEqualTo(expected);
            assertThat(result.path("exposure").path("knownGroupCap").asInt()).isEqualTo(Math.max(1,(int)Math.floor(.4*limit)));
            for(var row : result.path("recommendations")) {
                var heads=HeavyRankerIntegrationTest.fixture.path("expected").get(row.path("candidateId").asInt()-870002);
                assertThat(row.path("score").asDouble()).isCloseTo(.25*heads.get(0).asDouble()+.75*heads.get(1).asDouble(),within(1e-6));
            }
        }
        verify(heavy,times(1)).score(eq(870001L),anyList());
        assertThat(redis.keys("*:scored-pool-v1:*:scorer:day29-*" )).hasSize(1);
        assertThat(redis.keys("*:result:*" )).isEmpty();
    }

    @Test void poolBeyond100BackfillsAndBlankEmployersAreNotAnArtificialGroup() throws Exception {
        jdbc.update("UPDATE members SET school='Shared'");
        for(int i=0;i<240;i++) {
            long id=871000L+i;
            jdbc.update("INSERT INTO members(id,full_name,company,school,created_at) VALUES (?, 'Pool member',?,'Shared','2026-01-01')",id,i<120 ? " Acme " : i<180 ? "Beta" : "Gamma");
            jdbc.update("INSERT INTO pymk_release_features SELECT release_id,member_id,?,mutual_connection_count,same_company,same_school,same_geo,embedding_cosine_sim,profile_view_count_last_30d,feature_as_of,computed_at FROM pymk_release_features WHERE release_id='http-heavy' AND candidate_id=870002",id);
        }
        request(1);
        var full=request(100);
        assertThat(full.path("recommendations").size()).isEqualTo(100);
        var groups=full.path("exposure").path("knownGroupCounts");
        assertThat(groups.size()).isEqualTo(3);
        groups.forEach(count -> assertThat(count.asInt()).isLessThanOrEqualTo(40));
        verify(heavy,times(1)).score(eq(870001L),argThat(rows -> rows.size()==242));
        jdbc.update("UPDATE members SET company=null"); // Source revision invalidates cached employer groups.
        var blank=request(100);
        assertThat(blank.path("recommendations").size()).isEqualTo(100);
        assertThat(blank.path("exposure").path("unknownGroupCount").asInt()).isEqualTo(100);
        assertThat(blank.path("exposure").path("knownGroupCounts").size()).isZero();
        verify(heavy,times(2)).score(eq(870001L),anyList());
    }

    @Test void warmPoolCannotBypassCompatibilityAndFailedInferenceCannotCachePool() throws Exception {
        request(2);
        jdbc.update("UPDATE pymk_embedding_transforms SET transform_sha256=?","b".repeat(64));
        assertThat(http.getForEntity("/api/v1/pymk/870001",String.class).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        jdbc.update("UPDATE pymk_embedding_transforms SET transform_sha256=?",HeavyRankerIntegrationTest.fixture.path("metadata").path("embeddingCompatibility").path("transformSha256").asText());
        fixture.flush();
        doThrow(new HeavyRankerException("failed inference")).when(heavy).score(eq(870001L),anyList());
        assertThat(http.getForEntity("/api/v1/pymk/870001",String.class).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(redis.keys("*:scored-pool-v1:*" )).isEmpty();
    }

    @Test void connectionMutationExcludesCandidateFromWarmPool() throws Exception {
        request(20);
        assertThat(http.postForEntity("/api/v1/connections",Map.of("memberId",870001,"connectedMemberId",870002),String.class).getStatusCode().is2xxSuccessful()).isTrue();
        var result=request(20).path("recommendations");
        assertThat(result.size()).isEqualTo(1);
        assertThat(result.get(0).path("candidateId").asLong()).isEqualTo(870003L);
    }

    @Test void emptyPoolReportsZeroExposureAndDoesNotInvokeHeavyInference() throws Exception {
        jdbc.update("INSERT INTO members(id,full_name,created_at) VALUES (880000,'Isolated member','2026-01-01')");
        for(int limit : new int[]{1,100}) {
            var response=http.getForEntity("/api/v1/pymk/880000?limit="+limit,String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            var result=mapper.readTree(response.getBody());
            assertThat(result.path("recommendations").size()).isZero();
            assertThat(result.path("exposure").path("returnedSize").asInt()).isZero();
            assertThat(result.path("exposure").path("maxKnownGroupFraction").asDouble()).isZero();
        }
        verify(heavy,never()).score(eq(880000L),anyList());
    }
}
