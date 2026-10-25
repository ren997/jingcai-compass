package com.jingcaicompass.data.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jingcaicompass.data.dto.DataPipelineResultDto;
import com.jingcaicompass.data.dto.ProviderSyncOutcome;
import com.jingcaicompass.data.entity.DataSyncRun;
import com.jingcaicompass.data.enums.DataPipelineStatusEnum;
import com.jingcaicompass.data.enums.SyncStatusEnum;
import com.jingcaicompass.match.dto.SportteryPoolSyncRequestDto;
import com.jingcaicompass.match.dto.SportteryPoolSyncResultDto;
import com.jingcaicompass.match.entity.MatchEntity;
import com.jingcaicompass.match.mapper.MatchMapper;
import com.jingcaicompass.match.service.SportteryPoolSyncService;
import com.jingcaicompass.odds.dto.AsianOddsSyncRequestDto;
import com.jingcaicompass.odds.dto.AsianOddsSyncResultDto;
import com.jingcaicompass.odds.entity.AsianOddsSnapshot;
import com.jingcaicompass.odds.mapper.AsianOddsSnapshotMapper;
import com.jingcaicompass.odds.service.AsianOddsSyncService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

/**
 * 单源同步编排：赛程 → 亚盘 → 覆盖率报告；不使用跨 Provider 全局事务，保留每一阶段的运行记录。
 *
 * <p>历史上这里还有第三阶段「逐场标准化回填」（把供应商联赛/球队名对齐到内部标准实体），
 * 它依赖映射层，随单源改造一并删除：NovaAPI 的 {@code matchId} 就是竞彩场次 ID，
 * 不需要任何名称归一化。</p>
 */
@Service
@ConditionalOnBean(DataSource.class)
public class DataPipelineServiceImpl implements DataPipelineService {

    private static final Logger log = LoggerFactory.getLogger(DataPipelineServiceImpl.class);

    private final SportteryPoolSyncService sportteryPoolSyncService;
    private final AsianOddsSyncService asianOddsSyncService;
    private final MatchMapper matchMapper;
    private final AsianOddsSnapshotMapper asianOddsSnapshotMapper;

    public DataPipelineServiceImpl(
            SportteryPoolSyncService sportteryPoolSyncService,
            AsianOddsSyncService asianOddsSyncService,
            MatchMapper matchMapper,
            AsianOddsSnapshotMapper asianOddsSnapshotMapper
    ) {
        this.sportteryPoolSyncService = sportteryPoolSyncService;
        this.asianOddsSyncService = asianOddsSyncService;
        this.matchMapper = matchMapper;
        this.asianOddsSnapshotMapper = asianOddsSnapshotMapper;
    }

    @Override
    public DataPipelineResultDto run(LocalDate businessDate) {
        // 1) 校验竞彩业务日，流水线不隐式使用系统日期
        Objects.requireNonNull(businessDate, "businessDate must not be null");

        // 2) 先同步赛程；FAILED 时短路，已保存的同步运行记录仍保留
        SportteryPoolSyncResultDto sporttery;
        try {
            sporttery = sportteryPoolSyncService.sync(new SportteryPoolSyncRequestDto(businessDate));
        } catch (RuntimeException exception) {
            log.warn("data pipeline sporttery stage threw businessDate={}", businessDate, exception);
            return failedBeforeOdds(businessDate, truncate(exception.getMessage()));
        }

        ProviderSyncOutcome sportteryOutcome = sporttery == null ? null : sporttery.outcome();
        SyncStatusEnum sportteryStatus = sportteryOutcome == null ? SyncStatusEnum.FAILED : sportteryOutcome.status();
        if (sportteryStatus == SyncStatusEnum.FAILED) {
            return new DataPipelineResultDto(
                    businessDate,
                    DataPipelineStatusEnum.FAILED,
                    syncRunId(sportteryOutcome),
                    sportteryStatus,
                    null,
                    null,
                    sporttery == null ? 0 : sporttery.matchUpsertCount(),
                    sporttery == null ? 0 : sporttery.snapshotInsertCount(),
                    0,
                    0,
                    0,
                    0,
                    false,
                    0,
                    zeroCoverage(),
                    syncError(sportteryOutcome)
            );
        }

        // 3) 同步亚盘并写入快照；异常不回滚赛程阶段
        AsianOddsSyncResultDto asianOdds;
        try {
            asianOdds = asianOddsSyncService.sync(new AsianOddsSyncRequestDto(businessDate));
        } catch (RuntimeException exception) {
            log.warn("data pipeline asian odds stage threw businessDate={}", businessDate, exception);
            int covered = countCoveredMatches(businessDate);
            return new DataPipelineResultDto(
                    businessDate,
                    DataPipelineStatusEnum.PARTIAL,
                    syncRunId(sportteryOutcome),
                    sportteryStatus,
                    null,
                    SyncStatusEnum.FAILED,
                    sporttery.matchUpsertCount(),
                    sporttery.snapshotInsertCount(),
                    0,
                    0,
                    0,
                    0,
                    false,
                    covered,
                    coverage(dayMatchCount(businessDate), covered),
                    truncate(exception.getMessage())
            );
        }

        // 4) 汇总两个同步运行与覆盖率形成单次报告
        ProviderSyncOutcome asianOutcome = asianOdds == null ? null : asianOdds.outcome();
        SyncStatusEnum asianStatus = asianOdds == null || (asianOutcome == null && !asianOdds.quotaBlocked())
                ? SyncStatusEnum.FAILED
                : asianOutcome == null ? null : asianOutcome.status();
        DataPipelineStatusEnum pipelineStatus = calculateStatus(
                sportteryStatus,
                asianStatus,
                asianOdds != null && asianOdds.quotaBlocked()
        );

        return new DataPipelineResultDto(
                businessDate,
                pipelineStatus,
                syncRunId(sportteryOutcome),
                sportteryStatus,
                syncRunId(asianOutcome),
                asianStatus,
                sporttery.matchUpsertCount(),
                sporttery.snapshotInsertCount(),
                asianOdds == null ? 0 : asianOdds.snapshotInsertCount(),
                asianOdds == null ? 0 : asianOdds.skippedUnmapped(),
                asianOdds == null ? 0 : asianOdds.skippedLive(),
                asianOdds == null ? 0 : asianOdds.skippedIncomplete(),
                asianOdds != null && asianOdds.quotaBlocked(),
                asianOdds == null ? 0 : asianOdds.coveredMatchCount(),
                asianOdds == null ? zeroCoverage() : asianOdds.coverageRate(),
                joinErrors(syncError(sportteryOutcome), syncError(asianOutcome))
        );
    }

    private DataPipelineResultDto failedBeforeOdds(LocalDate businessDate, String message) {
        return new DataPipelineResultDto(
                businessDate,
                DataPipelineStatusEnum.FAILED,
                null,
                SyncStatusEnum.FAILED,
                null,
                null,
                0,
                0,
                0,
                0,
                0,
                0,
                false,
                0,
                zeroCoverage(),
                message
        );
    }

    private int countCoveredMatches(LocalDate businessDate) {
        Set<Long> covered = new HashSet<>();
        for (MatchEntity match : findDayMatches(businessDate)) {
            if (match.getId() == null) {
                continue;
            }
            Long count = asianOddsSnapshotMapper.selectCount(new LambdaQueryWrapper<AsianOddsSnapshot>()
                    .eq(AsianOddsSnapshot::getMatchId, match.getId()));
            if (count != null && count > 0) {
                covered.add(match.getId());
            }
        }
        return covered.size();
    }

    private int dayMatchCount(LocalDate businessDate) {
        return findDayMatches(businessDate).size();
    }

    private List<MatchEntity> findDayMatches(LocalDate businessDate) {
        return matchMapper.selectList(new LambdaQueryWrapper<MatchEntity>()
                .eq(MatchEntity::getLotteryDate, businessDate));
    }

    private static DataPipelineStatusEnum calculateStatus(
            SyncStatusEnum sportteryStatus,
            SyncStatusEnum asianStatus,
            boolean quotaBlocked
    ) {
        if (sportteryStatus == SyncStatusEnum.FAILED) {
            return DataPipelineStatusEnum.FAILED;
        }
        if (sportteryStatus == SyncStatusEnum.PARTIAL
                || asianStatus == SyncStatusEnum.FAILED
                || asianStatus == SyncStatusEnum.PARTIAL
                || quotaBlocked) {
            return DataPipelineStatusEnum.PARTIAL;
        }
        return DataPipelineStatusEnum.SUCCESS;
    }

    private static Long syncRunId(ProviderSyncOutcome outcome) {
        return outcome == null || outcome.syncRun() == null ? null : outcome.syncRun().getId();
    }

    private static String syncError(ProviderSyncOutcome outcome) {
        DataSyncRun run = outcome == null ? null : outcome.syncRun();
        return run == null ? null : run.getErrorMessage();
    }

    private static BigDecimal coverage(int total, int covered) {
        if (total <= 0) {
            return zeroCoverage();
        }
        return BigDecimal.valueOf(covered)
                .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal zeroCoverage() {
        return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
    }

    private static String joinErrors(String first, String second) {
        if (first == null || first.isBlank()) {
            return second;
        }
        if (second == null || second.isBlank()) {
            return first;
        }
        return truncate(first + "; " + second);
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
