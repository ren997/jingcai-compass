package com.jingcaicompass.admin.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jingcaicompass.match.dto.NormalizationBackfillResultDto;
import com.jingcaicompass.match.service.MatchNormalizationBackfillService;
import com.jingcaicompass.system.exception.ErrorCode;
import com.jingcaicompass.system.exception.GlobalExceptionHandler;
import com.jingcaicompass.system.infrastructure.TraceIdFilter;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DataPipelineAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, TraceIdFilter.class})
class DataPipelineAdminControllerTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 7, 22);

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MatchNormalizationBackfillService normalizationBackfillService;

    @Test
    void backfillReturnsNormalizationReport() throws Exception {
        when(normalizationBackfillService.backfill(BUSINESS_DATE))
                .thenReturn(new NormalizationBackfillResultDto(
                        BUSINESS_DATE,
                        3,
                        2,
                        1,
                        0,
                        2,
                        java.util.Map.of(),
                        java.util.List.of()
                ));

        mockMvc.perform(post("/api/admin/provider/pipeline/backfill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessDate":"2026-07-22"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.businessDate").value("2026-07-22"))
                .andExpect(jsonPath("$.data.totalMatchCount").value(3))
                .andExpect(jsonPath("$.data.normalizedMatchCount").value(2))
                .andExpect(jsonPath("$.data.pendingMatchCount").value(1));
    }

    @Test
    void missingBusinessDateReturnsParameterError() throws Exception {
        mockMvc.perform(post("/api/admin/provider/pipeline/backfill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAMETER.code()))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("businessDate")
                ));
    }
}
