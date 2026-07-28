package com.jingcaicompass.admin.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jingcaicompass.admin.service.AdminSyncRunQueryService;
import com.jingcaicompass.admin.vo.AdminSyncRunDetailVo;
import com.jingcaicompass.admin.vo.AdminSyncRunListItemVo;
import com.jingcaicompass.admin.vo.AdminSyncRunQuotaSummaryVo;
import com.jingcaicompass.data.enums.ProviderDataTypeEnum;
import com.jingcaicompass.data.enums.SyncStatusEnum;
import com.jingcaicompass.system.api.PageResult;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import com.jingcaicompass.system.infrastructure.TraceIdContext;
import com.jingcaicompass.system.exception.GlobalExceptionHandler;
import com.jingcaicompass.system.infrastructure.TraceIdFilter;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminSyncRunController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, TraceIdFilter.class})
class AdminSyncRunControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AdminSyncRunQueryService adminSyncRunQueryService;

    @Test
    void listReturnsSafeRunPageWithTraceId() throws Exception {
        when(adminSyncRunQueryService.list(any())).thenReturn(new PageResult<>(
                List.of(run()), 1, 20, 1
        ));

        mockMvc.perform(post("/api/admin/provider/sync-runs/list")
                        .header(TraceIdContext.HEADER_NAME, "sync-list-trace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"syncStatuses\":[\"FAILED\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.SUCCESS.code()))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.data.records[0].syncRunId").value(7))
                .andExpect(jsonPath("$.data.records[0].errorSummary").value("upstream failed"));
    }

    @Test
    void detailValidatesIdAndPreservesNotFoundTraceId() throws Exception {
        mockMvc.perform(post("/api/admin/provider/sync-runs/detail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"syncRunId\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAMETER.code()));

        when(adminSyncRunQueryService.detail(any())).thenThrow(new BusinessException(ErrorCode.SYNC_RUN_NOT_FOUND));
        mockMvc.perform(post("/api/admin/provider/sync-runs/detail")
                        .header(TraceIdContext.HEADER_NAME, "missing-run-trace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"syncRunId\":99}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.SYNC_RUN_NOT_FOUND.code()))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void quotaSummaryReturnsBusinessDate() throws Exception {
        when(adminSyncRunQueryService.quotaSummary(any())).thenReturn(
                new AdminSyncRunQuotaSummaryVo(LocalDate.of(2026, 7, 28), Instant.now(), List.of())
        );

        mockMvc.perform(post("/api/admin/provider/sync-runs/quota/summary")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessDate\":\"2026-07-28\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.businessDate").value("2026-07-28"));
    }

    private AdminSyncRunListItemVo run() {
        return new AdminSyncRunListItemVo(
                7L, "THE_ODDS_API", ProviderDataTypeEnum.ASIAN_ODDS, SyncStatusEnum.FAILED,
                Instant.parse("2026-07-28T00:00:00Z"), Instant.parse("2026-07-28T00:01:00Z"),
                1, 0, 1, 1, 1, "upstream failed"
        );
    }
}
