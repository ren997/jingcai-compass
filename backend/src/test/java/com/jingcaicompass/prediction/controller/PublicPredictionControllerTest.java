package com.jingcaicompass.prediction.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jingcaicompass.prediction.enums.ConfidenceLevelEnum;
import com.jingcaicompass.prediction.enums.HandicapPickEnum;
import com.jingcaicompass.prediction.enums.PredictionStatusEnum;
import com.jingcaicompass.prediction.service.PublicPredictionQueryService;
import com.jingcaicompass.prediction.vo.PredictionDetailVo;
import com.jingcaicompass.prediction.vo.PredictionModelDetailVo;
import com.jingcaicompass.prediction.vo.PredictionSnapshotVerificationVo;
import com.jingcaicompass.prediction.vo.PredictionSnapshotVo;
import com.jingcaicompass.prediction.vo.PredictionVersionVo;
import com.jingcaicompass.snapshot.dto.PublicPredictionSnapshotDownloadDto;
import com.jingcaicompass.snapshot.enums.PublicSnapshotAvailabilityEnum;
import com.jingcaicompass.system.config.SecurityConfig;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import com.jingcaicompass.system.exception.GlobalExceptionHandler;
import com.jingcaicompass.system.infrastructure.TraceIdContext;
import com.jingcaicompass.system.infrastructure.TraceIdFilter;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@WebMvcTest(PublicPredictionController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, TraceIdFilter.class})
class PublicPredictionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PublicPredictionQueryService publicPredictionQueryService;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void exposesCurrentPredictionHistoryAndTraceId() throws Exception {
        when(publicPredictionQueryService.detail(any())).thenReturn(new PredictionDetailVo(
                42L,
                List.of(new PredictionModelDetailVo(
                        "model-v1",
                        version(102L, 2, 101L, snapshot()),
                        List.of(version(101L, 1, null, null))
                ))
        ));

        mockMvc.perform(post("/api/public/predictions/detail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"matchId\":42}")
                        .header(TraceIdContext.HEADER_NAME, "prediction-detail-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.SUCCESS.code()))
                .andExpect(jsonPath("$.traceId").value("prediction-detail-test"))
                .andExpect(jsonPath("$.data.modelPredictions[0].modelVersion").value("model-v1"))
                .andExpect(jsonPath("$.data.modelPredictions[0].currentPrediction.predictionId").value(102))
                .andExpect(jsonPath("$.data.modelPredictions[0].historicalPredictions[0].replacesPredictionId").doesNotExist())
                .andExpect(jsonPath("$.data.modelPredictions[0].currentPrediction.snapshot.snapshotId").value(501));
    }

    @Test
    void rejectsInvalidDetailRequestWithUnifiedTraceId() throws Exception {
        mockMvc.perform(post("/api/public/predictions/detail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header(TraceIdContext.HEADER_NAME, "prediction-invalid-test"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAMETER.code()))
                .andExpect(jsonPath("$.traceId").value("prediction-invalid-test"));
    }

    @Test
    void streamsOnlyVerifiedPublishedSnapshotWithoutStorageAddress() throws Exception {
        byte[] bytes = "{\"schemaVersion\":1}".getBytes(StandardCharsets.UTF_8);
        when(publicPredictionQueryService.openSnapshot(501L)).thenReturn(
                new PublicPredictionSnapshotDownloadDto(
                        501L,
                        LocalDate.of(2026, 7, 28),
                        2,
                        "application/json",
                        (long) bytes.length,
                        new ByteArrayInputStream(bytes)
                )
        );

        MvcResult result = mockMvc.perform(get("/api/public/predictions/snapshots/501/download"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/json"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("prediction-snapshot-2026-07-28-v000002.json")))
                .andExpect(content().bytes(bytes));
    }

    @Test
    void verifiesSnapshotAndKeepsNotFoundTraceId() throws Exception {
        when(publicPredictionQueryService.verifySnapshot(501L)).thenReturn(
                new PredictionSnapshotVerificationVo(501L, "a".repeat(64), 99L, true)
        );
        when(publicPredictionQueryService.verifySnapshot(404L)).thenThrow(
                new BusinessException(ErrorCode.PREDICTION_SNAPSHOT_NOT_FOUND)
        );

        mockMvc.perform(post("/api/public/predictions/snapshots/501/verify"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verified").value(true))
                .andExpect(jsonPath("$.data.snapshotHash").value("a".repeat(64)));
        mockMvc.perform(post("/api/public/predictions/snapshots/404/verify")
                        .header(TraceIdContext.HEADER_NAME, "prediction-snapshot-test"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.PREDICTION_SNAPSHOT_NOT_FOUND.code()))
                .andExpect(jsonPath("$.traceId").value("prediction-snapshot-test"));
    }

    @Test
    void keepsUnavailableDownloadFailureInsideTheTraceableApiEnvelope() throws Exception {
        when(publicPredictionQueryService.openSnapshot(502L)).thenThrow(
                new BusinessException(ErrorCode.PREDICTION_SNAPSHOT_UNAVAILABLE)
        );

        mockMvc.perform(get("/api/public/predictions/snapshots/502/download")
                        .header(TraceIdContext.HEADER_NAME, "prediction-download-test"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.PREDICTION_SNAPSHOT_UNAVAILABLE.code()))
                .andExpect(jsonPath("$.traceId").value("prediction-download-test"));
    }

    private PredictionVersionVo version(
            Long predictionId,
            int predictionVersion,
            Long replacesPredictionId,
            PredictionSnapshotVo snapshot
    ) {
        return new PredictionVersionVo(
                predictionId,
                predictionVersion,
                replacesPredictionId,
                PredictionStatusEnum.LOCKED,
                "feature-v1",
                new BigDecimal("0.45"),
                new BigDecimal("0.30"),
                new BigDecimal("0.25"),
                HandicapPickEnum.HOME_WIN,
                new BigDecimal("2.50"),
                ConfidenceLevelEnum.HIGH,
                "公开分析摘要",
                Instant.parse("2026-07-28T08:00:00Z"),
                Instant.parse("2026-07-28T08:01:00Z"),
                Instant.parse("2026-07-28T12:00:00Z"),
                "b".repeat(64),
                snapshot == null ? PublicSnapshotAvailabilityEnum.UNAVAILABLE : PublicSnapshotAvailabilityEnum.AVAILABLE,
                snapshot
        );
    }

    private PredictionSnapshotVo snapshot() {
        return new PredictionSnapshotVo(
                501L,
                LocalDate.of(2026, 7, 28),
                2,
                "c".repeat(64),
                "application/json",
                99L,
                Instant.parse("2026-07-28T09:00:00Z")
        );
    }
}
