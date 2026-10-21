package com.jingcaicompass.admin.service;

import com.jingcaicompass.admin.vo.AdminOddsLadderVo;
import com.jingcaicompass.odds.client.AsianOddsAggregationProperties;
import com.jingcaicompass.odds.entity.AsianOddsSnapshot;
import com.jingcaicompass.odds.mapper.AsianOddsSnapshotMapper;
import com.jingcaicompass.odds.service.AsianMarketConsensusAggregator;
import com.jingcaicompass.prediction.entity.Prediction;
import com.jingcaicompass.prediction.enums.PredictionTypeEnum;
import com.jingcaicompass.prediction.mapper.PredictionMapper;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 为后台展示一场比赛的亚盘盘口阶梯。
 *
 * <p>取数与聚合完全复用生成侧的路径：同两条批量查询、同一个 {@link AsianMarketConsensusAggregator}。
 * 后台若是自己取「最新一条快照」再自己算，看到的就会与模型当时用的不是同一份数据，
 * 而这个页面的唯一用途恰恰是解释模型为什么选了这个方向、冻结在这一行。
 */
public class AdminOddsLadderQueryService {

    private final PredictionMapper predictionMapper;
    private final AsianOddsSnapshotMapper snapshotMapper;
    private final AsianMarketConsensusAggregator consensusAggregator;

    public AdminOddsLadderQueryService(
            PredictionMapper predictionMapper,
            AsianOddsSnapshotMapper snapshotMapper,
            AsianOddsAggregationProperties aggregationProperties
    ) {
        this.predictionMapper = predictionMapper;
        this.snapshotMapper = snapshotMapper;
        this.consensusAggregator = new AsianMarketConsensusAggregator(
                aggregationProperties == null
                        ? AsianOddsAggregationProperties.defaults()
                        : aggregationProperties
        );
    }

    /**
     * 读取指定预测的盘口阶梯。
     *
     * @param predictionId 亚盘预测 ID
     * @return 两个市场的共识阶梯与逐家庄原始报价
     */
    public AdminOddsLadderVo query(Long predictionId) {
        // 1) 定位预测：决定比赛、产品线与冻结行
        Prediction prediction = predictionMapper.selectById(predictionId);
        if (prediction == null) {
            throw new BusinessException(
                    ErrorCode.PREDICTION_NOT_FOUND,
                    "prediction not found: " + predictionId
            );
        }
        if (prediction.getPredictionType() != PredictionTypeEnum.ASIAN) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_ERROR,
                    "odds ladder is only available for ASIAN predictions"
            );
        }
        Long matchId = prediction.getMatchId();

        // 2) 按市场取「每家每档线」的最新报价，与生成侧同一对查询
        List<AsianOddsSnapshot> handicapRows = snapshotMapper
                .selectLatestConfirmedHandicapLinesByMatchIds(List.of(matchId));
        List<AsianOddsSnapshot> totalsRows = snapshotMapper
                .selectLatestConfirmedTotalsLinesByMatchIds(List.of(matchId));

        // 3) 聚合：逐家去水 → 中位 → 归一，主盘取偏离 50/50 最小的线
        AsianMarketConsensusAggregator.MarketAggregate handicap = consensusAggregator.aggregate(
                handicapRows.stream().map(this::handicapQuote).toList()
        );
        AsianMarketConsensusAggregator.MarketAggregate totals = consensusAggregator.aggregate(
                totalsRows.stream().map(this::totalsQuote).toList()
        );

        Long frozenSnapshotId = prediction.getAsianOddsSnapshotId();
        return new AdminOddsLadderVo(
                prediction.getId(),
                matchId,
                frozenSnapshotId,
                toLadder(handicap),
                toLadder(totals),
                toRows(handicapRows, totalsRows, frozenSnapshotId)
        );
    }

    private AdminOddsLadderVo.MarketLadderVo toLadder(
            AsianMarketConsensusAggregator.MarketAggregate aggregate
    ) {
        // ranked 已按「最平衡优先」排序，第 0 条即主盘；不用对象相等判断，避免依赖 record 的引用
        List<AdminOddsLadderVo.LineVo> lines = new ArrayList<>(aggregate.ranked().size());
        for (int index = 0; index < aggregate.ranked().size(); index++) {
            AsianMarketConsensusAggregator.LineConsensus consensus = aggregate.ranked().get(index);
            lines.add(new AdminOddsLadderVo.LineVo(
                    consensus.line(),
                    consensus.sampleCount(),
                    consensus.whitelistCount(),
                    consensus.whitelistApplied(),
                    consensus.firstProbability(),
                    consensus.secondProbability(),
                    consensus.dispersion(),
                    consensus.fairFirstPrice(),
                    consensus.fairSecondPrice(),
                    index == 0
            ));
        }
        return aggregate.best()
                .map(best -> new AdminOddsLadderVo.MarketLadderVo(
                        best.line(),
                        best.firstProbability(),
                        best.secondProbability(),
                        best.dispersion(),
                        best.sampleCount(),
                        best.sampleSource(),
                        lines
                ))
                .orElseGet(() -> new AdminOddsLadderVo.MarketLadderVo(
                        null, null, null, null, null, null, lines
                ));
    }

    /**
     * 逐家庄报价按快照 ID 去重。
     *
     * <p>一条快照同时带让球与大小球字段，两条查询会返回同一行。不去重会让表格里每条报价
     * 出现两次，家数看起来翻倍。
     */
    private List<AdminOddsLadderVo.RowVo> toRows(
            List<AsianOddsSnapshot> handicapRows,
            List<AsianOddsSnapshot> totalsRows,
            Long frozenSnapshotId
    ) {
        Map<Long, AsianOddsSnapshot> byId = new LinkedHashMap<>();
        handicapRows.forEach(row -> byId.putIfAbsent(row.getId(), row));
        totalsRows.forEach(row -> byId.putIfAbsent(row.getId(), row));
        List<AdminOddsLadderVo.RowVo> rows = new ArrayList<>(byId.values().stream()
                .filter(Objects::nonNull)
                .map(row -> new AdminOddsLadderVo.RowVo(
                        row.getId(),
                        row.getProviderCode(),
                        row.getBookmakerCode(),
                        row.getHandicapLine(),
                        row.getHomeOdds(),
                        row.getAwayOdds(),
                        row.getTotalLine(),
                        row.getOverOdds(),
                        row.getUnderOdds(),
                        row.getCapturedAt(),
                        row.getId().equals(frozenSnapshotId)
                ))
                .toList());
        // 冻结行置顶：运营打开这个页面第一件事是确认结算按哪一行计账
        rows.sort(Comparator
                .comparing(AdminOddsLadderVo.RowVo::frozen).reversed()
                .thenComparing(AdminOddsLadderVo.RowVo::handicapLine, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(AdminOddsLadderVo.RowVo::bookmakerCode, Comparator.nullsLast(Comparator.naturalOrder())));
        return rows;
    }

    private AsianMarketConsensusAggregator.Quote handicapQuote(AsianOddsSnapshot row) {
        return new AsianMarketConsensusAggregator.Quote(
                row.getProviderCode(),
                row.getBookmakerCode(),
                row.getHandicapLine(),
                row.getHomeOdds(),
                row.getAwayOdds(),
                row.getCapturedAt(),
                row.getId()
        );
    }

    private AsianMarketConsensusAggregator.Quote totalsQuote(AsianOddsSnapshot row) {
        return new AsianMarketConsensusAggregator.Quote(
                row.getProviderCode(),
                row.getBookmakerCode(),
                row.getTotalLine(),
                row.getOverOdds(),
                row.getUnderOdds(),
                row.getCapturedAt(),
                row.getId()
        );
    }
}
