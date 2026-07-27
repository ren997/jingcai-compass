package com.jingcaicompass.statistics.service;

import com.jingcaicompass.history.mapper.HistoryQueryMapper;
import com.jingcaicompass.history.service.HistoryRecordAssembler;
import com.jingcaicompass.history.vo.HistoryListItemVo;
import com.jingcaicompass.statistics.dto.StatisticsQueryCriteriaDto;
import com.jingcaicompass.statistics.dto.StatisticsSummaryQueryDto;
import com.jingcaicompass.statistics.vo.LeagueStatisticsVo;
import com.jingcaicompass.statistics.vo.ModelVersionStatisticsVo;
import com.jingcaicompass.statistics.vo.StatisticsAppliedFilterVo;
import com.jingcaicompass.statistics.vo.StatisticsMetricsVo;
import com.jingcaicompass.statistics.vo.StatisticsSummaryVo;
import com.jingcaicompass.statistics.vo.StatisticsWindowVo;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 使用当前事实和当前结算生成公开统计及分组对照窗口。 */
@Service
@ConditionalOnBean(DataSource.class)
public class StatisticsQueryServiceImpl implements StatisticsQueryService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final HistoryQueryMapper historyQueryMapper;
    private final HistoryRecordAssembler historyRecordAssembler;
    private final Clock clock;
    private final StatisticsCalculator statisticsCalculator;

    public StatisticsQueryServiceImpl(
            HistoryQueryMapper historyQueryMapper,
            HistoryRecordAssembler historyRecordAssembler,
            Clock clock
    ) {
        this.historyQueryMapper = historyQueryMapper;
        this.historyRecordAssembler = historyRecordAssembler;
        this.clock = clock;
        this.statisticsCalculator = new StatisticsCalculator();
    }

    @Override
    public StatisticsSummaryVo summary(StatisticsSummaryQueryDto query) {
        // 1) 以请求 endDate 或上海当天固定统计锚点，并校验请求范围。
        LocalDate asOfDate = query != null && query.endDate() != null
                ? query.endDate()
                : LocalDate.now(clock.withZone(SHANGHAI));
        LocalDate requestedStart = query != null && query.startDate() != null
                ? query.startDate()
                : asOfDate.minusDays(29);
        if (requestedStart.isAfter(asOfDate)) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "startDate must not be after endDate");
        }
        Long leagueId = query == null ? null : query.leagueId();
        String modelVersion = query == null || !StringUtils.hasText(query.modelVersion())
                ? null
                : query.modelVersion().trim();

        // 2) 分别读取请求、近 7 天和近 30 天当前口径记录。
        List<HistoryListItemVo> requestedRecords = recordsFor(requestedStart, asOfDate, leagueId, modelVersion);
        StatisticsWindowVo requestedWindow = window(requestedStart, asOfDate, requestedRecords);
        StatisticsWindowVo trailingSevenDays = window(
                asOfDate.minusDays(6),
                asOfDate,
                recordsFor(asOfDate.minusDays(6), asOfDate, leagueId, modelVersion)
        );
        StatisticsWindowVo trailingThirtyDays = window(
                asOfDate.minusDays(29),
                asOfDate,
                recordsFor(asOfDate.minusDays(29), asOfDate, leagueId, modelVersion)
        );

        // 3) 仅对请求范围分组，避免固定对照窗口重复放大响应内容。
        return new StatisticsSummaryVo(
                asOfDate,
                new StatisticsAppliedFilterVo(leagueId, modelVersion),
                requestedWindow,
                trailingSevenDays,
                trailingThirtyDays,
                byLeague(requestedRecords),
                byModelVersion(requestedRecords)
        );
    }

    private List<HistoryListItemVo> recordsFor(
            LocalDate startDate,
            LocalDate endDate,
            Long leagueId,
            String modelVersion
    ) {
        List<Long> predictionIds = historyQueryMapper.selectLockedPredictionIds(
                new StatisticsQueryCriteriaDto(startDate, endDate, leagueId, modelVersion)
        );
        return historyRecordAssembler.assemble(predictionIds);
    }

    private StatisticsWindowVo window(LocalDate startDate, LocalDate endDate, List<HistoryListItemVo> records) {
        return new StatisticsWindowVo(startDate, endDate, statisticsCalculator.calculate(records));
    }

    private List<LeagueStatisticsVo> byLeague(List<HistoryListItemVo> records) {
        Map<LeagueKey, List<HistoryListItemVo>> grouped = records.stream().collect(Collectors.groupingBy(
                record -> new LeagueKey(record.match().leagueId(), record.match().leagueName())
        ));
        List<LeagueStatisticsVo> result = new ArrayList<>(grouped.size());
        grouped.forEach((key, values) -> result.add(new LeagueStatisticsVo(
                key.leagueId(),
                key.leagueName(),
                statisticsCalculator.calculate(values)
        )));
        result.sort(Comparator
                .comparing((LeagueStatisticsVo item) -> item.leagueName(), Comparator.nullsLast(String::compareTo))
                .thenComparing(item -> item.leagueId(), Comparator.nullsLast(Long::compareTo)));
        return List.copyOf(result);
    }

    private List<ModelVersionStatisticsVo> byModelVersion(List<HistoryListItemVo> records) {
        Map<String, List<HistoryListItemVo>> grouped = records.stream().collect(Collectors.groupingBy(
                HistoryListItemVo::modelVersion
        ));
        List<ModelVersionStatisticsVo> result = new ArrayList<>(grouped.size());
        grouped.forEach((modelVersion, values) -> result.add(new ModelVersionStatisticsVo(
                modelVersion,
                statisticsCalculator.calculate(values)
        )));
        result.sort(Comparator.comparing(ModelVersionStatisticsVo::modelVersion));
        return List.copyOf(result);
    }

    private record LeagueKey(Long leagueId, String leagueName) {
    }
}
