package com.jingcaicompass.match.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.jingcaicompass.match.dto.EntityNormalizeRequestDto;
import com.jingcaicompass.match.dto.EntityNormalizeResultDto;
import com.jingcaicompass.match.entity.League;
import com.jingcaicompass.match.entity.LeagueAlias;
import com.jingcaicompass.match.entity.ProviderLeagueMapping;
import com.jingcaicompass.match.enums.EntityNormalizeOutcomeEnum;
import com.jingcaicompass.match.enums.MappingStatusEnum;
import com.jingcaicompass.match.mapper.LeagueAliasMapper;
import com.jingcaicompass.match.mapper.LeagueMapper;
import com.jingcaicompass.match.mapper.ProviderLeagueMappingMapper;
import com.jingcaicompass.match.support.NameNormalizationSupport;
import com.jingcaicompass.system.exception.BusinessException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LeagueNormalizationServiceTest {

    @Mock
    private LeagueMapper leagueMapper;

    @Mock
    private LeagueAliasMapper leagueAliasMapper;

    @Mock
    private ProviderLeagueMappingMapper providerLeagueMappingMapper;

    private LeagueNormalizationServiceImpl service;
    private final AtomicLong idSeq = new AtomicLong(100);

    @BeforeEach
    void setUp() {
        service = new LeagueNormalizationServiceImpl(leagueMapper, leagueAliasMapper, providerLeagueMappingMapper);
    }

    @Test
    void confirmedExternalIdTakesPriorityOverSameDisplayName() {
        ProviderLeagueMapping mapping = new ProviderLeagueMapping();
        mapping.setLeagueId(7L);
        mapping.setMappingStatus(MappingStatusEnum.MANUAL_CONFIRMED);
        when(providerLeagueMappingMapper.selectOne(any(Wrapper.class))).thenReturn(mapping);

        EntityNormalizeResultDto result = service.resolve(
                new EntityNormalizeRequestDto("sporttery", "L-001", "英超")
        );

        assertThat(result.entityId()).isEqualTo(7L);
        assertThat(result.outcome()).isEqualTo(EntityNormalizeOutcomeEnum.RESOLVED);
        assertThat(result.method()).isEqualTo(LeagueNormalizationServiceImpl.METHOD_EXTERNAL_ID);
        verify(leagueMapper, never()).insert(any(League.class));
    }

    @Test
    void confirmedAliasResolvesStandardLeague() {
        LeagueAlias alias = new LeagueAlias();
        alias.setLeagueId(9L);
        alias.setAliasNormalized(NameNormalizationSupport.normalizedKey("英超联赛"));
        when(leagueAliasMapper.selectOne(any(Wrapper.class))).thenReturn(alias);

        EntityNormalizeResultDto result = service.resolve(
                new EntityNormalizeRequestDto(null, null, "英超联赛")
        );

        assertThat(result.entityId()).isEqualTo(9L);
        assertThat(result.outcome()).isEqualTo(EntityNormalizeOutcomeEnum.RESOLVED);
        assertThat(result.method()).isEqualTo(LeagueNormalizationServiceImpl.METHOD_ALIAS);
        assertThat(result.mappingStatus()).isEqualTo(MappingStatusEnum.MANUAL_CONFIRMED);
    }

    @ParameterizedTest
    @CsvSource({
            "英超, 英 超",
            "Premier League, PREMIER-LEAGUE"
    })
    void uniqueExactNormalizedNameResolves(String storedName, String incomingName) {
        when(leagueAliasMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        League existing = new League();
        existing.setId(3L);
        existing.setNameZh(storedName);
        when(leagueMapper.selectList(null)).thenReturn(List.of(existing));

        EntityNormalizeResultDto result = service.resolve(
                new EntityNormalizeRequestDto(null, null, incomingName)
        );

        assertThat(result.outcome()).isEqualTo(EntityNormalizeOutcomeEnum.RESOLVED);
        assertThat(result.entityId()).isEqualTo(3L);
        assertThat(result.method()).isEqualTo(LeagueNormalizationServiceImpl.METHOD_EXACT_NAME);
        verify(leagueMapper, never()).insert(any(League.class));
    }

    @Test
    void similarButDifferentNamesCreateSeparateCandidates() {
        when(providerLeagueMappingMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(leagueAliasMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(leagueMapper.selectList(null)).thenReturn(List.of());
        when(leagueMapper.insert(any(League.class))).thenAnswer(invocation -> {
            League league = invocation.getArgument(0);
            league.setId(idSeq.getAndIncrement());
            return 1;
        });

        EntityNormalizeResultDto first = service.resolve(
                new EntityNormalizeRequestDto("sporttery", "A1", "曼联")
        );
        EntityNormalizeResultDto second = service.resolve(
                new EntityNormalizeRequestDto("sporttery", "A2", "曼城")
        );

        assertThat(first.outcome()).isEqualTo(EntityNormalizeOutcomeEnum.CANDIDATE_CREATED);
        assertThat(second.outcome()).isEqualTo(EntityNormalizeOutcomeEnum.CANDIDATE_CREATED);
        assertThat(first.entityId()).isNotEqualTo(second.entityId());
        assertThat(first.mappingStatus()).isEqualTo(MappingStatusEnum.PENDING);
        verify(providerLeagueMappingMapper, org.mockito.Mockito.times(2)).insert(any(ProviderLeagueMapping.class));
    }

    @Test
    void confirmAliasThenResolveReturnsResolved() {
        when(leagueAliasMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(leagueMapper.selectList(null)).thenReturn(List.of());
        when(leagueMapper.insert(any(League.class))).thenAnswer(invocation -> {
            League league = invocation.getArgument(0);
            league.setId(55L);
            return 1;
        });

        EntityNormalizeResultDto created = service.resolve(
                new EntityNormalizeRequestDto(null, null, "曼联")
        );
        assertThat(created.outcome()).isEqualTo(EntityNormalizeOutcomeEnum.CANDIDATE_CREATED);

        League league = new League();
        league.setId(55L);
        league.setNameZh("曼联");
        when(leagueMapper.selectById(55L)).thenReturn(league);
        when(leagueAliasMapper.insert(any(LeagueAlias.class))).thenAnswer(invocation -> {
            LeagueAlias alias = invocation.getArgument(0);
            alias.setId(1L);
            return 1;
        });

        LeagueAlias confirmed = service.confirmAlias(55L, "红魔", "manual", "tester");
        assertThat(confirmed.getAliasNormalized()).isEqualTo(NameNormalizationSupport.normalizedKey("红魔"));

        when(leagueAliasMapper.selectOne(any(Wrapper.class))).thenAnswer(invocation -> {
            LeagueAlias alias = new LeagueAlias();
            alias.setLeagueId(55L);
            alias.setAliasNormalized(NameNormalizationSupport.normalizedKey("红魔"));
            return alias;
        });

        EntityNormalizeResultDto resolved = service.resolve(
                new EntityNormalizeRequestDto(null, null, "红魔")
        );
        assertThat(resolved.outcome()).isEqualTo(EntityNormalizeOutcomeEnum.RESOLVED);
        assertThat(resolved.entityId()).isEqualTo(55L);
        assertThat(resolved.method()).isEqualTo(LeagueNormalizationServiceImpl.METHOD_ALIAS);
    }

    @Test
    void confirmAliasRejectsNormalizedConflict() {
        League league = new League();
        league.setId(1L);
        when(leagueMapper.selectById(1L)).thenReturn(league);
        LeagueAlias existing = new LeagueAlias();
        existing.setLeagueId(2L);
        existing.setAliasNormalized(NameNormalizationSupport.normalizedKey("英超"));
        when(leagueAliasMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

        assertThatThrownBy(() -> service.confirmAlias(1L, "英 超", "manual", "tester"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already confirmed");
    }

    @Test
    void candidateWithoutExternalIdDoesNotWriteMapping() {
        when(leagueAliasMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(leagueMapper.selectList(null)).thenReturn(List.of());
        when(leagueMapper.insert(any(League.class))).thenAnswer(invocation -> {
            League league = invocation.getArgument(0);
            league.setId(66L);
            return 1;
        });

        EntityNormalizeResultDto result = service.resolve(
                new EntityNormalizeRequestDto(null, null, "西甲")
        );

        assertThat(result.outcome()).isEqualTo(EntityNormalizeOutcomeEnum.CANDIDATE_CREATED);
        assertThat(result.mappingStatus()).isNull();
        verify(providerLeagueMappingMapper, never()).insert(any(ProviderLeagueMapping.class));

        ArgumentCaptor<League> captor = ArgumentCaptor.forClass(League.class);
        verify(leagueMapper).insert(captor.capture());
        assertThat(captor.getValue().getNameZh()).isEqualTo("西甲");
    }
}
