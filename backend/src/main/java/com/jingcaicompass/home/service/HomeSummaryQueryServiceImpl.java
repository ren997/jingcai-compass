package com.jingcaicompass.home.service;

import com.jingcaicompass.home.mapper.HomeSummaryMapper;
import com.jingcaicompass.home.vo.HomeDataFreshnessVo;
import com.jingcaicompass.home.vo.HomeSummaryVo;
import com.jingcaicompass.home.vo.HomeTodayOverviewVo;
import com.jingcaicompass.statistics.service.StatisticsQueryService;
import com.jingcaicompass.statistics.vo.StatisticsSummaryVo;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

/** 使用数据库事实和 T507 统一统计口径生成公开首页摘要。 */
@Service
@ConditionalOnBean(DataSource.class)
public class HomeSummaryQueryServiceImpl implements HomeSummaryQueryService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final HomeSummaryMapper homeSummaryMapper;
    private final StatisticsQueryService statisticsQueryService;
    private final Clock clock;

    public HomeSummaryQueryServiceImpl(
            HomeSummaryMapper homeSummaryMapper,
            StatisticsQueryService statisticsQueryService,
            Clock clock
    ) {
        this.homeSummaryMapper = homeSummaryMapper;
        this.statisticsQueryService = statisticsQueryService;
        this.clock = clock;
    }

    @Override
    public HomeSummaryVo summary() {
        // 1) 固定上海业务日和同一汇总时刻，避免日期与数据年龄跨日不一致。
        Instant generatedAt = Instant.now(clock);
        LocalDate asOfDate = LocalDate.now(clock.withZone(SHANGHAI));

        // 2) 读取去重比赛、待结算、当天体彩采集和已发布快照事实。
        Instant latestCapturedAt = homeSummaryMapper.selectLatestSportteryCapturedAtByLotteryDate(asOfDate);
        HomeTodayOverviewVo today = new HomeTodayOverviewVo(
                homeSummaryMapper.countMatchesByLotteryDate(asOfDate),
                homeSummaryMapper.countPublishedPredictionMatchesByLotteryDate(asOfDate)
        );

        // 3) 复用 T507 当前事实与当前结算口径，避免首页另行计算表现指标。
        StatisticsSummaryVo statistics = statisticsQueryService.summary(null);
        return new HomeSummaryVo(
                asOfDate,
                today,
                homeSummaryMapper.countPendingSettlementMatches(),
                homeSummaryMapper.countHistoricalPublishedPredictionMatches(),
                statistics.trailingSevenDays(),
                statistics.trailingThirtyDays(),
                new HomeDataFreshnessVo(latestCapturedAt, ageSeconds(latestCapturedAt, generatedAt)),
                homeSummaryMapper.selectLatestPublishedSnapshotAt(),
                generatedAt
        );
    }

    private Long ageSeconds(Instant capturedAt, Instant generatedAt) {
        if (capturedAt == null) {
            return null;
        }
        return Math.max(0, Duration.between(capturedAt, generatedAt).getSeconds());
    }
}
