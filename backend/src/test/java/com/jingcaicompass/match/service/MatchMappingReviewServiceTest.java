package com.jingcaicompass.match.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jingcaicompass.audit.enums.AuditActionTypeEnum;
import com.jingcaicompass.audit.enums.AuditTargetTypeEnum;
import com.jingcaicompass.audit.service.AuditLogService;
import com.jingcaicompass.match.dto.MappingReviewConfirmDto;
import com.jingcaicompass.match.dto.MappingReviewDetailQueryDto;
import com.jingcaicompass.match.dto.MappingReviewListQueryDto;
import com.jingcaicompass.match.dto.MappingReviewRejectDto;
import com.jingcaicompass.match.dto.MappingReviewReopenDto;
import com.jingcaicompass.match.entity.MatchEntity;
import com.jingcaicompass.match.entity.MatchSourceMapping;
import com.jingcaicompass.match.enums.MappingStatusEnum;
import com.jingcaicompass.match.mapper.MatchMapper;
import com.jingcaicompass.match.mapper.MatchSourceMappingMapper;
import com.jingcaicompass.match.vo.MappingReviewDetailVo;
import com.jingcaicompass.match.vo.MappingReviewListItemVo;
import com.jingcaicompass.system.api.PageResult;
import com.jingcaicompass.system.config.properties.PaginationProperties;
import com.jingcaicompass.system.exception.BusinessException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MatchMappingReviewServiceTest {

    @Mock
    private MatchSourceMappingMapper matchSourceMappingMapper;

    @Mock
    private MatchMapper matchMapper;

    @Mock
    private AuditLogService auditLogService;

    private MatchMappingReviewServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new MatchMappingReviewServiceImpl(
                matchSourceMappingMapper,
                matchMapper,
                auditLogService,
                new PaginationProperties(100)
        );
    }

    @Test
    void listDefaultsToPendingAndClampsPageSize() {
        MatchSourceMapping mapping = pendingMapping(1L, 10L);
        Page<MatchSourceMapping> page = new Page<>(1, 100);
        page.setRecords(List.of(mapping));
        page.setTotal(1);
        when(matchSourceMappingMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);

        PageResult<MappingReviewListItemVo> result = service.list(
                new MappingReviewListQueryDto("THE_ODDS_API", null, 1, 500)
        );

        assertThat(result.pageSize()).isEqualTo(100);
        assertThat(result.records()).hasSize(1);
        assertThat(result.records().get(0).mappingStatus()).isEqualTo(MappingStatusEnum.PENDING);
    }

    @Test
    void detailIncludesMatchBriefAndCandidates() {
        MatchSourceMapping mapping = pendingMapping(2L, 20L);
        mapping.setMappingCandidates(List.of(Map.of(
                "matchId", 20,
                "score", "0.9000",
                "reasons", List.of("HOME_ID")
        )));
        when(matchSourceMappingMapper.selectById(2L)).thenReturn(mapping);
        MatchEntity match = new MatchEntity();
        match.setId(20L);
        match.setLotteryMatchNo("周三001");
        match.setLotteryDate(LocalDate.of(2026, 7, 24));
        match.setLeagueName("英超");
        match.setHomeTeamName("曼联");
        match.setAwayTeamName("切尔西");
        match.setKickoffTime(Instant.parse("2026-07-24T12:00:00Z"));
        when(matchMapper.selectById(20L)).thenReturn(match);

        MappingReviewDetailVo detail = service.detail(new MappingReviewDetailQueryDto(2L));

        assertThat(detail.match().lotteryMatchNo()).isEqualTo("周三001");
        assertThat(detail.candidates()).hasSize(1);
        assertThat(detail.candidates().get(0).reasons()).contains("HOME_ID");
    }

    @Test
    void confirmUpdatesPendingAndAppendsAudit() {
        MatchSourceMapping pending = pendingMapping(3L, 30L);
        MatchSourceMapping confirmed = pendingMapping(3L, 30L);
        confirmed.setMappingStatus(MappingStatusEnum.MANUAL_CONFIRMED);
        confirmed.setConfirmedBy("admin-1");
        confirmed.setMappingMethod(MatchMappingReviewServiceImpl.METHOD_MANUAL_REVIEW);

        when(matchSourceMappingMapper.selectById(3L)).thenReturn(pending, confirmed);
        when(matchMapper.selectById(30L)).thenReturn(match(30L));
        when(matchSourceMappingMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        MappingReviewDetailVo result = service.confirm(new MappingReviewConfirmDto(3L, null, "admin-1"));

        assertThat(result.mappingStatus()).isEqualTo(MappingStatusEnum.MANUAL_CONFIRMED);
        verify(auditLogService).append(
                eq("admin-1"),
                eq(AuditTargetTypeEnum.MATCH_SOURCE_MAPPING),
                eq("3"),
                eq(AuditActionTypeEnum.CONFIRM),
                eq("mappingStatus"),
                any(),
                any()
        );
    }

    @Test
    void confirmConflictWhenUpdateAffectsZeroRows() {
        MatchSourceMapping pending = pendingMapping(4L, 40L);
        when(matchSourceMappingMapper.selectById(4L)).thenReturn(pending);
        when(matchMapper.selectById(40L)).thenReturn(match(40L));
        when(matchSourceMappingMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);

        assertThatThrownBy(() -> service.confirm(new MappingReviewConfirmDto(4L, null, "admin-1")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("confirm conflict");
        verify(auditLogService, never()).append(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectPendingAndAppendsAudit() {
        MatchSourceMapping pending = pendingMapping(5L, 50L);
        MatchSourceMapping rejected = pendingMapping(5L, 50L);
        rejected.setMappingStatus(MappingStatusEnum.REJECTED);
        rejected.setMappingExplanation("REJECTED: bad");

        when(matchSourceMappingMapper.selectById(5L)).thenReturn(pending, rejected);
        when(matchSourceMappingMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        MappingReviewDetailVo result = service.reject(new MappingReviewRejectDto(5L, "bad", "admin-2"));

        assertThat(result.mappingStatus()).isEqualTo(MappingStatusEnum.REJECTED);
        verify(auditLogService).append(
                eq("admin-2"),
                eq(AuditTargetTypeEnum.MATCH_SOURCE_MAPPING),
                eq("5"),
                eq(AuditActionTypeEnum.REJECT),
                eq("mappingStatus"),
                any(),
                any()
        );
    }

    @Test
    void reopenRejectedBackToPending() {
        MatchSourceMapping rejected = pendingMapping(6L, 60L);
        rejected.setMappingStatus(MappingStatusEnum.REJECTED);
        rejected.setConfirmedBy("admin-x");
        MatchSourceMapping reopened = pendingMapping(6L, 60L);
        reopened.setMappingStatus(MappingStatusEnum.PENDING);
        reopened.setConfirmedBy(null);

        when(matchSourceMappingMapper.selectById(6L)).thenReturn(rejected, reopened);
        when(matchSourceMappingMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        MappingReviewDetailVo result = service.reopen(new MappingReviewReopenDto(6L, "admin-3"));

        assertThat(result.mappingStatus()).isEqualTo(MappingStatusEnum.PENDING);
        verify(auditLogService).append(
                eq("admin-3"),
                eq(AuditTargetTypeEnum.MATCH_SOURCE_MAPPING),
                eq("6"),
                eq(AuditActionTypeEnum.REOPEN),
                eq("mappingStatus"),
                any(),
                any()
        );
    }

    @Test
    void confirmRejectsNonPendingState() {
        MatchSourceMapping auto = pendingMapping(7L, 70L);
        auto.setMappingStatus(MappingStatusEnum.AUTO_CONFIRMED);
        when(matchSourceMappingMapper.selectById(7L)).thenReturn(auto);

        assertThatThrownBy(() -> service.confirm(new MappingReviewConfirmDto(7L, null, "admin-1")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("expected PENDING");
    }

    private MatchSourceMapping pendingMapping(Long id, Long matchId) {
        MatchSourceMapping mapping = new MatchSourceMapping();
        mapping.setId(id);
        mapping.setMatchId(matchId);
        mapping.setProviderCode("THE_ODDS_API");
        mapping.setExternalMatchId("ext-" + id);
        mapping.setMappingStatus(MappingStatusEnum.PENDING);
        mapping.setMappingConfidence(new BigDecimal("0.7000"));
        mapping.setMappingMethod("SCORE_PENDING");
        mapping.setMappingExplanation("PENDING");
        mapping.setUpdatedAt(Instant.parse("2026-07-24T12:00:00Z"));
        return mapping;
    }

    private MatchEntity match(Long id) {
        MatchEntity entity = new MatchEntity();
        entity.setId(id);
        entity.setLotteryMatchNo("周三00" + id);
        entity.setLotteryDate(LocalDate.of(2026, 7, 24));
        return entity;
    }
}
