package com.jingcaicompass.history.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jingcaicompass.history.service.HistoryQueryService;
import com.jingcaicompass.statistics.controller.StatisticsController;
import com.jingcaicompass.statistics.service.StatisticsQueryService;
import com.jingcaicompass.system.api.PageResult;
import com.jingcaicompass.system.config.SecurityConfig;
import com.jingcaicompass.system.exception.ErrorCode;
import com.jingcaicompass.system.exception.GlobalExceptionHandler;
import com.jingcaicompass.system.infrastructure.TraceIdContext;
import com.jingcaicompass.system.infrastructure.TraceIdFilter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({HistoryController.class, StatisticsController.class})
@Import({SecurityConfig.class, GlobalExceptionHandler.class, TraceIdFilter.class})
class HistoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private HistoryQueryService historyQueryService;

    @MockBean
    private StatisticsQueryService statisticsQueryService;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void exposesHistoryListAsPublicPostWithApiResponseAndTraceId() throws Exception {
        when(historyQueryService.list(any())).thenReturn(new PageResult<>(List.of(), 1, 20, 0));

        mockMvc.perform(post("/api/public/history/list")
                        .contentType("application/json")
                        .content("{\"modelVersion\":\"t507-v1\"}")
                        .header(TraceIdContext.HEADER_NAME, "history-controller-test"))
                .andExpect(status().isOk())
                .andExpect(header().string(TraceIdContext.HEADER_NAME, "history-controller-test"))
                .andExpect(jsonPath("$.code").value(ErrorCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.pageNo").value(1))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void exposesStatisticsSummaryAsPublicPost() throws Exception {
        mockMvc.perform(post("/api/public/statistics/summary")
                        .contentType("application/json")
                        .content("{}")
                        .header(TraceIdContext.HEADER_NAME, "statistics-controller-test"))
                .andExpect(status().isOk())
                .andExpect(header().string(TraceIdContext.HEADER_NAME, "statistics-controller-test"))
                .andExpect(jsonPath("$.code").value(ErrorCode.SUCCESS.code()));
    }
}
