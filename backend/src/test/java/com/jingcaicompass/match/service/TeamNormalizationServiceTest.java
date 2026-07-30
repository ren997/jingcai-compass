package com.jingcaicompass.match.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.jingcaicompass.match.dto.EntityNormalizeRequestDto;
import com.jingcaicompass.match.dto.EntityNormalizeResultDto;
import com.jingcaicompass.match.entity.ProviderTeamMapping;
import com.jingcaicompass.match.entity.Team;
import com.jingcaicompass.match.entity.TeamAlias;
import com.jingcaicompass.match.enums.EntityNormalizeOutcomeEnum;
import com.jingcaicompass.match.enums.MappingStatusEnum;
import com.jingcaicompass.match.mapper.ProviderTeamMappingMapper;
import com.jingcaicompass.match.mapper.TeamAliasMapper;
import com.jingcaicompass.match.mapper.TeamMapper;
import com.jingcaicompass.match.support.NameNormalizationSupport;
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
class TeamNormalizationServiceTest {

    @Mock
    private TeamMapper teamMapper;

    @Mock
    private TeamAliasMapper teamAliasMapper;

    @Mock
    private ProviderTeamMappingMapper providerTeamMappingMapper;

    private TeamNormalizationServiceImpl service;
    private final AtomicLong idSeq = new AtomicLong(200);

    @BeforeEach
    void setUp() {
        service = new TeamNormalizationServiceImpl(teamMapper, teamAliasMapper, providerTeamMappingMapper);
    }

    @Test
    void confirmedExternalIdTakesPriorityOverSameDisplayName() {
        ProviderTeamMapping mapping = new ProviderTeamMapping();
        mapping.setTeamId(11L);
        mapping.setMappingStatus(MappingStatusEnum.AUTO_CONFIRMED);
        when(providerTeamMappingMapper.selectOne(any(Wrapper.class))).thenReturn(mapping);

        EntityNormalizeResultDto result = service.resolve(
                new EntityNormalizeRequestDto("asianodds", "T-100", "曼联")
        );

        assertThat(result.entityId()).isEqualTo(11L);
        assertThat(result.outcome()).isEqualTo(EntityNormalizeOutcomeEnum.RESOLVED);
        assertThat(result.method()).isEqualTo(TeamNormalizationServiceImpl.METHOD_EXTERNAL_ID);
        verify(teamMapper, never()).insert(any(Team.class));
    }

    @Test
    void theOddsIdentityAlwaysCreatesScopedPendingReviewInsteadOfUsingGlobalAlias() {
        TeamAlias alias = new TeamAlias();
        alias.setTeamId(1L);
        when(providerTeamMappingMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(teamMapper.insert(any(Team.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Team.class).setId(301L);
            return 1;
        });

        EntityNormalizeResultDto result = service.resolve(new EntityNormalizeRequestDto(
                "THE_ODDS_API", "SCOPED_NAME:a1", "Manchester United", "soccer_epl"));

        ArgumentCaptor<ProviderTeamMapping> captor = ArgumentCaptor.forClass(ProviderTeamMapping.class);
        verify(providerTeamMappingMapper).insert(captor.capture());
        assertThat(result.mappingStatus()).isEqualTo(MappingStatusEnum.PENDING);
        assertThat(result.entityId()).isEqualTo(301L);
        assertThat(captor.getValue()).satisfies(mapping -> {
            assertThat(mapping.getExternalScope()).isEqualTo("soccer_epl");
            assertThat(mapping.getExternalDisplayName()).isEqualTo("Manchester United");
            assertThat(mapping.getMappingStatus()).isEqualTo(MappingStatusEnum.PENDING);
        });
        verify(teamAliasMapper, never()).selectOne(any(Wrapper.class));
        verify(teamMapper, never()).selectList(null);
    }

    @Test
    void pendingExternalMappingIsReusedWithoutCreatingAnotherTeam() {
        ProviderTeamMapping mapping = new ProviderTeamMapping();
        mapping.setTeamId(21L);
        mapping.setMappingStatus(MappingStatusEnum.PENDING);
        when(providerTeamMappingMapper.selectOne(any(Wrapper.class))).thenReturn(mapping);
        when(teamAliasMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        EntityNormalizeResultDto result = service.resolve(
                new EntityNormalizeRequestDto("STUB", "NAME:team", "演示主队")
        );

        assertThat(result.entityId()).isEqualTo(21L);
        assertThat(result.outcome()).isEqualTo(EntityNormalizeOutcomeEnum.CANDIDATE_CREATED);
        assertThat(result.mappingStatus()).isEqualTo(MappingStatusEnum.PENDING);
        assertThat(result.method()).isEqualTo(
                TeamNormalizationServiceImpl.METHOD_NAME_CANDIDATE_REUSE
        );
        verify(teamMapper, never()).selectList(null);
        verify(teamMapper, never()).insert(any(Team.class));
    }

    @Test
    void rejectedExternalMappingIsNeverRestoredAutomatically() {
        ProviderTeamMapping mapping = new ProviderTeamMapping();
        mapping.setTeamId(22L);
        mapping.setMappingStatus(MappingStatusEnum.REJECTED);
        when(providerTeamMappingMapper.selectOne(any(Wrapper.class))).thenReturn(mapping);

        EntityNormalizeResultDto result = service.resolve(
                new EntityNormalizeRequestDto("STUB", "NAME:rejected", "未知球队")
        );

        assertThat(result.outcome()).isEqualTo(EntityNormalizeOutcomeEnum.CANDIDATE_CREATED);
        assertThat(result.mappingStatus()).isEqualTo(MappingStatusEnum.REJECTED);
        assertThat(result.method()).isEqualTo(TeamNormalizationServiceImpl.METHOD_REJECTED_REUSE);
        verify(teamAliasMapper, never()).selectOne(any(Wrapper.class));
        verify(providerTeamMappingMapper, never()).updateById(mapping);
    }

    @Test
    void confirmedAliasPromotesExistingPendingExternalMapping() {
        ProviderTeamMapping mapping = new ProviderTeamMapping();
        mapping.setTeamId(23L);
        mapping.setMappingStatus(MappingStatusEnum.PENDING);
        TeamAlias alias = new TeamAlias();
        alias.setTeamId(24L);
        when(providerTeamMappingMapper.selectOne(any(Wrapper.class))).thenReturn(mapping);
        when(teamAliasMapper.selectOne(any(Wrapper.class))).thenReturn(alias);

        EntityNormalizeResultDto result = service.resolve(
                new EntityNormalizeRequestDto("STUB", "NAME:alias", "红魔")
        );

        assertThat(result.entityId()).isEqualTo(24L);
        assertThat(result.outcome()).isEqualTo(EntityNormalizeOutcomeEnum.RESOLVED);
        assertThat(mapping.getTeamId()).isEqualTo(24L);
        assertThat(mapping.getMappingStatus()).isEqualTo(MappingStatusEnum.MANUAL_CONFIRMED);
        verify(providerTeamMappingMapper).updateById(mapping);
    }

    @ParameterizedTest
    @CsvSource({
            "曼联足球俱乐部, 曼联",
            "Arsenal FC, Arsenal"
    })
    void uniqueExactNormalizedNameResolvesIncludingSuffixVariants(String storedName, String incomingName) {
        when(teamAliasMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        Team existing = new Team();
        existing.setId(4L);
        existing.setNameZh(storedName);
        when(teamMapper.selectList(null)).thenReturn(List.of(existing));

        EntityNormalizeResultDto result = service.resolve(
                new EntityNormalizeRequestDto(null, null, incomingName)
        );

        assertThat(result.outcome()).isEqualTo(EntityNormalizeOutcomeEnum.RESOLVED);
        assertThat(result.entityId()).isEqualTo(4L);
        assertThat(result.method()).isEqualTo(TeamNormalizationServiceImpl.METHOD_EXACT_NAME);
    }

    @Test
    void similarButDifferentNamesCreateSeparateCandidates() {
        when(providerTeamMappingMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(teamAliasMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(teamMapper.selectList(null)).thenReturn(List.of());
        when(teamMapper.insert(any(Team.class))).thenAnswer(invocation -> {
            Team team = invocation.getArgument(0);
            team.setId(idSeq.getAndIncrement());
            return 1;
        });

        EntityNormalizeResultDto united = service.resolve(
                new EntityNormalizeRequestDto("asianodds", "U1", "曼联")
        );
        EntityNormalizeResultDto city = service.resolve(
                new EntityNormalizeRequestDto("asianodds", "C1", "曼城")
        );

        assertThat(united.entityId()).isNotEqualTo(city.entityId());
        assertThat(united.outcome()).isEqualTo(EntityNormalizeOutcomeEnum.CANDIDATE_CREATED);
        assertThat(city.outcome()).isEqualTo(EntityNormalizeOutcomeEnum.CANDIDATE_CREATED);
        assertThat(united.method()).isEqualTo(TeamNormalizationServiceImpl.METHOD_NAME_CANDIDATE);
    }

    @Test
    void confirmAliasThenResolveReturnsResolved() {
        when(teamAliasMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(teamMapper.selectList(null)).thenReturn(List.of());
        when(teamMapper.insert(any(Team.class))).thenAnswer(invocation -> {
            Team team = invocation.getArgument(0);
            team.setId(88L);
            return 1;
        });

        EntityNormalizeResultDto created = service.resolve(
                new EntityNormalizeRequestDto(null, null, "利物浦")
        );

        Team team = new Team();
        team.setId(88L);
        team.setNameZh("利物浦");
        when(teamMapper.selectById(88L)).thenReturn(team);
        when(teamAliasMapper.insert(any(TeamAlias.class))).thenReturn(1);

        service.confirmAlias(88L, "Liverpool", "manual", "tester");

        when(teamAliasMapper.selectOne(any(Wrapper.class))).thenAnswer(invocation -> {
            TeamAlias alias = new TeamAlias();
            alias.setTeamId(88L);
            alias.setAliasNormalized(NameNormalizationSupport.normalizedKey("Liverpool"));
            return alias;
        });

        EntityNormalizeResultDto resolved = service.resolve(
                new EntityNormalizeRequestDto(null, null, "Liverpool")
        );
        assertThat(created.outcome()).isEqualTo(EntityNormalizeOutcomeEnum.CANDIDATE_CREATED);
        assertThat(resolved.outcome()).isEqualTo(EntityNormalizeOutcomeEnum.RESOLVED);
        assertThat(resolved.entityId()).isEqualTo(88L);
        assertThat(resolved.method()).isEqualTo(TeamNormalizationServiceImpl.METHOD_ALIAS);
    }
}
