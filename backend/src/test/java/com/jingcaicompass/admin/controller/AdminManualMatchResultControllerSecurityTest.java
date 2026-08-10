package com.jingcaicompass.admin.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jingcaicompass.admin.service.AdminManualMatchResultService;
import com.jingcaicompass.system.config.SecurityConfig;
import com.jingcaicompass.system.exception.ErrorCode;
import com.jingcaicompass.system.exception.GlobalExceptionHandler;
import com.jingcaicompass.system.infrastructure.TraceIdFilter;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminManualMatchResultController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, TraceIdFilter.class})
class AdminManualMatchResultControllerSecurityTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private AdminManualMatchResultService manualMatchResultService;
    @MockBean private JwtDecoder jwtDecoder;

    @Test
    void recordPathRequiresAdministratorJwt() throws Exception {
        mockMvc.perform(post("/api/admin/manual-results")
                        .contentType(MediaType.APPLICATION_JSON).content(validRequest()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.AUTH_UNAUTHORIZED.code()));

        when(jwtDecoder.decode("viewer-token")).thenReturn(jwt("VIEWER"));
        mockMvc.perform(post("/api/admin/manual-results")
                        .header("Authorization", "Bearer viewer-token")
                        .contentType(MediaType.APPLICATION_JSON).content(validRequest()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ACCESS_DENIED.code()));
    }

    @Test
    void administratorCanAccessRecordPath() throws Exception {
        when(jwtDecoder.decode("admin-token")).thenReturn(jwt("ADMIN"));
        when(manualMatchResultService.record(any(), any())).thenReturn(null);

        mockMvc.perform(post("/api/admin/manual-results")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON).content(validRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.SUCCESS.code()));
    }

    private String validRequest() {
        return """
                {"matchId":7,"factStatus":"FINAL","matchStatus":"FINISHED","homeScore":2,"awayScore":1,
                 "sourceNote":"人工来源","entryReason":"开发补录","confirmed":true}
                """;
    }

    private Jwt jwt(String role) {
        return Jwt.withTokenValue("token").header("alg", "HS256").subject("1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(1800))
                .claim("username", "admin").claim("role", role).build();
    }
}
