package com.ahdyahmed.pymk.api.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ahdyahmed.pymk.api.support.PostgresTestConfig;
import com.ahdyahmed.pymk.domain.entity.Member;
import com.ahdyahmed.pymk.domain.repository.MemberRepository;
import com.ahdyahmed.pymk.domain.service.ConnectionService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** {@code GET /api/v1/members/{id}} end to end (MockMvc -> service -> real Postgres). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
@Transactional // each test rolls back its seed data
class MemberControllerTest {

    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;
    @Autowired ConnectionService connectionService;

    @Test
    void returnsMemberWithConnectionCount() throws Exception {
        Instant now = Instant.parse("2026-01-15T10:00:00Z");
        members.save(new Member(900_001L, "Ada Lovelace", "Engineer", "Acme", "MIT", "Cairo", now));
        members.save(new Member(900_002L, "Grace Hopper", "Admiral", "Navy", "Yale", "NYC", now));
        members.save(new Member(900_003L, "Alan Turing", "Mathematician", "GCHQ", "Cambridge", "London", now));
        connectionService.connect(900_001L, 900_002L, now);
        connectionService.connect(900_001L, 900_003L, now);

        mvc.perform(get("/api/v1/members/900001"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(900001))
                .andExpect(jsonPath("$.fullName").value("Ada Lovelace"))
                .andExpect(jsonPath("$.headline").value("Engineer"))
                .andExpect(jsonPath("$.company").value("Acme"))
                .andExpect(jsonPath("$.school").value("MIT"))
                .andExpect(jsonPath("$.geoRegion").value("Cairo"))
                .andExpect(jsonPath("$.connectionCount").value(2))
                .andExpect(jsonPath("$.createdAt").value("2026-01-15T10:00:00Z"));
    }

    @Test
    void memberWithNoConnectionsHasZeroCount() throws Exception {
        members.save(new Member(900_010L, "Solo", null, null, null, null, Instant.now()));

        mvc.perform(get("/api/v1/members/900010"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connectionCount").value(0));
    }

    @Test
    void unknownMemberIsProblemDetail404() throws Exception {
        mvc.perform(get("/api/v1/members/999999999"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Member not found"))
                .andExpect(jsonPath("$.memberId").value(999999999));
    }

    @Test
    void nonNumericIdIsProblemDetail400() throws Exception {
        mvc.perform(get("/api/v1/members/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
    }
}
