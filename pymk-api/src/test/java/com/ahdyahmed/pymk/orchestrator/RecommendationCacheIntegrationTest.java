package com.ahdyahmed.pymk.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ahdyahmed.pymk.api.support.PostgresTestConfig;
import com.ahdyahmed.pymk.api.PymkApiApplication;
import com.ahdyahmed.pymk.candidategen.CandidateHit;
import com.ahdyahmed.pymk.candidategen.CandidateSourceType;
import com.ahdyahmed.pymk.candidategen.MergedCandidate;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;

/** Real-Redis coverage for misses, JSON round trips, versioned keys, and TTL expiry. */
@SpringBootTest(classes = PymkApiApplication.class, properties = {
        "pymk.cache.l0-ttl=250ms",
        "pymk.cache.result-ttl=250ms"
})
@Import(PostgresTestConfig.class)
class RecommendationCacheIntegrationTest {

    private static final long MEMBER_ID = 920_001L;

    @Autowired RecommendationCache cache;
    @Autowired StringRedisTemplate redis;

    @BeforeEach
    @AfterEach
    void flushRedis() {
        redis.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    @Test
    void missThenRoundTripsBothStablePayloadsUnderVersionedKeys() {
        assertThat(cache.getCandidates(MEMBER_ID)).isEmpty();
        assertThat(cache.getResult(MEMBER_ID)).isEmpty();

        List<MergedCandidate> candidates = candidates();
        RecommendationResult result = new RecommendationResult(MEMBER_ID,
                List.of(new Recommendation(920_002L, 3.0, 3, List.of("3 mutual connections"))));
        cache.putCandidates(MEMBER_ID, candidates);
        cache.putResult(MEMBER_ID, result);

        assertThat(cache.getCandidates(MEMBER_ID)).contains(candidates);
        assertThat(cache.getResult(MEMBER_ID)).contains(result);
        assertThat(cache.candidateKey(MEMBER_ID))
                .isEqualTo("pymk:schema:v1:pipeline:l0-rrf-v1:model:naive-mutual-v1:l0:920001");
        assertThat(cache.resultKey(MEMBER_ID))
                .isEqualTo("pymk:schema:v1:pipeline:l0-rrf-v1:model:naive-mutual-v1:result:920001");
        assertThat(redis.getExpire(cache.candidateKey(MEMBER_ID), TimeUnit.MILLISECONDS)).isPositive();
        assertThat(redis.getExpire(cache.resultKey(MEMBER_ID), TimeUnit.MILLISECONDS)).isPositive();
    }

    @Test
    void bothLayersExpireAndBecomeMisses() {
        cache.putCandidates(MEMBER_ID, candidates());
        cache.putResult(MEMBER_ID, new RecommendationResult(MEMBER_ID, List.of()));

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertThat(cache.getCandidates(MEMBER_ID)).isEmpty();
            assertThat(cache.getResult(MEMBER_ID)).isEmpty();
        });
    }

    private static List<MergedCandidate> candidates() {
        CandidateHit hit = new CandidateHit(
                920_002L, CandidateSourceType.HEURISTIC, 1.0, Map.of("company", "Acme"));
        return List.of(new MergedCandidate(920_002L, 0.25, List.of(hit)));
    }
}
