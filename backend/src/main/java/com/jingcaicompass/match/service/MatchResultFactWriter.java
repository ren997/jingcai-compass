package com.jingcaicompass.match.service;

import com.jingcaicompass.audit.enums.AuditActionTypeEnum;
import com.jingcaicompass.audit.enums.AuditTargetTypeEnum;
import com.jingcaicompass.audit.service.AuditLogService;
import com.jingcaicompass.match.dto.SportteryMatchResultDto;
import com.jingcaicompass.match.entity.MatchEntity;
import com.jingcaicompass.match.entity.MatchResultFact;
import com.jingcaicompass.match.enums.MatchResultFactStatusEnum;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import com.jingcaicompass.match.mapper.MatchMapper;
import com.jingcaicompass.match.mapper.MatchResultFactMapper;
import java.time.Instant;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 单条官方赛果的事务写入器：追加事实、更新投影并审计。 */
@Component
@ConditionalOnBean(DataSource.class)
public class MatchResultFactWriter {

    static final String SYSTEM_OPERATOR = "system:match-result-sync-job";

    private final MatchMapper matchMapper;
    private final MatchResultFactMapper factMapper;
    private final AuditLogService auditLogService;

    public MatchResultFactWriter(
            MatchMapper matchMapper,
            MatchResultFactMapper factMapper,
            AuditLogService auditLogService
    ) {
        this.matchMapper = matchMapper;
        this.factMapper = factMapper;
        this.auditLogService = auditLogService;
    }

    /** 在独立事务内写入一条赛果，失败时不影响同批次其他比赛。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WriteResult write(SportteryMatchResultDto result, Long rawDataPayloadId) {
        IncomingFact incoming = validateAndMap(result, rawDataPayloadId);

        // 1) 锁定既有比赛，禁止赛果响应反向创建未知比赛。
        MatchEntity match = matchMapper.selectByLotteryIdentityForUpdate(
                result.lotteryDate(),
                result.lotteryMatchNo()
        );
        if (match == null) {
            throw new IllegalArgumentException(
                    "match not found for lottery identity: " + result.lotteryDate() + "/" + result.lotteryMatchNo()
            );
        }

        // 2) 比较当前事实，拒绝非法回退或未标记的终态改写。
        MatchResultFact current = factMapper.selectCurrentByMatchId(match.getId());
        if (current != null && sameContent(current, incoming)) {
            return new WriteResult(WriteOutcome.UNCHANGED, current.getId());
        }
        if (current != null) {
            validateReplacement(current, incoming, result.amended());
            int demotedRows = factMapper.markNotCurrent(current.getId());
            if (demotedRows != 1) {
                throw new IllegalStateException("current match result fact demotion conflict: " + current.getId());
            }
        }

        // 3) 追加新事实并在同一事务更新当前比赛投影。
        MatchResultFact created = createFact(match.getId(), current, incoming);
        factMapper.insert(created);
        updateCurrentProjection(match, incoming);

        // 4) 追加同步或替代审计，审计失败将回滚本条事实与投影。
        auditLogService.append(
                SYSTEM_OPERATOR,
                AuditTargetTypeEnum.MATCH_RESULT_FACT,
                String.valueOf(created.getId()),
                current == null ? AuditActionTypeEnum.SYNC : AuditActionTypeEnum.SUPERSEDE,
                "matchResultFact",
                current == null ? null : snapshot(current),
                snapshot(created)
        );
        return new WriteResult(current == null ? WriteOutcome.APPENDED : WriteOutcome.SUPERSEDED, created.getId());
    }

    private IncomingFact validateAndMap(SportteryMatchResultDto result, Long rawDataPayloadId) {
        if (result == null) {
            throw new IllegalArgumentException("sporttery match result must not be null");
        }
        if (!StringUtils.hasText(result.matchId())) {
            throw new IllegalArgumentException("matchId must not be blank");
        }
        if (result.lotteryDate() == null) {
            throw new IllegalArgumentException("lotteryDate must not be null");
        }
        if (!StringUtils.hasText(result.lotteryMatchNo())) {
            throw new IllegalArgumentException("lotteryMatchNo must not be blank");
        }
        if (result.matchStatus() == null) {
            throw new IllegalArgumentException("matchStatus must not be null");
        }
        if (result.providerUpdatedAt() == null) {
            throw new IllegalArgumentException("providerUpdatedAt must not be null");
        }
        if (rawDataPayloadId == null) {
            throw new IllegalArgumentException("rawDataPayloadId must not be null");
        }

        Integer homeScore = result.homeScore();
        Integer awayScore = result.awayScore();
        Instant providerUpdatedAt = result.providerUpdatedAt().toInstant();
        if (result.officialVoid()) {
            if (homeScore != null || awayScore != null) {
                throw new IllegalArgumentException("official VOID result must not contain scores");
            }
            return new IncomingFact(
                    MatchResultFactStatusEnum.VOID,
                    result.matchStatus(),
                    null,
                    null,
                    providerUpdatedAt,
                    rawDataPayloadId
            );
        }
        if (result.matchStatus() == MatchStatusEnum.FINISHED) {
            if (homeScore == null || awayScore == null) {
                throw new IllegalArgumentException("FINISHED result requires both scores");
            }
            if (homeScore < 0 || awayScore < 0) {
                throw new IllegalArgumentException("FINISHED result scores must not be negative");
            }
            return new IncomingFact(
                    MatchResultFactStatusEnum.FINAL,
                    result.matchStatus(),
                    homeScore,
                    awayScore,
                    providerUpdatedAt,
                    rawDataPayloadId
            );
        }
        if (homeScore != null || awayScore != null) {
            throw new IllegalArgumentException("non-final result must not contain scores");
        }
        return new IncomingFact(
                MatchResultFactStatusEnum.PENDING,
                result.matchStatus(),
                null,
                null,
                providerUpdatedAt,
                rawDataPayloadId
        );
    }

    private void validateReplacement(MatchResultFact current, IncomingFact incoming, boolean amended) {
        if (!incoming.providerUpdatedAt().isAfter(current.getProviderUpdatedAt())) {
            throw new IllegalArgumentException("changed result must have a later providerUpdatedAt");
        }
        if (current.getFactStatus() != MatchResultFactStatusEnum.PENDING) {
            if (incoming.factStatus() == MatchResultFactStatusEnum.PENDING) {
                throw new IllegalArgumentException("terminal result fact cannot regress to PENDING");
            }
            if (!amended) {
                throw new IllegalArgumentException("changed terminal result requires amended=true");
            }
        }
    }

    private MatchResultFact createFact(Long matchId, MatchResultFact current, IncomingFact incoming) {
        MatchResultFact fact = new MatchResultFact();
        fact.setMatchId(matchId);
        fact.setFactVersion(current == null ? 1 : current.getFactVersion() + 1);
        fact.setSupersedesFactVersion(current == null ? null : current.getFactVersion());
        fact.setFactStatus(incoming.factStatus());
        fact.setMatchStatus(incoming.matchStatus());
        fact.setHomeScore(incoming.homeScore());
        fact.setAwayScore(incoming.awayScore());
        fact.setRawDataPayloadId(incoming.rawDataPayloadId());
        fact.setProviderUpdatedAt(incoming.providerUpdatedAt());
        fact.setIsCurrent(true);
        return fact;
    }

    private void updateCurrentProjection(MatchEntity match, IncomingFact incoming) {
        match.setMatchStatus(incoming.matchStatus());
        match.setHomeScore(incoming.homeScore());
        match.setAwayScore(incoming.awayScore());
        int rows = matchMapper.updateById(match);
        if (rows != 1) {
            throw new IllegalStateException("match projection update conflict: " + match.getId());
        }
    }

    private boolean sameContent(MatchResultFact current, IncomingFact incoming) {
        return current.getFactStatus() == incoming.factStatus()
                && current.getMatchStatus() == incoming.matchStatus()
                && java.util.Objects.equals(current.getHomeScore(), incoming.homeScore())
                && java.util.Objects.equals(current.getAwayScore(), incoming.awayScore());
    }

    private String snapshot(MatchResultFact fact) {
        return "version=" + fact.getFactVersion()
                + ";factStatus=" + fact.getFactStatus()
                + ";matchStatus=" + fact.getMatchStatus()
                + ";homeScore=" + fact.getHomeScore()
                + ";awayScore=" + fact.getAwayScore()
                + ";providerUpdatedAt=" + fact.getProviderUpdatedAt()
                + ";rawPayloadId=" + fact.getRawDataPayloadId();
    }

    private record IncomingFact(
            MatchResultFactStatusEnum factStatus,
            MatchStatusEnum matchStatus,
            Integer homeScore,
            Integer awayScore,
            Instant providerUpdatedAt,
            Long rawDataPayloadId
    ) {
    }

    public enum WriteOutcome {
        APPENDED,
        SUPERSEDED,
        UNCHANGED
    }

    /** 单条事务处理结果。 */
    public record WriteResult(WriteOutcome outcome, Long factId) {
    }
}
