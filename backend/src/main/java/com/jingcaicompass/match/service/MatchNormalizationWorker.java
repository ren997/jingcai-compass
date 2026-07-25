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

    /**
     * 在独立事务中仅补齐指定比赛为空的标准实体 ID。
     *
     * @param matchId 内部比赛 ID
     * @param providerCode 体彩 Provider 业务编码
     * @return 本场是否更新、是否完成及待确认原因
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ItemResult normalize(Long matchId, String providerCode) {
        // 1) 校验来源并在当前独立事务中重新读取比赛
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

        // 2) 仅在联赛 ID 为空时解析，不覆盖已有人工或自动确认值
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

        // 3) 仅在主队 ID 为空时解析
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

        // 4) 仅在客队 ID 为空时解析
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

        // 5) 本场有确认结果时一次性更新，任一异常触发本场整体回滚
        if (updated) {
            matchMapper.updateById(match);
        }

        // 6) 根据最终字段生成完成状态与明确的待确认原因
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

    /**
     * 单场标准化事务结果。
     *
     * @param matchId 内部比赛 ID
     * @param updated 本次是否写入标准实体 ID
     * @param normalized 三个标准实体 ID 是否均已确认
     * @param pendingReasons 未完成时的待确认原因
     */
    public record ItemResult(
            Long matchId,
            boolean updated,
            boolean normalized,
            Set<NormalizationPendingReasonEnum> pendingReasons
    ) {
    }
}
