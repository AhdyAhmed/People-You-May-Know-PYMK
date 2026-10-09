package com.ahdyahmed.pymk.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ahdyahmed.pymk.api.support.PostgresTestConfig;
import com.ahdyahmed.pymk.domain.entity.Member;
import com.ahdyahmed.pymk.domain.repository.ConnectionRepository;
import com.ahdyahmed.pymk.domain.repository.MemberRepository;
import com.ahdyahmed.pymk.domain.service.ConnectionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Day 13 end-to-end quality gate for recommendation safety and baseline relevance. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
class RecommendationQualitySmokeTest {

    private static final long REQUESTER = 820_001L;
    private static final long FRIEND_ONE = 820_002L;
    private static final long FRIEND_TWO = 820_003L;
    private static final long TWO_MUTUALS = 820_004L;
    private static final long ONE_MUTUAL = 820_005L;
    private static final long PROFILE_MATCH = 820_006L;
    private static final long CONNECTED_PROFILE_MATCH = 820_007L;
    private static final long UNRELATED = 820_008L;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired MemberRepository members;
    @Autowired ConnectionRepository connections;
    @Autowired ConnectionService connectionService;
    @Autowired StringRedisTemplate redis;

    @BeforeEach
    void seedSyntheticQaGraph() {
        flushRedis();
        Instant joinedAt = Instant.parse("2026-01-15T10:00:00Z");
        members.saveAll(List.of(
                member(REQUESTER, "Requester", "Acme", "Cairo University", "Cairo", joinedAt),
                member(FRIEND_ONE, "Friend One", "Other", "Other", "Giza", joinedAt),
                member(FRIEND_TWO, "Friend Two", "Other", "Other", "Giza", joinedAt),
                member(TWO_MUTUALS, "Strong Candidate", "Acme", "Cairo University", "Cairo", joinedAt),
                member(ONE_MUTUAL, "Graph Candidate", "Other", "Other", "Alexandria", joinedAt),
                member(PROFILE_MATCH, "Profile Candidate", "Acme", "Other", "Alexandria", joinedAt),
                member(CONNECTED_PROFILE_MATCH, "Connected Coworker", "Acme", "Other", "Cairo", joinedAt),
                member(UNRELATED, "Unrelated Member", "Unrelated", "Unrelated", "Aswan", joinedAt)));

        connectionService.connect(REQUESTER, FRIEND_ONE, joinedAt);
        connectionService.connect(REQUESTER, FRIEND_TWO, joinedAt);
        connectionService.connect(REQUESTER, CONNECTED_PROFILE_MATCH, joinedAt);
        connectionService.connect(FRIEND_ONE, TWO_MUTUALS, joinedAt);
        connectionService.connect(FRIEND_TWO, TWO_MUTUALS, joinedAt);
        connectionService.connect(FRIEND_ONE, ONE_MUTUAL, joinedAt);
    }

    @AfterEach
    void cleanFixture() {
        flushRedis();
        connections.deleteAll();
        members.deleteAll();
    }

    @Test
    void recommendationsRespectAllSafetyInvariantsAcrossSupportedLimits() throws Exception {
        for (int limit : List.of(1, 2, 5, 20, 100)) {
            assertInvariants(REQUESTER, limit, request(REQUESTER, limit));
        }
    }

    @Test
    void handfulOfSyntheticMembersPassTheEndToEndSanityCheck() throws Exception {
        for (long memberId : List.of(REQUESTER, FRIEND_ONE, FRIEND_TWO, TWO_MUTUALS)) {
            assertInvariants(memberId, 20, request(memberId, 20));
        }
    }

    @Test
    void mutualHeavyAndProfileRelevantCandidatesProduceSensibleBaselineResults() throws Exception {
        JsonNode recommendations = request(REQUESTER, 100).path("recommendations");

        assertThat(candidateIds(recommendations))
                .containsExactly(TWO_MUTUALS, ONE_MUTUAL, PROFILE_MATCH)
                .doesNotContain(CONNECTED_PROFILE_MATCH, UNRELATED);
        assertThat(recommendations.get(0).path("mutualConnectionCount").asLong()).isEqualTo(2);
        assertThat(reasons(recommendations.get(0)))
                .contains("2 mutual connections", "Same company: Acme", "Same school: Cairo University");
        assertThat(recommendations.get(1).path("mutualConnectionCount").asLong()).isEqualTo(1);
        assertThat(reasons(recommendations.get(2))).contains("Same company: Acme");
    }

    @Test
    void repeatedRequestsAreDeterministicIncludingTheCachedPath() throws Exception {
        String first = request(REQUESTER, 20).toString();
        String cached = request(REQUESTER, 20).toString();

        assertThat(cached).isEqualTo(first);
    }

    private JsonNode request(long memberId, int limit) throws Exception {
        String json = mvc.perform(get("/api/v1/pymk/{memberId}", memberId)
                        .queryParam("limit", Integer.toString(limit)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(json);
    }

    private void assertInvariants(long memberId, int limit, JsonNode response) {
        JsonNode recommendations = response.path("recommendations");
        List<Long> candidateIds = candidateIds(recommendations);
        Set<Long> directConnections = Set.copyOf(connections.findConnectedMemberIds(memberId));

        assertThat(response.path("memberId").asLong()).isEqualTo(memberId);
        assertThat(candidateIds).hasSizeLessThanOrEqualTo(limit);
        assertThat(candidateIds).doesNotContain(memberId);
        assertThat(candidateIds).doesNotContainAnyElementsOf(directConnections);
        assertThat(new HashSet<>(candidateIds)).hasSameSizeAs(candidateIds);
        assertThat(members.findAllById(candidateIds)).hasSameSizeAs(candidateIds);

        long previousMutualCount = Long.MAX_VALUE;
        for (JsonNode recommendation : recommendations) {
            long mutualCount = recommendation.path("mutualConnectionCount").asLong();
            assertThat(mutualCount).isLessThanOrEqualTo(previousMutualCount);
            previousMutualCount = mutualCount;
        }
    }

    private static List<Long> candidateIds(JsonNode recommendations) {
        return recommendations.valueStream()
                .map(node -> node.path("candidateId").asLong())
                .toList();
    }

    private static List<String> reasons(JsonNode recommendation) {
        return recommendation.path("reasons").valueStream()
                .map(JsonNode::asText)
                .toList();
    }

    private void flushRedis() {
        redis.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    private static Member member(
            long id, String name, String company, String school, String geoRegion, Instant createdAt) {
        return new Member(id, name, null, company, school, geoRegion, createdAt);
    }
}
