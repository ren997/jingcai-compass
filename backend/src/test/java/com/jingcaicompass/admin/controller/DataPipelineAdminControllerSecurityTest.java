package com.jingcaicompass.admin.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jingcaicompass.match.service.MatchNormalizationBackfillService;
import com.jingcaicompass.system.config.SecurityConfig;
import com.jingcaicompass.system.exception.ErrorCode;
import com.jingcaicompass.system.exception.GlobalExceptionHandler;
import com.jingcaicompass.system.infrastructure.TraceIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DataPipelineAdminController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, TraceIdFilter.class})
class DataPipelineAdminControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MatchNormalizationBackfillService normalizationBackfillService;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void backfillRequiresAdministratorAuthentication() throws Exception {
        mockMvc.perform(post("/api/admin/provider/pipeline/backfill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessDate":"2026-07-22"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.AUTH_UNAUTHORIZED.code()));
    }
}
