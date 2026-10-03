package com.ahdyahmed.pymk.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ahdyahmed.pymk.api.support.PostgresTestConfig;
import com.ahdyahmed.pymk.domain.entity.Member;
import com.ahdyahmed.pymk.domain.repository.ConnectionRepository;
import com.ahdyahmed.pymk.domain.repository.MemberRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** An M1 smoke journey through the public API and the real migrated database. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
@Transactional
class ApiEndToEndTest {

    private static final long MEMBER_ID = 700_001L;
    private static final long CANDIDATE_ID = 700_002L;

    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;
    @Autowired ConnectionRepository connections;

    @Test
    void serviceStatusMemberLookupAndConnectionCreationWorkTogether() throws Exception {
        Instant joinedAt = Instant.parse("2026-01-15T10:00:00Z");
        members.save(new Member(MEMBER_ID, "Ada Lovelace", "Engineer", "Acme", "MIT", "Cairo", joinedAt));
        members.save(new Member(CANDIDATE_ID, "Grace Hopper", "Admiral", "Navy", "Yale", "NYC", joinedAt));

        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("pymk-api"))
                .andExpect(jsonPath("$.status").value("up"))
                .andExpect(jsonPath("$.milestone").value("M1 - Day 7: foundation complete"));

        mvc.perform(get("/api/v1/members/{id}", MEMBER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connectionCount").value(0));

        mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"memberId":700001,"connectedMemberId":700002}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.created").value(true));

        mvc.perform(get("/api/v1/members/{id}", MEMBER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connectionCount").value(1));
        assertThat(connections.existsByMemberIdAndConnectedMemberId(CANDIDATE_ID, MEMBER_ID)).isTrue();
    }
}
