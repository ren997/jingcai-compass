package com.jingcaicompass.admin.vo;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 一次查询的盘口阶梯：跨庄共识 + 逐家庄原始报价。
 *
 * <p>概率由服务端用同一套 {@code AsianMarketMath.devigFirst} 算出，前端不再重算——
 * 前端一旦自己实现去水，两处口径会慢慢分叉，运营看到的「共识」就和模型用的不是同一个数。
 *
 * @param predictionId 预测 ID
 * @param matchId 内部比赛 ID
 * @param frozenSnapshotId 冻结行（结算与摘要实际引用的那条报价）；未生成时为空
 * @param handicap 让球阶梯
 * @param totals 大小球阶梯
 * @param rows 逐家庄报价，按冻结行优先、再按盘口线升序
 */
public record AdminOddsLadderVo(
        Long predictionId,
        Long matchId,
        Long frozenSnapshotId,
        MarketLadderVo handicap,
        MarketLadderVo totals,
        List<RowVo> rows
) {

    public AdminOddsLadderVo {
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    /**
     * 单个市场的阶梯。
     *
     * @param bestLine 主盘线；样本不足时为空
     * @param bestFirstProbability 主盘首边（主队赢盘 / 大球）去水概率
     * @param bestSecondProbability 主盘次边去水概率
     * @param bestDispersion 主盘跨庄总体标准差 σ
     * @param bestSampleCount 主盘参与家数
     * @param bestSampleSource 样本来源说明（白名单或回退全量）
     * @param lines 全部合格档位，按主盘优先排序
     */
    public record MarketLadderVo(
            BigDecimal bestLine,
            BigDecimal bestFirstProbability,
            BigDecimal bestSecondProbability,
            BigDecimal bestDispersion,
            Integer bestSampleCount,
            String bestSampleSource,
            List<LineVo> lines
    ) {

        public MarketLadderVo {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /**
     * 某一档盘的共识结果。
     *
     * @param line 盘口线
     * @param sampleCount 参与聚合家数
     * @param whitelistCount 白名单家数
     * @param whitelistApplied 是否按白名单取样（白名单不足时回退全量）
     * @param firstProbability 首边去水概率
     * @param secondProbability 次边去水概率
     * @param dispersion 跨庄总体标准差
     * @param fairFirstPrice 去水后公平赔率（首边），不可成交，仅供对照
     * @param fairSecondPrice 去水后公平赔率（次边）
     * @param best 是否为主盘
     */
    public record LineVo(
            BigDecimal line,
            int sampleCount,
            int whitelistCount,
            boolean whitelistApplied,
            BigDecimal firstProbability,
            BigDecimal secondProbability,
            BigDecimal dispersion,
            BigDecimal fairFirstPrice,
            BigDecimal fairSecondPrice,
            boolean best
    ) {
    }

    /**
     * 一条原始报价。
     *
     * @param snapshotId 快照 ID
     * @param providerCode 数据源编码
     * @param bookmakerCode 庄家编码
     * @param handicapLine 让球线
     * @param homeOdds 主队水位
     * @param awayOdds 客队水位
     * @param totalLine 大小球线
     * @param overOdds 大球水位
     * @param underOdds 小球水位
     * @param capturedAt 抓取时间
     * @param frozen 是否为该预测的冻结行（结算按这一行计账）
     */
    public record RowVo(
            Long snapshotId,
            String providerCode,
            String bookmakerCode,
            BigDecimal handicapLine,
            BigDecimal homeOdds,
            BigDecimal awayOdds,
            BigDecimal totalLine,
            BigDecimal overOdds,
            BigDecimal underOdds,
            Instant capturedAt,
            boolean frozen
    ) {
    }
}
