package com.ahdyahmed.pymk.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
