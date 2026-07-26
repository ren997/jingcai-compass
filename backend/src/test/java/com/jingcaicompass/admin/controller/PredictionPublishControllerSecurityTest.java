package com.jingcaicompass.admin.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jingcaicompass.prediction.enums.PredictionStatusEnum;
import com.jingcaicompass.prediction.service.PredictionPublishService;
import com.jingcaicompass.prediction.vo.PredictionPublishResultVo;
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

@WebMvcTest(PredictionPublishController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, TraceIdFilter.class})
class PredictionPublishControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PredictionPublishService predictionPublishService;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void publishRequiresAdministratorAuthentication() throws Exception {
        mockMvc.perform(post("/api/admin/predictions/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"predictionId\":1}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.AUTH_UNAUTHORIZED.code()));
    }

    @Test
    void administratorCanPublishPrediction() throws Exception {
        when(jwtDecoder.decode("admin-token")).thenReturn(jwt("ADMIN"));
        when(predictionPublishService.publish(any(), eq("admin"))).thenReturn(result());

        mockMvc.perform(post("/api/admin/predictions/publish")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"predictionId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.SUCCESS.code()));
    }

    @Test
    void nonAdministratorTokenIsForbiddenBeforeService() throws Exception {
        when(jwtDecoder.decode("viewer-token")).thenReturn(jwt("VIEWER"));

        mockMvc.perform(post("/api/admin/predictions/publish")
                        .header("Authorization", "Bearer viewer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"predictionId\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ACCESS_DENIED.code()));
    }

    private PredictionPublishResultVo result() {
        return new PredictionPublishResultVo(
                1L,
                2L,
                "model-v1",
                1,
                PredictionStatusEnum.PUBLISHED,
                Instant.parse("2026-07-26T08:00:00Z"),
                Instant.parse("2026-07-26T10:00:00Z"),
                "a".repeat(64),
                false
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
