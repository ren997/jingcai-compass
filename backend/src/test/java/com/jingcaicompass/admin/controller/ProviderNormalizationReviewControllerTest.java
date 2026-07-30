package com.jingcaicompass.admin.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.match.dto.ProviderNormalizationReviewConfirmDto;
import com.jingcaicompass.match.dto.ProviderNormalizationReviewListQueryDto;
import com.jingcaicompass.match.enums.MappingStatusEnum;
import com.jingcaicompass.match.enums.ProviderNormalizationEntityTypeEnum;
import com.jingcaicompass.match.service.ProviderNormalizationReviewService;
import com.jingcaicompass.match.vo.ProviderNormalizationEntityVo;
import com.jingcaicompass.match.vo.ProviderNormalizationReviewDetailVo;
import com.jingcaicompass.match.vo.ProviderNormalizationReviewListItemVo;
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

@WebMvcTest(ProviderNormalizationReviewController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, TraceIdFilter.class})
class ProviderNormalizationReviewControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private ProviderNormalizationReviewService providerNormalizationReviewService;

    @Test
    void listReturnsCapturedIdentityWithTraceId() throws Exception {
        when(providerNormalizationReviewService.list(any())).thenReturn(new PageResult<>(List.of(
                new ProviderNormalizationReviewListItemVo(1L, ProviderNormalizationEntityTypeEnum.TEAM,
                        "THE_ODDS_API", "SCOPED_NAME:abc", "soccer_epl", "Manchester United", "manchesterunited",
                        MappingStatusEnum.PENDING, new BigDecimal("0.5000"), "NAME_CANDIDATE",
                        new ProviderNormalizationEntityVo(8L, "Manchester United", "Manchester United"), Instant.parse("2026-07-30T01:00:00Z"))),
                1, 20, 1));

        mockMvc.perform(post("/api/admin/provider/normalizations/list")
                        .header(TraceIdContext.HEADER_NAME, "normalization-list")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ProviderNormalizationReviewListQueryDto(
                                ProviderNormalizationEntityTypeEnum.TEAM, "THE_ODDS_API", null, 1, 20))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.SUCCESS.code()))
                .andExpect(jsonPath("$.traceId").isString())
                .andExpect(jsonPath("$.data.records[0].externalScope").value("soccer_epl"));
    }

    @Test
    void confirmUsesJwtOperatorInsteadOfClientInput() throws Exception {
        ProviderNormalizationReviewDetailVo detail = new ProviderNormalizationReviewDetailVo(2L,
                ProviderNormalizationEntityTypeEnum.LEAGUE, "THE_ODDS_API", "soccer_epl", null,
                "soccer_epl", "soccerepl", MappingStatusEnum.MANUAL_CONFIRMED, BigDecimal.ONE,
                "MANUAL_NORMALIZATION_REVIEW", new ProviderNormalizationEntityVo(9L, "英超", "Premier League"),
                List.of(), Instant.parse("2026-07-30T01:00:00Z"));
        when(providerNormalizationReviewService.confirm(any(), eq("admin-1"))).thenReturn(detail);
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "HS256").subject("1")
                .claim("username", "admin-1").claim("role", "ADMIN").build();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(jwt));
        SecurityContextHolder.setContext(context);
        try {
            mockMvc.perform(post("/api/admin/provider/normalizations/confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"entityType\":\"LEAGUE\",\"mappingId\":2,\"targetEntityId\":9,\"operatorId\":\"spoofed\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.mappingStatus").value("MANUAL_CONFIRMED"));
            verify(providerNormalizationReviewService).confirm(new ProviderNormalizationReviewConfirmDto(
                    ProviderNormalizationEntityTypeEnum.LEAGUE, 2L, 9L), "admin-1");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
