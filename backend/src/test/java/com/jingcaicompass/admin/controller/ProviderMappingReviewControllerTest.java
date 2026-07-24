package com.jingcaicompass.admin.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.match.dto.MappingReviewConfirmDto;
import com.jingcaicompass.match.dto.MappingReviewDetailQueryDto;
import com.jingcaicompass.match.dto.MappingReviewListQueryDto;
import com.jingcaicompass.match.enums.MappingStatusEnum;
import com.jingcaicompass.match.service.MatchMappingReviewService;
import com.jingcaicompass.match.vo.MappingReviewDetailVo;
import com.jingcaicompass.match.vo.MappingReviewListItemVo;
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
                        .content(objectMapper.writeValueAsString(new MappingReviewListQueryDto(null, null, 1, 20)))
                        .header(TraceIdContext.HEADER_NAME, "review-list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.records[0].mappingId").value(1))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void confirmReturnsDetail() throws Exception {
        when(matchMappingReviewService.confirm(any())).thenReturn(new MappingReviewDetailVo(
                1L,
                10L,
                "THE_ODDS_API",
                "ext-1",
                null,
                null,
                null,
                MappingStatusEnum.MANUAL_CONFIRMED,
                new BigDecimal("0.7000"),
                "MANUAL_REVIEW",
                "PENDING",
                List.of(),
                "admin-1",
                null,
                Instant.parse("2026-07-24T12:00:00Z")
        ));

        mockMvc.perform(post("/api/admin/provider/mappings/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new MappingReviewConfirmDto(1L, null, "admin-1")))
                        .header(TraceIdContext.HEADER_NAME, "review-confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mappingStatus").value("MANUAL_CONFIRMED"));
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
                .andExpect(jsonPath("$.data.mappingId").value(2));
    }
}
