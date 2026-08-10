package com.jingcaicompass.admin.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jingcaicompass.prediction.enums.BaselinePredictionSkipReasonEnum;
import com.jingcaicompass.prediction.service.BaselinePredictionGenerationService;
import com.jingcaicompass.prediction.vo.BaselinePredictionGenerationVo;
import com.jingcaicompass.system.exception.ErrorCode;
import com.jingcaicompass.system.exception.GlobalExceptionHandler;
import com.jingcaicompass.system.infrastructure.TraceIdFilter;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

/** 首版预测生成控制器的输入、操作者和输出契约测试。 */
@WebMvcTest(BaselinePredictionGenerationController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, TraceIdFilter.class})
class BaselinePredictionGenerationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BaselinePredictionGenerationService generationService;

    @Test
    void generatesDraftPredictionsUsingAuthenticatedAdministrator() throws Exception {
        when(generationService.generateAndImport(eq(LocalDate.of(2026, 8, 11)), eq("trusted-admin")))
                .thenReturn(result());

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(jwt("trusted-admin")));
        SecurityContextHolder.setContext(context);
        try {
            mockMvc.perform(post("/api/admin/predictions/baseline/generate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"lotteryDate\":\"2026-08-11\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(ErrorCode.SUCCESS.code()))
                    .andExpect(jsonPath("$.data.generatedCount").value(1))
                    .andExpect(jsonPath("$.data.generationBatchId").value("t306-baseline-2026-08-11-abcdef0123456789"));
            verify(generationService).generateAndImport(LocalDate.of(2026, 8, 11), "trusted-admin");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void rejectsMissingLotteryDate() throws Exception {
        mockMvc.perform(post("/api/admin/predictions/baseline/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAMETER.code()));
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
                Map.of(BaselinePredictionSkipReasonEnum.MISSING_CONFIRMED_ASIAN_MARKET, 0)
        );
    }

    private Jwt jwt(String username) {
        return Jwt.withTokenValue("controller-test-token")
                .header("alg", "HS256")
                .subject("1")
                .claim("username", username)
                .claim("role", "ADMIN")
                .build();
    }
}
