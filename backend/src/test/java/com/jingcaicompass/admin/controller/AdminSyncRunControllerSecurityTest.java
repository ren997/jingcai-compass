package com.jingcaicompass.admin.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jingcaicompass.admin.service.AdminSyncRunQueryService;
import com.jingcaicompass.system.api.PageResult;
import com.jingcaicompass.system.config.SecurityConfig;
import com.jingcaicompass.system.exception.ErrorCode;
import com.jingcaicompass.system.exception.GlobalExceptionHandler;
import com.jingcaicompass.system.infrastructure.TraceIdFilter;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminSyncRunController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, TraceIdFilter.class})
class AdminSyncRunControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AdminSyncRunQueryService adminSyncRunQueryService;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void syncRunPathsRequireAdministratorJwt() throws Exception {
        mockMvc.perform(post("/api/admin/provider/sync-runs/list")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.AUTH_UNAUTHORIZED.code()));

        when(jwtDecoder.decode("viewer-token")).thenReturn(jwt("VIEWER"));
        mockMvc.perform(post("/api/admin/provider/sync-runs/list")
                        .header("Authorization", "Bearer viewer-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ACCESS_DENIED.code()));
    }

    @Test
    void administratorCanAccessSyncRunPath() throws Exception {
        when(jwtDecoder.decode("admin-token")).thenReturn(jwt("ADMIN"));
        when(adminSyncRunQueryService.list(any())).thenReturn(new PageResult<>(List.of(), 1, 20, 0));

        mockMvc.perform(post("/api/admin/provider/sync-runs/list")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.SUCCESS.code()));
    }

    private Jwt jwt(String role) {
        return Jwt.withTokenValue("token").header("alg", "HS256").subject("1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(1800))
                .claim("username", "admin").claim("role", role).build();
    }
}
