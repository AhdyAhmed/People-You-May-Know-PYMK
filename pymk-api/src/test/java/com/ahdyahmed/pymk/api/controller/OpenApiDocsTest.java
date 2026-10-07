package com.ahdyahmed.pymk.api.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ahdyahmed.pymk.api.support.PostgresTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** Guards the OpenAPI wiring: spec is served and documents the real endpoints. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
class OpenApiDocsTest {

    @Autowired MockMvc mvc;

    @Test
    void specIsServedWithMetadataAndMemberEndpoint() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("PYMK API"))
                .andExpect(jsonPath("$.paths['/api/v1/members/{id}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/pymk/{memberId}'].get").exists());
    }

    @Test
    void swaggerUiEntryPointRedirects() throws Exception {
        mvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection());
    }
}
