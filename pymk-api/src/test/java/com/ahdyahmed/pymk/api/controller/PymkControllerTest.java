package com.ahdyahmed.pymk.api.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ahdyahmed.pymk.api.support.PostgresTestConfig;
import com.ahdyahmed.pymk.domain.entity.Member;
import com.ahdyahmed.pymk.domain.repository.ConnectionRepository;
import com.ahdyahmed.pymk.domain.repository.MemberRepository;
import com.ahdyahmed.pymk.domain.service.ConnectionService;
import com.ahdyahmed.pymk.orchestrator.RecommendationCache;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.test.web.servlet.MockMvc;

/** Full Days 11–12 request path against PostgreSQL/pgvector and Redis. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
class PymkControllerTest {

    private static final long MEMBER = 810_001L;
    private static final long FRIEND_ONE = 810_002L;
    private static final long FRIEND_TWO = 810_003L;
    private static final long TWO_MUTUALS = 810_004L;
    private static final long ONE_MUTUAL = 810_005L;
    private static final long PROFILE_MATCH = 810_006L;

    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;
    @Autowired ConnectionRepository connections;
    @Autowired ConnectionService connectionService;
    @Autowired RecommendationCache cache;
    @Autowired StringRedisTemplate redis;

    @BeforeEach
    void clearCache() {
        redis.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    @AfterEach
    void cleanCommittedFixture() {
        clearCache();
        connections.deleteAll();
        members.deleteAll();
    }

    @Test
    void returnsEligibleCandidatesOrderedByMutualConnections() throws Exception {
        seedGraph();

        mvc.perform(get("/api/v1/pymk/{memberId}", MEMBER).queryParam("limit", "3"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.memberId").value(MEMBER))
                .andExpect(jsonPath("$.recommendations.length()").value(3))
                .andExpect(jsonPath("$.recommendations[0].candidateId").value(TWO_MUTUALS))
                .andExpect(jsonPath("$.recommendations[0].score").value(2.0))
                .andExpect(jsonPath("$.recommendations[0].mutualConnectionCount").value(2))
                .andExpect(jsonPath("$.recommendations[0].reasons[0]").value("2 mutual connections"))
                .andExpect(jsonPath("$.recommendations[1].candidateId").value(ONE_MUTUAL))
                .andExpect(jsonPath("$.recommendations[1].mutualConnectionCount").value(1))
                .andExpect(jsonPath("$.recommendations[2].candidateId").value(PROFILE_MATCH))
                .andExpect(jsonPath("$.recommendations[2].mutualConnectionCount").value(0));

        // Reuses the cached top-100 result and slices it for a different limit.
        mvc.perform(get("/api/v1/pymk/{memberId}", MEMBER).queryParam("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendations.length()").value(1))
                .andExpect(jsonPath("$.recommendations[0].candidateId").value(TWO_MUTUALS));
    }

    @Test
    void enforcesLimitAndRejectsInvalidRequests() throws Exception {
        seedGraph();

        mvc.perform(get("/api/v1/pymk/{memberId}", MEMBER).queryParam("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendations.length()").value(1));
        mvc.perform(get("/api/v1/pymk/{memberId}", MEMBER).queryParam("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(get("/api/v1/pymk/{memberId}", MEMBER).queryParam("limit", "101"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/pymk/0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownMemberIsProblemDetail404() throws Exception {
        mvc.perform(get("/api/v1/pymk/999999999"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Member not found"))
                .andExpect(jsonPath("$.memberId").value(999999999));
    }

    @Test
    void connectionCreationInvalidatesAffectedCachesAfterCommit() throws Exception {
        seedGraph();
        mvc.perform(get("/api/v1/pymk/{memberId}", MEMBER).queryParam("limit", "10"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/pymk/{memberId}", FRIEND_ONE).queryParam("limit", "10"))
                .andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(cache.getResult(MEMBER)).isPresent();
        org.assertj.core.api.Assertions.assertThat(cache.getResult(FRIEND_ONE)).isPresent();

        mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\":810001,\"connectedMemberId\":810005}"))
                .andExpect(status().isCreated());

        org.assertj.core.api.Assertions.assertThat(cache.getResult(MEMBER)).isEmpty();
        org.assertj.core.api.Assertions.assertThat(cache.getCandidates(MEMBER)).isEmpty();
        org.assertj.core.api.Assertions.assertThat(cache.getResult(FRIEND_ONE)).isEmpty();
        mvc.perform(get("/api/v1/pymk/{memberId}", MEMBER).queryParam("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendations[?(@.candidateId == 810005)]").isEmpty());
    }

    private void seedGraph() {
        Instant joinedAt = Instant.parse("2026-01-15T10:00:00Z");
        members.saveAll(List.of(
                member(MEMBER, "Requester", "Acme", joinedAt),
                member(FRIEND_ONE, "Friend One", "Other", joinedAt),
                member(FRIEND_TWO, "Friend Two", "Other", joinedAt),
                member(TWO_MUTUALS, "Two Mutuals", "Other", joinedAt),
                member(ONE_MUTUAL, "One Mutual", "Other", joinedAt),
                member(PROFILE_MATCH, "Profile Match", "Acme", joinedAt)));
        connectionService.connect(MEMBER, FRIEND_ONE, joinedAt);
        connectionService.connect(MEMBER, FRIEND_TWO, joinedAt);
        connectionService.connect(FRIEND_ONE, TWO_MUTUALS, joinedAt);
        connectionService.connect(FRIEND_TWO, TWO_MUTUALS, joinedAt);
        connectionService.connect(FRIEND_ONE, ONE_MUTUAL, joinedAt);
    }

    private static Member member(long id, String name, String company, Instant createdAt) {
        return new Member(id, name, null, company, null, null, createdAt);
    }
}
