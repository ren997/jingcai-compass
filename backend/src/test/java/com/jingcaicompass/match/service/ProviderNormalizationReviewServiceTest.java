package com.jingcaicompass.match.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jingcaicompass.audit.enums.AuditActionTypeEnum;
import com.jingcaicompass.audit.enums.AuditTargetTypeEnum;
import com.jingcaicompass.audit.mapper.AuditLogMapper;
import com.jingcaicompass.audit.service.AuditLogService;
import com.jingcaicompass.match.dto.ProviderNormalizationCandidateQueryDto;
import com.jingcaicompass.match.dto.ProviderNormalizationReviewConfirmDto;
import com.jingcaicompass.match.dto.ProviderNormalizationReviewListQueryDto;
import com.jingcaicompass.match.dto.ProviderNormalizationReviewRejectDto;
import com.jingcaicompass.match.dto.ProviderNormalizationReviewReopenDto;
import com.jingcaicompass.match.entity.League;
import com.jingcaicompass.match.entity.ProviderLeagueMapping;
import com.jingcaicompass.match.enums.MappingStatusEnum;
import com.jingcaicompass.match.enums.ProviderNormalizationEntityTypeEnum;
import com.jingcaicompass.match.mapper.LeagueMapper;
import com.jingcaicompass.match.mapper.ProviderLeagueMappingMapper;
import com.jingcaicompass.match.mapper.ProviderTeamMappingMapper;
import com.jingcaicompass.match.mapper.TeamMapper;
import com.jingcaicompass.match.vo.ProviderNormalizationReviewDetailVo;
import com.jingcaicompass.match.vo.ProviderNormalizationEntityVo;
import com.jingcaicompass.system.config.properties.PaginationProperties;
import com.jingcaicompass.system.exception.BusinessException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProviderNormalizationReviewServiceTest {

    @Mock private ProviderLeagueMappingMapper providerLeagueMappingMapper;
    @Mock private ProviderTeamMappingMapper providerTeamMappingMapper;
    @Mock private LeagueMapper leagueMapper;
    @Mock private TeamMapper teamMapper;
    @Mock private AuditLogMapper auditLogMapper;
    @Mock private AuditLogService auditLogService;

    private ProviderNormalizationReviewServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProviderNormalizationReviewServiceImpl(
                providerLeagueMappingMapper, providerTeamMappingMapper, leagueMapper, teamMapper,
                auditLogMapper, auditLogService, new PaginationProperties(100));
    }

    @Test
    void listDefaultsToPendingAndReturnsCapturedProviderIdentity() {
        ProviderLeagueMapping mapping = pendingLeagueMapping(1L, 10L, "soccer_epl");
        Page<ProviderLeagueMapping> page = new Page<>(1, 100);
        page.setRecords(List.of(mapping));
        page.setTotal(1);
        League provisional = league(10L, "soccer_epl");
        when(providerLeagueMappingMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);
        when(leagueMapper.selectBatchIds(List.of(10L))).thenReturn(List.of(provisional));

        var result = service.list(new ProviderNormalizationReviewListQueryDto(
                ProviderNormalizationEntityTypeEnum.LEAGUE, "THE_ODDS_API", null, 1, 500));

        assertThat(result.pageSize()).isEqualTo(100);
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.records().getFirst()).satisfies(item -> {
            assertThat(item.mappingStatus()).isEqualTo(MappingStatusEnum.PENDING);
            assertThat(item.externalId()).isEqualTo("soccer_epl");
            assertThat(item.externalDisplayName()).isEqualTo("soccer_epl");
            assertThat(item.currentEntity().entityId()).isEqualTo(10L);
        });
    }

    @Test
    void sportteryMappingsAreNotListedOrOperableAsProviderReviews() {
        var list = service.list(new ProviderNormalizationReviewListQueryDto(
                ProviderNormalizationEntityTypeEnum.LEAGUE, "CHINA_SPORTTERY", MappingStatusEnum.PENDING, 1, 20));
        assertThat(list.records()).isEmpty();
        assertThat(list.total()).isZero();
        verify(providerLeagueMappingMapper, never()).selectPage(any(Page.class), any(Wrapper.class));

        ProviderLeagueMapping sporttery = pendingLeagueMapping(11L, 110L, "NAME:brazil-serie-a");
        sporttery.setProviderCode("CHINA_SPORTTERY");
        when(providerLeagueMappingMapper.selectById(11L)).thenReturn(sporttery);

        assertThatThrownBy(() -> service.confirm(new ProviderNormalizationReviewConfirmDto(
                ProviderNormalizationEntityTypeEnum.LEAGUE, 11L, 111L), "operator"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(com.jingcaicompass.system.exception.ErrorCode.NORMALIZATION_PROVIDER_NOT_REVIEWABLE);
        verify(providerLeagueMappingMapper, never()).update(any(), any(Wrapper.class));
        verify(auditLogService, never()).append(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void candidatesSearchTheInternalLeagueDictionaryForReviewableProviders() {
        ProviderLeagueMapping mapping = pendingLeagueMapping(12L, 120L, "soccer_brazil_campeonato");
        when(providerLeagueMappingMapper.selectById(12L)).thenReturn(mapping);
        when(leagueMapper.selectList(any(Wrapper.class))).thenReturn(List.of(league(121L, "巴西甲级联赛")));

        List<ProviderNormalizationEntityVo> result = service.candidates(new ProviderNormalizationCandidateQueryDto(
                ProviderNormalizationEntityTypeEnum.LEAGUE, 12L, "巴西"));

        assertThat(result).containsExactly(new ProviderNormalizationEntityVo(121L, "巴西甲级联赛", null));
        verify(leagueMapper).selectList(any(Wrapper.class));
    }

    @Test
    void confirmChangesOnlyPendingProviderMappingAndAppendsAudit() {
        ProviderLeagueMapping pending = pendingLeagueMapping(2L, 20L, "soccer_epl");
        ProviderLeagueMapping confirmed = pendingLeagueMapping(2L, 99L, "soccer_epl");
        confirmed.setMappingStatus(MappingStatusEnum.MANUAL_CONFIRMED);
        confirmed.setMappingConfidence(BigDecimal.ONE);
        confirmed.setMappingMethod(ProviderNormalizationReviewServiceImpl.METHOD_MANUAL_NORMALIZATION_REVIEW);
        when(providerLeagueMappingMapper.selectById(2L)).thenReturn(pending, confirmed);
        when(leagueMapper.selectById(99L)).thenReturn(league(99L, "英格兰超级联赛"));
        when(auditLogMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(providerLeagueMappingMapper.update(eq(null), any(Wrapper.class))).thenReturn(1);

        ProviderNormalizationReviewDetailVo result = service.confirm(new ProviderNormalizationReviewConfirmDto(
                ProviderNormalizationEntityTypeEnum.LEAGUE, 2L, 99L), "operator");

        assertThat(result.mappingStatus()).isEqualTo(MappingStatusEnum.MANUAL_CONFIRMED);
        assertThat(result.currentEntity().entityId()).isEqualTo(99L);
        verify(auditLogService).append(eq("operator"), eq(AuditTargetTypeEnum.PROVIDER_LEAGUE_MAPPING),
                eq("2"), eq(AuditActionTypeEnum.CONFIRM), eq("mappingStatus"), any(), any());
        verify(providerTeamMappingMapper, never()).update(any(), any(Wrapper.class));
    }

    @Test
    void confirmRejectsTheProvisionalEntityAndConditionalUpdateConflict() {
        ProviderLeagueMapping pending = pendingLeagueMapping(3L, 30L, "soccer_epl");
        when(providerLeagueMappingMapper.selectById(3L)).thenReturn(pending);

        assertThatThrownBy(() -> service.confirm(new ProviderNormalizationReviewConfirmDto(
                ProviderNormalizationEntityTypeEnum.LEAGUE, 3L, 30L), "operator"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("separate existing internal entity");

        when(leagueMapper.selectById(31L)).thenReturn(league(31L, "英超"));
        when(providerLeagueMappingMapper.update(eq(null), any(Wrapper.class))).thenReturn(0);
        assertThatThrownBy(() -> service.confirm(new ProviderNormalizationReviewConfirmDto(
                ProviderNormalizationEntityTypeEnum.LEAGUE, 3L, 31L), "operator"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("confirm conflict");
        verify(auditLogService, never()).append(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectThenReopenUsesConditionalStatesAndAppendOnlyAudit() {
        ProviderLeagueMapping pending = pendingLeagueMapping(4L, 40L, "soccer_epl");
        ProviderLeagueMapping rejected = pendingLeagueMapping(4L, 40L, "soccer_epl");
        rejected.setMappingStatus(MappingStatusEnum.REJECTED);
        ProviderLeagueMapping reopened = pendingLeagueMapping(4L, 40L, "soccer_epl");
        when(providerLeagueMappingMapper.selectById(4L)).thenReturn(pending, rejected, rejected, reopened);
        when(providerLeagueMappingMapper.update(eq(null), any(Wrapper.class))).thenReturn(1);
        when(leagueMapper.selectById(40L)).thenReturn(league(40L, "soccer_epl"));
        when(auditLogMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        var rejectResult = service.reject(new ProviderNormalizationReviewRejectDto(
                ProviderNormalizationEntityTypeEnum.LEAGUE, 4L, "不是目标联赛"), "operator");
        var reopenResult = service.reopen(new ProviderNormalizationReviewReopenDto(
                ProviderNormalizationEntityTypeEnum.LEAGUE, 4L), "operator");

        assertThat(rejectResult.mappingStatus()).isEqualTo(MappingStatusEnum.REJECTED);
        assertThat(reopenResult.mappingStatus()).isEqualTo(MappingStatusEnum.PENDING);
        verify(auditLogService).append(eq("operator"), eq(AuditTargetTypeEnum.PROVIDER_LEAGUE_MAPPING),
                eq("4"), eq(AuditActionTypeEnum.REJECT), eq("mappingStatus"), any(), any());
        verify(auditLogService).append(eq("operator"), eq(AuditTargetTypeEnum.PROVIDER_LEAGUE_MAPPING),
                eq("4"), eq(AuditActionTypeEnum.REOPEN), eq("mappingStatus"), any(), any());
    }

    private ProviderLeagueMapping pendingLeagueMapping(Long id, Long leagueId, String sportKey) {
        ProviderLeagueMapping mapping = new ProviderLeagueMapping();
        mapping.setId(id);
        mapping.setLeagueId(leagueId);
        mapping.setProviderCode("THE_ODDS_API");
        mapping.setExternalLeagueId(sportKey);
        mapping.setExternalScope(sportKey);
        mapping.setExternalDisplayName(sportKey);
        mapping.setExternalNormalizedKey(sportKey);
        mapping.setMappingStatus(MappingStatusEnum.PENDING);
        mapping.setMappingConfidence(new BigDecimal("0.5000"));
        mapping.setMappingMethod("NAME_CANDIDATE");
        mapping.setUpdatedAt(Instant.parse("2026-07-30T01:00:00Z"));
        return mapping;
    }

    private League league(Long id, String name) {
        League league = new League();
        league.setId(id);
        league.setNameZh(name);
        return league;
    }
}
