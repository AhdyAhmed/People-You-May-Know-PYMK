package com.ahdyahmed.pymk.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RecommendationCacheTest {
    @Test void scoredPoolRoundTripAndInvalidPayloadsAreMisses() throws Exception {
        org.springframework.test.util.ReflectionTestUtils.setField(cache,"pipeline",new ServingPipeline("L1",null,null,500,500));
        var pool = new ScoredPool(42, java.util.stream.LongStream.rangeClosed(100,340)
                .mapToObj(id -> new ScoredPool.Row(new Recommendation(id,.5,0,List.of()), " Acme ")).toList());
        var mapper = new ObjectMapper();
        when(values.get(cache.poolKey(42))).thenReturn(mapper.writeValueAsString(pool));
        assertThat(cache.getPool(42)).contains(pool);
        cache.putPool(42,pool);
        verify(values).set(org.mockito.ArgumentMatchers.eq(cache.poolKey(42)),org.mockito.ArgumentMatchers.eq(mapper.writeValueAsString(pool)),any(Duration.class));
        for (String payload : List.of("null", "{invalid", "{\"memberId\":99,\"rows\":[]}",
                "{\"memberId\":42,\"rows\":[{\"recommendation\":{\"candidateId\":42,\"score\":0.5,\"mutualConnectionCount\":0,\"reasons\":[]}}]}",
                mapper.writeValueAsString(new ScoredPool(42,java.util.stream.LongStream.rangeClosed(100,600)
                        .mapToObj(id -> new ScoredPool.Row(new Recommendation(id,.5,0,List.of()), "")).toList())))) {
            when(values.get(cache.poolKey(42))).thenReturn(payload);
            assertThat(cache.getPool(42)).isEmpty();
        }
    }
    @Test
    void modeArtifactAndSourceRevisionIsolateCachesAndRevisionIsPinned() {
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT revision FROM pymk_source_revision",Long.class)).thenReturn(1L);
        org.springframework.test.util.ReflectionTestUtils.setField(cache,"jdbc",jdbc);
        org.springframework.test.util.ReflectionTestUtils.setField(cache,"pipeline",new ServingPipeline("L1",null,null,500,500));
        String l1 = cache.resultKey(42);
        var heavy = mock(com.ahdyahmed.pymk.heavyranker.L2HeavyRanker.class);
        var model = mock(com.ahdyahmed.pymk.heavyranker.HeavyRankerModel.class);
        when(model.artifactIdentity()).thenReturn("a".repeat(64));
        org.springframework.test.util.ReflectionTestUtils.setField(cache,"pipeline",new ServingPipeline("L2",heavy,model,500,500));
        String l2 = cache.resultKey(42);
        assertThat(l2).isNotEqualTo(l1);
        try (var scope = cache.pinNamespace()) {
            when(jdbc.queryForObject("SELECT revision FROM pymk_source_revision",Long.class)).thenReturn(2L);
            assertThat(cache.resultKey(42)).isEqualTo(l2);
        }
        assertThat(cache.resultKey(42)).isNotEqualTo(l2);
        org.springframework.test.util.ReflectionTestUtils.setField(cache,"pipeline",new ServingPipeline("L1",null,null,500,500));
        assertThat(cache.resultKey(42)).isNotEqualTo(l1);
    }

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private RecommendationCache cache;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        cache = new RecommendationCache(redis, new ObjectMapper(), new RecommendationCacheProperties());
    }

    @Test
    void redisReadAndWriteFailuresDegradeWithoutEscaping() {
        RedisConnectionFailureException unavailable = new RedisConnectionFailureException("offline");
        when(values.get(anyString())).thenThrow(unavailable);
        doThrow(unavailable).when(values).set(anyString(), anyString(), any(Duration.class));

        assertThat(cache.getResult(42)).isEmpty();
        assertThatCode(() -> cache.putResult(42, new RecommendationResult(42, List.of())))
                .doesNotThrowAnyException();
    }

    @Test
    void corruptPayloadIsRemovedAndTreatedAsMiss() {
        when(values.get(anyString())).thenReturn("{not-json");

        assertThat(cache.getResult(42)).isEmpty();

        verify(redis).delete(cache.resultKey(42));
    }

    @Test
    void nullAndWrongOwnerPayloadsAreEvictedInsteadOfServed() {
        for (String payload : List.of("null", "{\"memberId\":99,\"recommendations\":[]}")) {
            when(values.get(anyString())).thenReturn(payload);
            assertThat(cache.getResult(42)).isEmpty();
        }
        when(values.get(anyString())).thenReturn("null");
        assertThat(cache.getCandidates(42)).isEmpty();
        verify(redis, org.mockito.Mockito.times(2)).delete(cache.resultKey(42));
        verify(redis).delete(cache.candidateKey(42));
    }

    @Test
    void unsafeOrUnorderedResultsAreTreatedAsMisses() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (List<Recommendation> recommendations : List.of(
                List.of(new Recommendation(42, 0.8, 0, List.of())),
                List.of(new Recommendation(10, 0.8, 0, List.of()), new Recommendation(10, 0.7, 0, List.of())),
                List.of(new Recommendation(10, 0.3, 0, List.of()), new Recommendation(20, 0.8, 0, List.of())))) {
            when(values.get(anyString())).thenReturn(mapper.writeValueAsString(
                    new RecommendationResult(42, recommendations)));
            assertThat(cache.getResult(42)).isEmpty();
        }
    }

    @Test
    void cacheNamespaceMustMatchLoadedModelUnlessCacheIsDisabled() {
        assertThatCode(() -> cache.verifyModelVersion("light-logreg-v1")).doesNotThrowAnyException();
        assertThatThrownBy(() -> cache.verifyModelVersion("other-model"))
                .isInstanceOf(com.ahdyahmed.pymk.lightranker.LightRankerException.class);
        RecommendationCacheProperties properties = new RecommendationCacheProperties();
        properties.setEnabled(false);
        cache = new RecommendationCache(redis, new ObjectMapper(), properties);
        assertThatCode(() -> cache.verifyModelVersion("other-model")).doesNotThrowAnyException();
    }

    @Test
    void selfAndDuplicateCachedCandidatesAreEvicted() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var candidate = new com.ahdyahmed.pymk.candidategen.MergedCandidate(42, 0.5,
                List.of(new com.ahdyahmed.pymk.candidategen.CandidateHit(42,
                        com.ahdyahmed.pymk.candidategen.CandidateSourceType.HEURISTIC, 0.5, java.util.Map.of())));
        when(values.get(anyString())).thenReturn(mapper.writeValueAsString(List.of(candidate)));
        assertThat(cache.getCandidates(42)).isEmpty();
        when(values.get(anyString())).thenReturn(mapper.writeValueAsString(List.of(candidate, candidate)));
        assertThat(cache.getCandidates(99)).isEmpty();
        verify(redis).delete(cache.candidateKey(42));
        verify(redis).delete(cache.candidateKey(99));
    }

    @Test
    void disabledCacheNeverTouchesRedis() {
        RecommendationCacheProperties properties = new RecommendationCacheProperties();
        properties.setEnabled(false);
        cache = new RecommendationCache(redis, new ObjectMapper(), properties);

        assertThat(cache.getCandidates(42)).isEmpty();
        assertThatCode(() -> cache.putCandidates(42, List.of())).doesNotThrowAnyException();
        assertThatCode(() -> cache.evictMembers(List.of(42L))).doesNotThrowAnyException();
        org.mockito.Mockito.verifyNoInteractions(redis);
    }
}
