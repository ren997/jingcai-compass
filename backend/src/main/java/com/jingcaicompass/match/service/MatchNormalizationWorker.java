package com.jingcaicompass.match.service;

import com.jingcaicompass.match.dto.EntityNormalizeRequestDto;
import com.jingcaicompass.match.dto.EntityNormalizeResultDto;
import com.jingcaicompass.match.entity.MatchEntity;
import com.jingcaicompass.match.enums.EntityNormalizeOutcomeEnum;
import com.jingcaicompass.match.enums.MappingStatusEnum;
import com.jingcaicompass.match.enums.NormalizationPendingReasonEnum;
import com.jingcaicompass.match.mapper.MatchMapper;
import com.jingcaicompass.match.support.ProviderEntityKeySupport;
import java.util.EnumSet;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 单场标准化事务，保证任一字段失败时本场整体回滚。 */
@Component
@ConditionalOnBean(DataSource.class)
public class MatchNormalizationWorker {

    private final MatchMapper matchMapper;
    private final LeagueNormalizationService leagueNormalizationService;
    private final TeamNormalizationService teamNormalizationService;

    public MatchNormalizationWorker(
            MatchMapper matchMapper,
            LeagueNormalizationService leagueNormalizationService,
            TeamNormalizationService teamNormalizationService
    ) {
        this.matchMapper = matchMapper;
        this.leagueNormalizationService = leagueNormalizationService;
        this.teamNormalizationService = teamNormalizationService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ItemResult normalize(Long matchId, String providerCode) {
        if (matchId == null) {
            throw new IllegalArgumentException("matchId must not be null");
        }
        if (!StringUtils.hasText(providerCode)) {
            throw new IllegalArgumentException("providerCode must not be blank");
        }
        MatchEntity match = matchMapper.selectById(matchId);
        if (match == null) {
            throw new IllegalArgumentException("match not found: " + matchId);
        }

        boolean updated = false;
        EnumSet<NormalizationPendingReasonEnum> pendingReasons =
                EnumSet.noneOf(NormalizationPendingReasonEnum.class);

        if (match.getLeagueId() == null) {
            EntityNormalizeResultDto result = leagueNormalizationService.resolve(request(
                    providerCode,
                    match.getLeagueName()
            ));
            if (isConfirmed(result)) {
                match.setLeagueId(result.entityId());
                updated = true;
            } else {
                pendingReasons.add(NormalizationPendingReasonEnum.LEAGUE_PENDING);
            }
        }

        if (match.getHomeTeamId() == null) {
            EntityNormalizeResultDto result = teamNormalizationService.resolve(request(
                    providerCode,
                    match.getHomeTeamName()
            ));
            if (isConfirmed(result)) {
                match.setHomeTeamId(result.entityId());
                updated = true;
            } else {
                pendingReasons.add(NormalizationPendingReasonEnum.HOME_TEAM_PENDING);
            }
        }

        if (match.getAwayTeamId() == null) {
            EntityNormalizeResultDto result = teamNormalizationService.resolve(request(
                    providerCode,
                    match.getAwayTeamName()
            ));
            if (isConfirmed(result)) {
                match.setAwayTeamId(result.entityId());
                updated = true;
            } else {
                pendingReasons.add(NormalizationPendingReasonEnum.AWAY_TEAM_PENDING);
            }
        }

        if (updated) {
            matchMapper.updateById(match);
        }

        boolean normalized = match.getLeagueId() != null
                && match.getHomeTeamId() != null
                && match.getAwayTeamId() != null;
        if (!normalized) {
            if (match.getLeagueId() == null) {
                pendingReasons.add(NormalizationPendingReasonEnum.LEAGUE_PENDING);
            }
            if (match.getHomeTeamId() == null) {
                pendingReasons.add(NormalizationPendingReasonEnum.HOME_TEAM_PENDING);
            }
            if (match.getAwayTeamId() == null) {
                pendingReasons.add(NormalizationPendingReasonEnum.AWAY_TEAM_PENDING);
            }
        }
        return new ItemResult(match.getId(), updated, normalized, Set.copyOf(pendingReasons));
    }

    private static EntityNormalizeRequestDto request(String providerCode, String displayName) {
        return new EntityNormalizeRequestDto(
                providerCode.trim(),
                ProviderEntityKeySupport.nameKey(displayName),
                displayName
        );
    }

    private static boolean isConfirmed(EntityNormalizeResultDto result) {
        if (result == null
                || result.entityId() == null
                || result.outcome() != EntityNormalizeOutcomeEnum.RESOLVED) {
            return false;
        }
        return result.mappingStatus() != MappingStatusEnum.PENDING
                && result.mappingStatus() != MappingStatusEnum.REJECTED;
    }

    public record ItemResult(
            Long matchId,
            boolean updated,
            boolean normalized,
            Set<NormalizationPendingReasonEnum> pendingReasons
    ) {
    }
}
