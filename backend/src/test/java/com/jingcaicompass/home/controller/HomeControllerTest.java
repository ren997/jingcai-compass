package com.jingcaicompass.home.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jingcaicompass.home.service.HomeSummaryQueryService;
import com.jingcaicompass.home.vo.HomeDataFreshnessVo;
import com.jingcaicompass.home.vo.HomeSummaryVo;
import com.jingcaicompass.home.vo.HomeTodayOverviewVo;
import com.jingcaicompass.system.config.SecurityConfig;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import com.jingcaicompass.system.exception.GlobalExceptionHandler;
import com.jingcaicompass.system.infrastructure.TraceIdContext;
import com.jingcaicompass.system.infrastructure.TraceIdFilter;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HomeController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, TraceIdFilter.class})
class HomeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private HomeSummaryQueryService homeSummaryQueryService;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void exposesAnonymousHomeSummaryWithTraceId() throws Exception {
        when(homeSummaryQueryService.summary()).thenReturn(summary());

        mockMvc.perform(get("/api/public/home/summary")
                        .header(TraceIdContext.HEADER_NAME, "home-summary-test"))
                .andExpect(status().isOk())
                .andExpect(header().string(TraceIdContext.HEADER_NAME, "home-summary-test"))
                .andExpect(jsonPath("$.code").value(ErrorCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.asOfDate").value("2026-07-28"))
                .andExpect(jsonPath("$.data.today.matchCount").value(8))
                .andExpect(jsonPath("$.data.dataFreshness.sportteryDataAgeSeconds").value(900));
    }

    @Test
    void returnsUnifiedUnavailableErrorWithTraceId() throws Exception {
        when(homeSummaryQueryService.summary()).thenThrow(
                new BusinessException(ErrorCode.DATA_SOURCE_UNAVAILABLE, "public home summary requires a database")
        );

        mockMvc.perform(get("/api/public/home/summary")
                        .header(TraceIdContext.HEADER_NAME, "home-unavailable-test"))
                .andExpect(status().isBadGateway())
                .andExpect(header().string(TraceIdContext.HEADER_NAME, "home-unavailable-test"))
                .andExpect(jsonPath("$.code").value(ErrorCode.DATA_SOURCE_UNAVAILABLE.code()))
                .andExpect(jsonPath("$.traceId").value("home-unavailable-test"));
    }

    private HomeSummaryVo summary() {
        return new HomeSummaryVo(
                LocalDate.of(2026, 7, 28),
                new HomeTodayOverviewVo(8, 5),
                3,
                42,
                null,
                null,
                new HomeDataFreshnessVo(Instant.parse("2026-07-28T00:45:00Z"), 900L),
                Instant.parse("2026-07-28T00:30:00Z"),
                Instant.parse("2026-07-28T01:00:00Z")
        );
    }
}
