package com.ahdyahmed.pymk.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ahdyahmed.pymk.api.support.PostgresTestConfig;
import com.ahdyahmed.pymk.domain.entity.Member;
import com.ahdyahmed.pymk.domain.repository.ConnectionRepository;
import com.ahdyahmed.pymk.domain.repository.MemberRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/** {@code POST /api/v1/connections} end to end (MockMvc -> service -> real Postgres). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
@Transactional // each test rolls back its seed data
class ConnectionControllerTest {

    private static final long A = 800_001L;
    private static final long B = 800_002L;

    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;
    @Autowired ConnectionRepository connections;

    @BeforeEach
    void seed() {
        Instant now = Instant.parse("2026-01-15T10:00:00Z");
        members.save(new Member(A, "Ada Lovelace", null, "Acme", null, null, now));
        members.save(new Member(B, "Grace Hopper", null, "Navy", null, null, now));
    }

    private ResultActions postConnection(String json) throws Exception {
        return mvc.perform(post("/api/v1/connections")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    @Test
    void createsBothDirectionsAndReturns201() throws Exception {
        postConnection("{\"memberId\":800001,\"connectedMemberId\":800002}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.memberId").value(A))
                .andExpect(jsonPath("$.connectedMemberId").value(B))
                .andExpect(jsonPath("$.created").value(true));

        assertThat(connections.existsByMemberIdAndConnectedMemberId(A, B)).isTrue();
        assertThat(connections.existsByMemberIdAndConnectedMemberId(B, A)).isTrue();

        // Visible through the Part 1 endpoint, for both members.
        mvc.perform(get("/api/v1/members/" + A)).andExpect(jsonPath("$.connectionCount").value(1));
        mvc.perform(get("/api/v1/members/" + B)).andExpect(jsonPath("$.connectionCount").value(1));
    }

    @Test
    void repeatingTheRequestIsIdempotent() throws Exception {
        String body = "{\"memberId\":800001,\"connectedMemberId\":800002}";
        postConnection(body).andExpect(status().isCreated());

        // Same pair again, and the reverse direction: both are no-ops.
        postConnection(body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(false));
        postConnection("{\"memberId\":800002,\"connectedMemberId\":800001}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(false));

        assertThat(connections.countByMemberId(A)).isEqualTo(1);
        assertThat(connections.countByMemberId(B)).isEqualTo(1);
    }

    @Test
    void selfConnectionIs400() throws Exception {
        postConnection("{\"memberId\":800001,\"connectedMemberId\":800001}")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid connection request"));
    }

    @Test
    void unknownMemberIs404() throws Exception {
        postConnection("{\"memberId\":800001,\"connectedMemberId\":999999999}")
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.memberId").value(999999999));
        postConnection("{\"memberId\":999999998,\"connectedMemberId\":800002}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.memberId").value(999999998));

        assertThat(connections.countByMemberId(A)).isZero();
    }

    @Test
    void missingFieldIs400WithFieldErrors() throws Exception {
        postConnection("{\"memberId\":800001}")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.connectedMemberId").exists());
    }

    @Test
    void nonPositiveIdIs400WithFieldErrors() throws Exception {
        postConnection("{\"memberId\":-5,\"connectedMemberId\":800002}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.memberId").exists());
    }

    @Test
    void malformedJsonIs400() throws Exception {
        postConnection("{not json")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
