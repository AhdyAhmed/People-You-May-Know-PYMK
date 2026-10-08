package com.ahdyahmed.pymk.orchestrator;

import com.ahdyahmed.pymk.candidategen.MergedCandidate;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Redis-backed, versioned cache for the expensive L0 union and final response.
 * Redis and payload failures deliberately degrade to a cache miss.
 */
@Service
public class RecommendationCache {

    private static final Logger log = LoggerFactory.getLogger(RecommendationCache.class);
    private static final TypeReference<List<MergedCandidate>> CANDIDATE_LIST = new TypeReference<>() { };

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final RecommendationCacheProperties properties;

    public RecommendationCache(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            RecommendationCacheProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public Optional<List<MergedCandidate>> getCandidates(long memberId) {
        if (!properties.isEnabled()) {
            return Optional.empty();
        }
        return read(candidateKey(memberId), json -> List.copyOf(objectMapper.readValue(json, CANDIDATE_LIST)));
    }

    public void putCandidates(long memberId, List<MergedCandidate> candidates) {
        if (!properties.isEnabled()) {
            return;
        }
        write(candidateKey(memberId), List.copyOf(candidates), properties.getL0Ttl());
    }

    public Optional<RecommendationResult> getResult(long memberId) {
        if (!properties.isEnabled()) {
            return Optional.empty();
        }
        return read(resultKey(memberId), json -> objectMapper.readValue(json, RecommendationResult.class));
    }

    public void putResult(long memberId, RecommendationResult result) {
        if (!properties.isEnabled()) {
            return;
        }
        write(resultKey(memberId), result, properties.getResultTtl());
    }

    public void evictMembers(Collection<Long> memberIds) {
        if (!properties.isEnabled() || memberIds.isEmpty()) {
            return;
        }
        properties.validate();
        List<String> keys = memberIds.stream()
                .distinct()
                .flatMap(memberId -> List.of(candidateKey(memberId), resultKey(memberId)).stream())
                .toList();
        try {
            redis.delete(keys);
        } catch (DataAccessException ex) {
            log.warn("Redis cache invalidation failed; TTL remains the consistency backstop: {}", ex.getMessage());
        }
    }

    String candidateKey(long memberId) {
        return key("l0", memberId);
    }

    String resultKey(long memberId) {
        return key("result", memberId);
    }

    private String key(String layer, long memberId) {
        if (memberId <= 0) {
            throw new IllegalArgumentException("memberId must be positive");
        }
        properties.validate();
        return String.join(":",
                properties.getKeyPrefix(),
                "schema", properties.getSchemaVersion(),
                "pipeline", properties.getPipelineVersion(),
                "model", properties.getModelVersion(),
                layer, Long.toString(memberId));
    }

    private <T> Optional<T> read(String key, JsonReader<T> reader) {
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(reader.read(json));
        } catch (DataAccessException ex) {
            log.warn("Redis cache read failed for {}; continuing without cache: {}", key, ex.getMessage());
            return Optional.empty();
        } catch (JsonProcessingException ex) {
            log.warn("Discarding unreadable Redis cache entry {}: {}", key, ex.getOriginalMessage());
            deleteQuietly(key);
            return Optional.empty();
        }
    }

    private void write(String key, Object value, Duration ttl) {
        if (!properties.isEnabled()) {
            return;
        }
        properties.validate();
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (DataAccessException ex) {
            log.warn("Redis cache write failed for {}; continuing without cache: {}", key, ex.getMessage());
        } catch (JsonProcessingException ex) {
            log.warn("Redis cache serialization failed for {}; continuing without cache: {}",
                    key, ex.getOriginalMessage());
        }
    }

    private void deleteQuietly(String key) {
        try {
            redis.delete(key);
        } catch (DataAccessException ex) {
            log.debug("Could not remove unreadable Redis entry {}: {}", key, ex.getMessage());
        }
    }

    @FunctionalInterface
    private interface JsonReader<T> {
        T read(String json) throws JsonProcessingException;
    }
}
