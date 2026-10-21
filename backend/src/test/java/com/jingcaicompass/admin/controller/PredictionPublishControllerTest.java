package com.jingcaicompass.admin.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jingcaicompass.prediction.dto.PredictionBatchPublishDto;
import com.jingcaicompass.prediction.dto.PredictionPublishDto;
import com.jingcaicompass.prediction.enums.PredictionStatusEnum;
import com.jingcaicompass.prediction.service.PredictionBatchPublishService;
import com.jingcaicompass.prediction.service.PredictionPublishService;
import com.jingcaicompass.prediction.vo.PredictionBatchPublishResultVo;
import com.jingcaicompass.prediction.vo.PredictionPublishResultVo;
import java.util.List;
import com.jingcaicompass.system.exception.ErrorCode;
import com.jingcaicompass.system.exception.GlobalExceptionHandler;
import com.jingcaicompass.system.infrastructure.TraceIdFilter;
import java.time.Instant;
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

@WebMvcTest(PredictionPublishController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, TraceIdFilter.class})
class PredictionPublishControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PredictionPublishService predictionPublishService;

    @MockBean
    private PredictionBatchPublishService predictionBatchPublishService;

    @Test
    void publishesWithJwtOperatorAndReturnsExplicitResult() throws Exception {
        Instant publishTime = Instant.parse("2026-07-26T08:00:00Z");
        Instant lockTime = Instant.parse("2026-07-26T10:00:00Z");
        when(predictionPublishService.publish(
                eq(new PredictionPublishDto(123L)),
                eq("trusted-admin")
        )).thenReturn(new PredictionPublishResultVo(
                123L,
                456L,
                "model-v1",
                1,
                PredictionStatusEnum.PUBLISHED,
                publishTime,
                lockTime,
                "a".repeat(64),
                false
        ));

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(jwt("trusted-admin")));
        SecurityContextHolder.setContext(context);
        try {
            mockMvc.perform(post("/api/admin/predictions/publish")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "predictionId": 123,
                                      "operatorUsername": "spoofed-client"
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(ErrorCode.SUCCESS.code()))
                    .andExpect(jsonPath("$.data.predictionId").value(123))
                    .andExpect(jsonPath("$.data.predictionStatus").value("PUBLISHED"))
                    .andExpect(jsonPath("$.data.predictionHash").value("a".repeat(64)))
                    .andExpect(jsonPath("$.data.reused").value(false));
            verify(predictionPublishService).publish(
                    new PredictionPublishDto(123L),
                    "trusted-admin"
            );
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void rejectsMissingAndNonPositivePredictionId() throws Exception {
        mockMvc.perform(post("/api/admin/predictions/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAMETER.code()));

        mockMvc.perform(post("/api/admin/predictions/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"predictionId\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAMETER.code()));
    }

    /**
     * 批量接口必须逐条回结果：只有汇总数时，运营无法判断哪几条没发出去，
     * 只能整批重跑，而已成功的条目会被幂等复用，等于白跑一遍。
     */
    @Test
    void publishesBatchAndReturnsPerItemResults() throws Exception {
        when(predictionBatchPublishService.publish(
                eq(new PredictionBatchPublishDto(List.of(123L, 124L))),
                eq("trusted-admin")
        )).thenReturn(new PredictionBatchPublishResultVo(
                2,
                1,
                1,
                List.of(
                        PredictionBatchPublishResultVo.Item.success(new PredictionPublishResultVo(
                                123L, 456L, "model-v1", 1, PredictionStatusEnum.PUBLISHED,
                                Instant.parse("2026-07-26T08:00:00Z"),
                                Instant.parse("2026-07-26T10:00:00Z"),
                                "a".repeat(64), false
                        )),
                        PredictionBatchPublishResultVo.Item.failure(124L, 456L, "prediction status must be DRAFT")
                )
        ));

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(jwt("trusted-admin")));
        SecurityContextHolder.setContext(context);
        try {
            mockMvc.perform(post("/api/admin/predictions/publish/batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"predictionIds": [123, 124]}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.requestedCount").value(2))
                    .andExpect(jsonPath("$.data.successCount").value(1))
                    .andExpect(jsonPath("$.data.failureCount").value(1))
                    .andExpect(jsonPath("$.data.results[0].success").value(true))
                    .andExpect(jsonPath("$.data.results[1].errorMessage").value("prediction status must be DRAFT"));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void rejectsEmptyOrOversizedBatch() throws Exception {
        mockMvc.perform(post("/api/admin/predictions/publish/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"predictionIds\": []}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAMETER.code()));

        String tooMany = "{\"predictionIds\":[" + "1,".repeat(200) + "1]}";
        mockMvc.perform(post("/api/admin/predictions/publish/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tooMany))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAMETER.code()));
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
