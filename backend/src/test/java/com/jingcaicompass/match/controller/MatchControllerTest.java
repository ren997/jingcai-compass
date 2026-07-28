package com.jingcaicompass.match.controller;

import com.jingcaicompass.match.enums.MatchStatusEnum;
import com.jingcaicompass.match.service.MatchQueryService;
import com.jingcaicompass.match.vo.MatchDetailVo;
import com.jingcaicompass.match.vo.MatchListItemVo;
import com.jingcaicompass.match.vo.MatchSummaryVo;
import com.jingcaicompass.system.api.PageResult;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MatchController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, TraceIdFilter.class})
class MatchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MatchQueryService matchQueryService;

    @MockBean
    private JwtDecoder jwtDecoder;

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
                        BigDecimal.valueOf(-1),
                        MatchStatusEnum.SCHEDULED,
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
    void exposesPagedListAndDetailThroughExplicitPublicModels() throws Exception {
        LocalDate lotteryDate = LocalDate.of(2026, 7, 22);
        MatchListItemVo listItem = new MatchListItemVo(
                42L, lotteryDate, "周三042", 7L, "英超", "主队", "客队",
                OffsetDateTime.parse("2026-07-22T19:30:00+08:00"), MatchStatusEnum.SCHEDULED,
                BigDecimal.valueOf(-1), com.jingcaicompass.match.enums.MatchDataAvailabilityEnum.AVAILABLE,
                "CHINA_SPORTTERY", OffsetDateTime.parse("2026-07-22T10:00:00+08:00"), null
        );
        MatchDetailVo detail = new MatchDetailVo(
                42L, lotteryDate, "周三042", 7L, "英超", "主队", "客队",
                OffsetDateTime.parse("2026-07-22T19:30:00+08:00"), MatchStatusEnum.SCHEDULED,
                null, null, null,
                com.jingcaicompass.match.enums.MatchDataAvailabilityEnum.NO_ASIAN_ODDS_SNAPSHOT,
                List.of(), com.jingcaicompass.match.enums.MatchDataAvailabilityEnum.NO_SOURCE_MAPPING, List.of()
        );
        when(matchQueryService.list(org.mockito.ArgumentMatchers.any())).thenReturn(
                new PageResult<>(List.of(listItem), 1, 20, 1)
        );
        when(matchQueryService.detail(new com.jingcaicompass.match.dto.MatchDetailQueryDto(42L))).thenReturn(detail);

        mockMvc.perform(post("/api/public/matches/list")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lotteryDate\":\"2026-07-22\",\"pageSize\":20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].matchId").value(42))
                .andExpect(jsonPath("$.data.records[0].sportteryAvailability").value("AVAILABLE"));

        mockMvc.perform(post("/api/public/matches/detail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"matchId\":42}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.matchId").value(42))
                .andExpect(jsonPath("$.data.asianOddsAvailability").value("NO_ASIAN_ODDS_SNAPSHOT"));
    }

    @Test
    void rejectsMissingDetailMatchIdWithUnifiedParameterError() throws Exception {
        mockMvc.perform(post("/api/public/matches/detail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header(TraceIdContext.HEADER_NAME, "missing-match-id-test"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAMETER.code()))
                .andExpect(jsonPath("$.traceId").value("missing-match-id-test"));
    }

    @Test
    void returnsNotFoundWithTraceIdForUnknownDetailMatch() throws Exception {
        when(matchQueryService.detail(new com.jingcaicompass.match.dto.MatchDetailQueryDto(404L))).thenThrow(
                new BusinessException(ErrorCode.MATCH_NOT_FOUND)
        );

        mockMvc.perform(post("/api/public/matches/detail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"matchId\":404}")
                        .header(TraceIdContext.HEADER_NAME, "unknown-match-test"))
                .andExpect(status().isNotFound())
                .andExpect(header().string(TraceIdContext.HEADER_NAME, "unknown-match-test"))
                .andExpect(jsonPath("$.code").value(ErrorCode.MATCH_NOT_FOUND.code()))
                .andExpect(jsonPath("$.traceId").value("unknown-match-test"));
    }

    @Test
    void rejectsUnknownListSortWithUnifiedParameterError() throws Exception {
        mockMvc.perform(post("/api/public/matches/list")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sort\":\"arbitrary_sql\"}")
                        .header(TraceIdContext.HEADER_NAME, "invalid-sort-test"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(TraceIdContext.HEADER_NAME, "invalid-sort-test"))
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAMETER.code()))
                .andExpect(jsonPath("$.traceId").value("invalid-sort-test"));
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
    void rejectsAdministrativePathsWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/admin/jobs")
                        .header(TraceIdContext.HEADER_NAME, "admin-denied-test"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(TraceIdContext.HEADER_NAME, "admin-denied-test"))
                .andExpect(jsonPath("$.code").value(ErrorCode.AUTH_UNAUTHORIZED.code()))
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
