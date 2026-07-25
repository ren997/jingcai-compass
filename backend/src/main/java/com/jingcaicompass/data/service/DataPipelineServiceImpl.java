package com.jingcaicompass.data.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jingcaicompass.data.dto.DataPipelineResultDto;
import com.jingcaicompass.data.dto.ProviderSyncOutcome;
import com.jingcaicompass.data.entity.DataSyncRun;
import com.jingcaicompass.data.enums.DataPipelineStatusEnum;
import com.jingcaicompass.data.enums.SyncStatusEnum;
import com.jingcaicompass.match.dto.NormalizationBackfillResultDto;
import com.jingcaicompass.match.dto.SportteryPoolSyncRequestDto;
import com.jingcaicompass.match.dto.SportteryPoolSyncResultDto;
import com.jingcaicompass.match.entity.MatchEntity;
import com.jingcaicompass.match.entity.MatchSourceMapping;
import com.jingcaicompass.match.enums.MappingStatusEnum;
import com.jingcaicompass.match.mapper.MatchMapper;
import com.jingcaicompass.match.mapper.MatchSourceMappingMapper;
import com.jingcaicompass.match.service.MatchNormalizationBackfillService;
import com.jingcaicompass.match.service.SportteryPoolSyncService;
import com.jingcaicompass.odds.dto.AsianOddsSyncRequestDto;
import com.jingcaicompass.odds.dto.AsianOddsSyncResultDto;
import com.jingcaicompass.odds.entity.AsianOddsSnapshot;
import com.jingcaicompass.odds.mapper.AsianOddsSnapshotMapper;
import com.jingcaicompass.odds.service.AsianOddsProvider;
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

/** 双源流水线实现；不使用跨 Provider 全局事务，保留每一阶段的运行记录。 */
@Service
@ConditionalOnBean(DataSource.class)
public class DataPipelineServiceImpl implements DataPipelineService {

    private static final Logger log = LoggerFactory.getLogger(DataPipelineServiceImpl.class);

    private final SportteryPoolSyncService sportteryPoolSyncService;
    private final MatchNormalizationBackfillService normalizationBackfillService;
    private final AsianOddsSyncService asianOddsSyncService;
    private final AsianOddsProvider asianOddsProvider;
    private final MatchMapper matchMapper;
    private final MatchSourceMappingMapper matchSourceMappingMapper;
    private final AsianOddsSnapshotMapper asianOddsSnapshotMapper;

    public DataPipelineServiceImpl(
            SportteryPoolSyncService sportteryPoolSyncService,
            MatchNormalizationBackfillService normalizationBackfillService,
            AsianOddsSyncService asianOddsSyncService,
            AsianOddsProvider asianOddsProvider,
            MatchMapper matchMapper,
            MatchSourceMappingMapper matchSourceMappingMapper,
            AsianOddsSnapshotMapper asianOddsSnapshotMapper
    ) {
        this.sportteryPoolSyncService = sportteryPoolSyncService;
        this.normalizationBackfillService = normalizationBackfillService;
        this.asianOddsSyncService = asianOddsSyncService;
        this.asianOddsProvider = asianOddsProvider;
        this.matchMapper = matchMapper;
        this.matchSourceMappingMapper = matchSourceMappingMapper;
        this.asianOddsSnapshotMapper = asianOddsSnapshotMapper;
    }

    @Override
    public DataPipelineResultDto run(LocalDate businessDate) {
        // 1) 校验竞彩业务日，流水线不隐式使用系统日期
        Objects.requireNonNull(businessDate, "businessDate must not be null");

        // 2) 先同步体彩；FAILED 时短路，已保存的同步运行记录仍保留
        SportteryPoolSyncResultDto sporttery;
        try {
            sporttery = sportteryPoolSyncService.sync(new SportteryPoolSyncRequestDto(businessDate));
        } catch (RuntimeException exception) {
            log.warn("data pipeline sporttery stage threw businessDate={}", businessDate, exception);
            return failedBeforeNormalization(businessDate, truncate(exception.getMessage()));
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
                    NormalizationBackfillResultDto.empty(businessDate),
                    0,
                    0,
                    0,
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

        // 3) 对当日已写入比赛逐场执行独立事务标准化
        NormalizationBackfillResultDto normalization = normalizationBackfillService.backfill(businessDate);

        // 4) 同步亚盘并完成比赛映射和快照写入；异常不回滚前两阶段
        AsianOddsSyncResultDto asianOdds;
        try {
            asianOdds = asianOddsSyncService.sync(new AsianOddsSyncRequestDto(businessDate));
        } catch (RuntimeException exception) {
            log.warn("data pipeline asian odds stage threw businessDate={}", businessDate, exception);
            MappingCounts mappingCounts = countMappings(businessDate);
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
                    normalization,
                    mappingCounts.confirmed(),
                    mappingCounts.pending(),
                    covered,
                    0,
                    0,
                    0,
                    0,
                    false,
                    covered,
                    coverage(normalization.totalMatchCount(), covered),
                    truncate(exception.getMessage())
            );
        }

        // 5) 汇总两个同步运行、标准化、映射和覆盖率形成单次报告
        ProviderSyncOutcome asianOutcome = asianOdds == null ? null : asianOdds.outcome();
        SyncStatusEnum asianStatus = asianOdds == null || (asianOutcome == null && !asianOdds.quotaBlocked())
                ? SyncStatusEnum.FAILED
                : asianOutcome == null ? null : asianOutcome.status();
        MappingCounts mappingCounts = countMappings(businessDate);
        DataPipelineStatusEnum pipelineStatus = calculateStatus(
                sportteryStatus,
                normalization,
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
                normalization,
                mappingCounts.confirmed(),
                mappingCounts.pending(),
                asianOdds == null ? 0 : asianOdds.coveredMatchCount(),
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

    private DataPipelineResultDto failedBeforeNormalization(LocalDate businessDate, String message) {
        return new DataPipelineResultDto(
                businessDate,
                DataPipelineStatusEnum.FAILED,
                null,
                SyncStatusEnum.FAILED,
                null,
                null,
                0,
                0,
                NormalizationBackfillResultDto.empty(businessDate),
                0,
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

    private MappingCounts countMappings(LocalDate businessDate) {
        List<Long> matchIds = findDayMatches(businessDate).stream()
                .map(MatchEntity::getId)
                .filter(Objects::nonNull)
                .toList();
        if (matchIds.isEmpty()) {
            return new MappingCounts(0, 0);
        }
        List<MatchSourceMapping> mappings = matchSourceMappingMapper.selectList(
                new LambdaQueryWrapper<MatchSourceMapping>()
                        .eq(MatchSourceMapping::getProviderCode, asianOddsProvider.providerCode())
                        .in(MatchSourceMapping::getMatchId, matchIds)
        );
        int confirmed = 0;
        int pending = 0;
        for (MatchSourceMapping mapping : mappings) {
            if (mapping.getMappingStatus() == MappingStatusEnum.AUTO_CONFIRMED
                    || mapping.getMappingStatus() == MappingStatusEnum.MANUAL_CONFIRMED) {
                confirmed++;
            } else if (mapping.getMappingStatus() == MappingStatusEnum.PENDING) {
                pending++;
            }
        }
        return new MappingCounts(confirmed, pending);
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

    private List<MatchEntity> findDayMatches(LocalDate businessDate) {
        return matchMapper.selectList(new LambdaQueryWrapper<MatchEntity>()
                .eq(MatchEntity::getLotteryDate, businessDate));
    }

    private static DataPipelineStatusEnum calculateStatus(
            SyncStatusEnum sportteryStatus,
            NormalizationBackfillResultDto normalization,
            SyncStatusEnum asianStatus,
            boolean quotaBlocked
    ) {
        if (sportteryStatus == SyncStatusEnum.FAILED) {
            return DataPipelineStatusEnum.FAILED;
        }
        if (sportteryStatus == SyncStatusEnum.PARTIAL
                || normalization.failureCount() > 0
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

    private record MappingCounts(int confirmed, int pending) {
    }
}
