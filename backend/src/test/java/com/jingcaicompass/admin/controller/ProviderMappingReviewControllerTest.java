package com.jingcaicompass.admin.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.match.dto.MappingReviewConfirmDto;
import com.jingcaicompass.match.dto.MappingReviewBundleConfirmDto;
import com.jingcaicompass.match.dto.MappingReviewDetailQueryDto;
import com.jingcaicompass.match.dto.MappingReviewListQueryDto;
import com.jingcaicompass.match.dto.MappingReviewMatchDetailQueryDto;
import com.jingcaicompass.match.enums.MappingStatusEnum;
import com.jingcaicompass.match.service.MatchMappingReviewService;
import com.jingcaicompass.match.vo.MappingReviewDetailVo;
import com.jingcaicompass.match.vo.MappingReviewListItemVo;
import com.jingcaicompass.match.vo.MappingReviewMatchListItemVo;
import com.jingcaicompass.match.vo.MappingReviewMatchDetailVo;
import com.jingcaicompass.system.api.PageResult;
import com.jingcaicompass.system.exception.ErrorCode;
import com.jingcaicompass.system.exception.GlobalExceptionHandler;
import com.jingcaicompass.system.infrastructure.TraceIdContext;
import com.jingcaicompass.system.infrastructure.TraceIdFilter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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

@WebMvcTest(ProviderMappingReviewController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, TraceIdFilter.class})
class ProviderMappingReviewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private MatchMappingReviewService matchMappingReviewService;

    @Test
    void listReturnsPage() throws Exception {
        when(matchMappingReviewService.list(any())).thenReturn(new PageResult<>(
                List.of(new MappingReviewListItemVo(
                        1L,
                        10L,
                        "THE_ODDS_API",
                        "ext-1",
                        MappingStatusEnum.PENDING,
                        new BigDecimal("0.7000"),
                        "SCORE_PENDING",
                        "PENDING",
                        1,
                        null,
                        Instant.parse("2026-07-24T12:00:00Z")
                )),
                1,
                20,
                1
        ));

        mockMvc.perform(post("/api/admin/provider/mappings/list")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new MappingReviewListQueryDto(null, null, null, 1, 20)))
                        .header(TraceIdContext.HEADER_NAME, "review-list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.records[0].mappingId").value(1))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void listByMatchReturnsLotteryMatchesAndExternalCandidates() throws Exception {
        MappingReviewDetailVo.MatchBriefVo match = new MappingReviewDetailVo.MatchBriefVo(
                10L, "周三001", java.time.LocalDate.of(2026, 7, 29), "欧冠",
                "阿拉木图", "奥莫尼亚", Instant.parse("2026-07-29T12:00:00Z")
        );
        when(matchMappingReviewService.listByMatch(any())).thenReturn(new PageResult<>(
                List.of(new MappingReviewMatchListItemVo(match, List.of(
                        new MappingReviewMatchListItemVo.ExternalCandidateVo(
                                1L, "THE_ODDS_API", "ext-1", "soccer_uefa_champs_league",
                                "Kairat Almaty", "Omonia Nicosia", Instant.parse("2026-07-29T12:00:00Z"), MappingStatusEnum.PENDING,
                                new BigDecimal("0.7000"), List.of("KICKOFF_TIME"), "PENDING",
                                Instant.parse("2026-07-29T12:00:00Z")
                        )
                ))),
                1,
                20,
                1
        ));

        mockMvc.perform(post("/api/admin/provider/mappings/matches/list")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new MappingReviewListQueryDto(null, null, null, 1, 20))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].match.lotteryMatchNo").value("周三001"))
                        .andExpect(jsonPath("$.data.records[0].externalCandidates[0].externalHomeTeamName")
                        .value("Kairat Almaty"));
    }

    @Test
    void detailByMatchReturnsLotterySubjectAndExternalKickoff() throws Exception {
        MappingReviewDetailVo.MatchBriefVo match = new MappingReviewDetailVo.MatchBriefVo(
                10L, "周三001", java.time.LocalDate.of(2026, 7, 29), "欧冠",
                "阿拉木图", "奥莫尼亚", Instant.parse("2026-07-29T12:00:00Z")
        );
        when(matchMappingReviewService.detailByMatch(any())).thenReturn(new MappingReviewMatchDetailVo(
                match, List.of(new MappingReviewMatchListItemVo.ExternalCandidateVo(
                        1L, "THE_ODDS_API", "ext-1", "soccer_uefa_champs_league",
                        "Kairat Almaty", "Omonia Nicosia", Instant.parse("2026-07-29T12:05:00Z"),
                        MappingStatusEnum.PENDING, new BigDecimal("0.7000"), List.of("KICKOFF_TIME"), "PENDING",
                        Instant.parse("2026-07-29T12:00:00Z")
                )), List.of()
        ));

        mockMvc.perform(post("/api/admin/provider/mappings/matches/detail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new MappingReviewMatchDetailQueryDto(10L, "THE_ODDS_API", MappingStatusEnum.PENDING))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.match.lotteryMatchNo").value("周三001"))
                .andExpect(jsonPath("$.data.externalCandidates[0].externalKickoffTime")
                        .value("2026-07-29T12:05:00Z"));
    }

    @Test
    void confirmReturnsDetail() throws Exception {
        when(matchMappingReviewService.confirm(any(), eq("admin-1"))).thenReturn(new MappingReviewDetailVo(
                1L,
                10L,
                "THE_ODDS_API",
                "ext-1",
                null,
                null,
                null,
                "Manchester United",
                "Chelsea",
                Instant.parse("2026-07-24T12:00:00Z"),
                MappingStatusEnum.MANUAL_CONFIRMED,
                new BigDecimal("0.7000"),
                "MANUAL_REVIEW",
                "PENDING",
                List.of(),
                "admin-1",
                null,
                Instant.parse("2026-07-24T12:00:00Z")
        ));

        Jwt jwt = Jwt.withTokenValue("controller-test-token")
                .header("alg", "HS256")
                .subject("1")
                .claim("username", "admin-1")
                .claim("role", "ADMIN")
                .build();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(jwt));
        SecurityContextHolder.setContext(context);
        try {
            mockMvc.perform(post("/api/admin/provider/mappings/confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "mappingId": 1,
                                      "targetMatchId": null,
                                      "operatorId": "spoofed-client"
                                    }
                                    """)
                            .header(TraceIdContext.HEADER_NAME, "review-confirm"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.mappingStatus").value("MANUAL_CONFIRMED"));
            verify(matchMappingReviewService).confirm(
                    new MappingReviewConfirmDto(1L, null),
                    "admin-1"
            );
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void confirmBundleUsesJwtOperatorAndKeepsExplicitSelections() throws Exception {
        MappingReviewDetailVo confirmed = new MappingReviewDetailVo(
                1L, 10L, "THE_ODDS_API", "ext-1", null, null, null,
                "Manchester United", "Chelsea", Instant.parse("2026-07-24T12:00:00Z"),
                MappingStatusEnum.MANUAL_CONFIRMED, new BigDecimal("1.0000"), "MANUAL_REVIEW", null,
                List.of(), "admin-1", null, Instant.parse("2026-07-24T12:00:00Z")
        );
        when(matchMappingReviewService.confirmBundle(any(), eq("admin-1"))).thenReturn(confirmed);

        Jwt jwt = Jwt.withTokenValue("controller-test-token")
                .header("alg", "HS256").subject("1").claim("username", "admin-1").claim("role", "ADMIN").build();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(jwt));
        SecurityContextHolder.setContext(context);
        try {
            mockMvc.perform(post("/api/admin/provider/mappings/confirm-bundle")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"mappingId":1,"targetMatchId":10,"confirmLeague":true,
                                     "confirmHomeTeam":true,"confirmAwayTeam":false,"operatorId":"spoofed-client"}
                                    """)
                            .header(TraceIdContext.HEADER_NAME, "bundle-confirm"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.traceId").isNotEmpty())
                    .andExpect(jsonPath("$.data.mappingStatus").value("MANUAL_CONFIRMED"));
            verify(matchMappingReviewService).confirmBundle(
                    new MappingReviewBundleConfirmDto(1L, 10L, true, true, false), "admin-1"
            );
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void detailReturnsMapping() throws Exception {
        when(matchMappingReviewService.detail(any())).thenReturn(new MappingReviewDetailVo(
                2L,
                20L,
                "THE_ODDS_API",
                "ext-2",
                null,
                null,
                null,
                "Manchester United",
                "Chelsea",
                Instant.parse("2026-07-24T12:00:00Z"),
                MappingStatusEnum.PENDING,
                new BigDecimal("0.5000"),
                "SCORE_PENDING",
                "PENDING",
                List.of(),
                null,
                null,
                Instant.parse("2026-07-24T12:00:00Z")
        ));

        mockMvc.perform(post("/api/admin/provider/mappings/detail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new MappingReviewDetailQueryDto(2L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mappingId").value(2))
                .andExpect(jsonPath("$.data.externalHomeTeamName").value("Manchester United"))
                .andExpect(jsonPath("$.data.externalAwayTeamName").value("Chelsea"));
    }
}
