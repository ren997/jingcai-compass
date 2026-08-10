package com.jingcaicompass.admin.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jingcaicompass.prediction.service.BaselinePredictionGenerationService;
import com.jingcaicompass.prediction.vo.BaselinePredictionGenerationVo;
import com.jingcaicompass.system.config.SecurityConfig;
import com.jingcaicompass.system.exception.ErrorCode;
import com.jingcaicompass.system.exception.GlobalExceptionHandler;
import com.jingcaicompass.system.infrastructure.TraceIdFilter;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

/** 首版预测生成入口必须保持管理员 JWT 边界。 */
@WebMvcTest(BaselinePredictionGenerationController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, TraceIdFilter.class})
class BaselinePredictionGenerationControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BaselinePredictionGenerationService generationService;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void generationRequiresAdministratorAuthentication() throws Exception {
        mockMvc.perform(post("/api/admin/predictions/baseline/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lotteryDate\":\"2026-08-11\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.AUTH_UNAUTHORIZED.code()));
    }

    @Test
    void administratorCanGenerateButViewerIsRejected() throws Exception {
        when(jwtDecoder.decode("admin-token")).thenReturn(jwt("ADMIN"));
        when(generationService.generateAndImport(any(), eq("admin"))).thenReturn(result());
        when(jwtDecoder.decode("viewer-token")).thenReturn(jwt("VIEWER"));

        mockMvc.perform(post("/api/admin/predictions/baseline/generate")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lotteryDate\":\"2026-08-11\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.SUCCESS.code()));
        mockMvc.perform(post("/api/admin/predictions/baseline/generate")
                        .header("Authorization", "Bearer viewer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lotteryDate\":\"2026-08-11\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ACCESS_DENIED.code()));
    }

    private BaselinePredictionGenerationVo result() {
        return new BaselinePredictionGenerationVo(
                LocalDate.of(2026, 8, 11),
                "t306-odds-baseline-v1",
                "t306-sporttery-asian-v1",
                1,
                1,
                1,
                0,
                "t306-baseline-2026-08-11-abcdef0123456789",
                "a".repeat(64),
                Instant.parse("2026-08-10T02:00:00Z"),
                Map.of()
        );
    }

    private Jwt jwt(String role) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject("1")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(1800))
                .claim("username", "admin");
        if (role != null) {
            builder.claim("role", role);
        }
        return builder.build();
    }
}
