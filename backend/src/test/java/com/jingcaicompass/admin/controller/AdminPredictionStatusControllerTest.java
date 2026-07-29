package com.jingcaicompass.admin.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jingcaicompass.admin.service.AdminPredictionStatusQueryService;
import com.jingcaicompass.admin.vo.AdminPredictionStatusPageVo;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import com.jingcaicompass.system.exception.GlobalExceptionHandler;
import com.jingcaicompass.system.infrastructure.TraceIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminPredictionStatusController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, TraceIdFilter.class})
class AdminPredictionStatusControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AdminPredictionStatusQueryService queryService;

    @Test
    void listAndSettlementEndpointsReturnTraceablePages() throws Exception {
        when(queryService.locks(any())).thenReturn(new AdminPredictionStatusPageVo(java.util.List.of(), 1, 20, 0, 0));
        when(queryService.settlements(any())).thenReturn(new AdminPredictionStatusPageVo(java.util.List.of(), 1, 20, 0, 0));

        mockMvc.perform(post("/api/admin/prediction-status/locks/list")
                        .header("X-Trace-Id", "lock-list-trace").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.data.manualAttentionCount").value(0));
        mockMvc.perform(post("/api/admin/prediction-status/settlements/list")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.records").isArray());
    }

    @Test
    void detailValidatesIdAndKeepsNotFoundTraceId() throws Exception {
        mockMvc.perform(post("/api/admin/prediction-status/detail")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"predictionId\":0}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAMETER.code()));

        when(queryService.detail(any())).thenThrow(new BusinessException(ErrorCode.PREDICTION_NOT_FOUND));
        mockMvc.perform(post("/api/admin/prediction-status/detail")
                        .header("X-Trace-Id", "missing-prediction-trace")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"predictionId\":99}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(ErrorCode.PREDICTION_NOT_FOUND.code()))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }
}
