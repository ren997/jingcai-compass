package com.jingcaicompass.match.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jingcaicompass.match.dto.NormalizationBackfillResultDto;
import com.jingcaicompass.match.dto.NormalizationFailureDto;
import com.jingcaicompass.match.entity.MatchEntity;
import com.jingcaicompass.match.enums.NormalizationPendingReasonEnum;
import com.jingcaicompass.match.mapper.MatchMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

/** 按业务日逐场执行独立事务的标准化回填。 */
@Service
@ConditionalOnBean(DataSource.class)
public class MatchNormalizationBackfillServiceImpl implements MatchNormalizationBackfillService {

    private static final Logger log = LoggerFactory.getLogger(MatchNormalizationBackfillServiceImpl.class);
    private static final int MAX_FAILURE_DETAILS = 20;

    private final MatchMapper matchMapper;
    private final MatchNormalizationWorker worker;
    private final SportteryProvider sportteryProvider;

    public MatchNormalizationBackfillServiceImpl(
            MatchMapper matchMapper,
            MatchNormalizationWorker worker,
            SportteryProvider sportteryProvider
    ) {
        this.matchMapper = matchMapper;
        this.worker = worker;
        this.sportteryProvider = sportteryProvider;
    }

    @Override
    public NormalizationBackfillResultDto backfill(LocalDate businessDate) {
        // 1) 校验业务日并按 ID 稳定读取当日已有比赛
        Objects.requireNonNull(businessDate, "businessDate must not be null");
        List<MatchEntity> matches = matchMapper.selectList(new LambdaQueryWrapper<MatchEntity>()
                .eq(MatchEntity::getLotteryDate, businessDate)
                .orderByAsc(MatchEntity::getId));
        if (matches == null || matches.isEmpty()) {
            return NormalizationBackfillResultDto.empty(businessDate);
        }

        // 2) 初始化完成、待确认、失败和实际更新计数
        int normalizedCount = 0;
        int pendingCount = 0;
        int failureCount = 0;
        int updatedCount = 0;
        Map<NormalizationPendingReasonEnum, Integer> pendingReasons =
                new EnumMap<>(NormalizationPendingReasonEnum.class);
        List<NormalizationFailureDto> failures = new ArrayList<>();

        // 3) 每场通过 REQUIRES_NEW 独立处理，单场异常只进入失败摘要
        for (MatchEntity match : matches) {
            try {
                MatchNormalizationWorker.ItemResult result =
                        worker.normalize(match.getId(), sportteryProvider.providerCode());
                if (result.updated()) {
                    updatedCount++;
                }
                if (result.normalized()) {
                    normalizedCount++;
                } else {
                    pendingCount++;
                    for (NormalizationPendingReasonEnum reason : result.pendingReasons()) {
                        pendingReasons.merge(reason, 1, Integer::sum);
                    }
                }
            } catch (RuntimeException exception) {
                failureCount++;
                log.warn(
                        "match normalization failed businessDate={} matchId={} lotteryMatchNo={}",
                        businessDate,
                        match.getId(),
                        match.getLotteryMatchNo(),
                        exception
                );
                if (failures.size() < MAX_FAILURE_DETAILS) {
                    failures.add(new NormalizationFailureDto(
                            match.getId(),
                            match.getLotteryMatchNo(),
                            truncate(exception.getMessage())
                    ));
                }
            }
        }

        // 4) 返回有限失败明细和完整分类计数
        return new NormalizationBackfillResultDto(
                businessDate,
                matches.size(),
                normalizedCount,
                pendingCount,
                failureCount,
                updatedCount,
                Map.copyOf(pendingReasons),
                List.copyOf(failures)
        );
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
