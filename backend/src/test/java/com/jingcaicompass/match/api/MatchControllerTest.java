package com.jingcaicompass.match.api;

import com.jingcaicompass.match.api.vo.MatchSummaryVo;
import com.jingcaicompass.match.application.MatchQueryService;
import com.jingcaicompass.match.domain.MatchStatus;
import com.jingcaicompass.system.config.SecurityConfig;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import com.jingcaicompass.system.exception.GlobalExceptionHandler;
import com.jingcaicompass.system.infrastructure.TraceIdContext;
import com.jingcaicompass.system.infrastructure.TraceIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MatchController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, TraceIdFilter.class})
class MatchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MatchQueryService matchQueryService;

    @Test
    void exposesPublicDailyMatchList() throws Exception {
        LocalDate lotteryDate = LocalDate.of(2026, 7, 22);
        when(matchQueryService.findDailyMatches(lotteryDate)).thenReturn(List.of(
                new MatchSummaryVo(
                        "stub-2026-07-22-001",
                        lotteryDate,
                        "周三001",
                        "英超",
                        "曼彻斯特城",
                        "阿森纳",
                        OffsetDateTime.parse("2026-07-22T19:30:00+08:00"),
                        -1,
                        MatchStatus.SCHEDULED,
                        "STUB"
                )
        ));

        mockMvc.perform(get("/api/public/matches")
                        .param("lotteryDate", "2026-07-22")
                        .header(TraceIdContext.HEADER_NAME, "match-list-test"))
                .andExpect(status().isOk())
                .andExpect(header().string(TraceIdContext.HEADER_NAME, "match-list-test"))
                .andExpect(jsonPath("$.code").value(ErrorCode.SUCCESS.code()))
                .andExpect(jsonPath("$.traceId").value("match-list-test"))
                .andExpect(jsonPath("$.data[0].lotteryMatchNo").value("周三001"))
                .andExpect(jsonPath("$.data[0].officialHandicap").value(-1))
                .andExpect(jsonPath("$.data[0].dataSource").value("STUB"));
    }

    @Test
    void returnsUnifiedParameterErrorWithTraceId() throws Exception {
        mockMvc.perform(get("/api/public/matches")
                        .param("lotteryDate", "not-a-date")
                        .header(TraceIdContext.HEADER_NAME, "invalid-date-test"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(TraceIdContext.HEADER_NAME, "invalid-date-test"))
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAMETER.code()))
                .andExpect(jsonPath("$.traceId").value("invalid-date-test"));
    }

    @Test
    void returnsUnifiedBusinessErrorWithTraceId() throws Exception {
        LocalDate lotteryDate = LocalDate.of(2026, 7, 22);
        when(matchQueryService.findDailyMatches(lotteryDate)).thenThrow(
                new BusinessException(ErrorCode.DATA_SOURCE_UNAVAILABLE, "体彩数据源暂时不可用")
        );

        mockMvc.perform(get("/api/public/matches")
                        .param("lotteryDate", "2026-07-22")
                        .header(TraceIdContext.HEADER_NAME, "provider-error-test"))
                .andExpect(status().isBadGateway())
                .andExpect(header().string(TraceIdContext.HEADER_NAME, "provider-error-test"))
                .andExpect(jsonPath("$.code").value(ErrorCode.DATA_SOURCE_UNAVAILABLE.code()))
                .andExpect(jsonPath("$.message").value("体彩数据源暂时不可用"))
                .andExpect(jsonPath("$.traceId").value("provider-error-test"));
    }

    @Test
    void rejectsAdministrativePathsBeforeAuthenticationIsImplemented() throws Exception {
        mockMvc.perform(get("/api/admin/jobs")
                        .header(TraceIdContext.HEADER_NAME, "admin-denied-test"))
                .andExpect(status().isForbidden())
                .andExpect(header().string(TraceIdContext.HEADER_NAME, "admin-denied-test"))
                .andExpect(jsonPath("$.code").value(ErrorCode.ACCESS_DENIED.code()))
                .andExpect(jsonPath("$.traceId").value("admin-denied-test"));
    }

    @Test
    void hidesUnexpectedExceptionDetails() throws Exception {
        LocalDate lotteryDate = LocalDate.of(2026, 7, 22);
        when(matchQueryService.findDailyMatches(lotteryDate)).thenThrow(
                new IllegalStateException("sensitive-internal-detail")
        );

        mockMvc.perform(get("/api/public/matches")
                        .param("lotteryDate", "2026-07-22")
                        .header(TraceIdContext.HEADER_NAME, "unknown-error-test"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(ErrorCode.INTERNAL_ERROR.code()))
                .andExpect(jsonPath("$.message").value(ErrorCode.INTERNAL_ERROR.defaultMessage()))
                .andExpect(jsonPath("$.traceId").value("unknown-error-test"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("sensitive-internal-detail")
                )));
    }
}
