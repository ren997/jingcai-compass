package com.jingcaicompass.admin.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jingcaicompass.admin.enums.AdminRoleEnum;
import com.jingcaicompass.admin.service.AdminAuthService;
import com.jingcaicompass.admin.vo.AdminLoginVo;
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

@WebMvcTest(AdminAuthController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, TraceIdFilter.class})
class AdminAuthControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AdminAuthService adminAuthService;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void loginIsAnonymousAndReturnsBearerToken() throws Exception {
        Instant expiresAt = Instant.parse("2026-07-26T06:30:00Z");
        when(adminAuthService.login(any())).thenReturn(new AdminLoginVo(
                "signed-token",
                "Bearer",
                expiresAt,
                1L,
                "admin",
                AdminRoleEnum.ADMIN
        ));

        mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "admin",
                                  "password": "administrator-password"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.accessToken").value("signed-token"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.role").value("ADMIN"));
    }

    @Test
    void logoutUsesIdentityFromValidatedJwt() throws Exception {
        when(jwtDecoder.decode("admin-token")).thenReturn(Jwt.withTokenValue("admin-token")
                .header("alg", "HS256")
                .subject("1")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(1800))
                .claim("username", "admin")
                .claim("role", "ADMIN")
                .build());

        mockMvc.perform(post("/api/admin/auth/logout")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.SUCCESS.code()));

        verify(adminAuthService).logout(1L, "admin");
    }
}
