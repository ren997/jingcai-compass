package com.jingcaicompass.odds.service;

import com.jingcaicompass.odds.client.AsianOddsAggregationProperties;
import com.jingcaicompass.odds.entity.AsianOddsSnapshot;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 从真实快照行里挑出「冻结行」：预测与结算都只认这一行的盘口线与水位。
 *
 * <p>存在这层选择的根因是：{@code predictions.asian_odds_snapshot_id} 是指向真实快照行的外键，
 * 结算只从该行读线与价。共识主盘是跨庄算出来的数，必须落到「确实报出该线的某家庄」上，
 * 否则决策线与计账线会分裂，ROI/CLV 无法复核。</p>
 *
 * <p>因此计账价格永远是真实可下注报价，共识公平价只用于打分与 edge 计算。</p>
 */
public class AsianFreezeSnapshotSelector {

    private static final BigDecimal FIFTY_PERCENT = new BigDecimal("0.5");

    private final Set<String> whitelist;

    public AsianFreezeSnapshotSelector(AsianOddsAggregationProperties properties) {
        this.whitelist = (properties == null
                ? AsianOddsAggregationProperties.defaults()
                : properties).normalizedWhitelist();
    }

    /**
     * 选出冻结行。
     *
     * @param handicapRows     同场完整交叉积行（每家 × 让球线 × 大小球线，各取最新）。
     *                         <b>必须是完整交叉积</b>，不能传「每家每档让球线只留一行」的聚合取数结果，
     *                         否则大小球维度已被压成单档，对齐主盘的排序键失效
     * @param handicapConsensus 让球市场共识
     * @param totalsConsensus  大小球市场共识，可为空
     * @return 落在共识线上、让球与大小球四个水位齐全的那一行
     */
    public Optional<AsianOddsSnapshot> select(
            List<AsianOddsSnapshot> handicapRows,
            AsianMarketConsensusAggregator.LineConsensus handicapConsensus,
            Optional<AsianMarketConsensusAggregator.LineConsensus> totalsConsensus
    ) {
        if (handicapRows == null || handicapRows.isEmpty() || handicapConsensus == null) {
            return Optional.empty();
        }
        return handicapRows.stream()
                .filter(row -> freezable(row, handicapConsensus.line()))
                .min(ranking(handicapConsensus, totalsConsensus));
    }

    /**
     * 排序键依次是：对齐大小球主盘 → 白名单 → 与共识同向 → 抽水低 → 最新。
     *
     * <p><b>「对齐大小球主盘」必须排第一，不能排在白名单后面。</b>它不是「更好」而是「正确」：
     * 大小球方向由 λ 投影回判定线得出，而 λ 正是由主盘去水概率反解出来的。判定线与主盘不是
     * 同一条线时，投影出的模型概率不再复现市场，{@code edge_over} 会凭空出现——那不是 alpha，
     * 是「投影线 ≠ 反解线」的形状偏差。排在白名单之后意味着只要白名单庄没报主盘那一档，
     * 就会静默退回到错误线上，而这正是 2026-10-22 那批 13/13 全小球的成因。</p>
     *
     * <p>代价要认：为了选到正确的线，可能落到一个非白名单庄上，冻结行的报价抽水更高、
     * 置信度因此更粗。这是有意的取舍——线错会污染方向与结算，抽水高只损失一点分辨率。</p>
     */
    private Comparator<AsianOddsSnapshot> ranking(
            AsianMarketConsensusAggregator.LineConsensus handicapConsensus,
            Optional<AsianMarketConsensusAggregator.LineConsensus> totalsConsensus
    ) {
        return Comparator
                .comparing((AsianOddsSnapshot row) -> alignedWithTotalsLine(row, totalsConsensus),
                        Comparator.reverseOrder())
                .thenComparing(row -> isWhitelisted(row.getBookmakerCode()), Comparator.reverseOrder())
                .thenComparing(row -> agreesOnSide(row, handicapConsensus), Comparator.reverseOrder())
                .thenComparing(row -> priceGap(row, handicapConsensus))
                .thenComparing(AsianOddsSnapshot::getCapturedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(AsianOddsSnapshot::getId,
                        Comparator.nullsLast(Comparator.reverseOrder()));
    }

    /**
     * 硬过滤：必须落在共识线上，且让球与大小球四个水位齐全可用。
     *
     * <p>大小球三列在 V3 中允许为空，但预测导入校验要求被引用的快照四个水位全部非空，
     * 因此这里直接排除残缺行，让调用方转成明确的跳过原因。</p>
     */
    private boolean freezable(AsianOddsSnapshot row, BigDecimal consensusLine) {
        return row != null
                && row.getHandicapLine() != null
                && row.getHandicapLine().compareTo(consensusLine) == 0
                && AsianMarketMath.isUsablePrice(row.getHomeOdds())
                && AsianMarketMath.isUsablePrice(row.getAwayOdds())
                && row.getTotalLine() != null
                && AsianMarketMath.isUsablePrice(row.getOverOdds())
                && AsianMarketMath.isUsablePrice(row.getUnderOdds());
    }

    private boolean isWhitelisted(String bookmakerCode) {
        return bookmakerCode != null && whitelist.contains(bookmakerCode.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * 该行自身更看好的一侧是否与共识同向。
     *
     * <p>方向来自共识、置信度来自冻结行报价，两者输入不同源；优先同向行可以避免出现
     * 「共识选主队、计账行却更看好客队」的自相矛盾组合。</p>
     */
    private boolean agreesOnSide(
            AsianOddsSnapshot row,
            AsianMarketConsensusAggregator.LineConsensus consensus
    ) {
        BigDecimal rowProbability = AsianMarketMath.devigFirst(row.getHomeOdds(), row.getAwayOdds());
        if (rowProbability.compareTo(FIFTY_PERCENT) == 0
                || consensus.firstProbability().compareTo(FIFTY_PERCENT) == 0) {
            return true;
        }
        return rowProbability.compareTo(FIFTY_PERCENT) > 0
                == consensus.firstProbability().compareTo(FIFTY_PERCENT) > 0;
    }

    /** 该行的大小球线是否就是大小球主盘线；不是也能用，但排在后面并在摘要中标注。 */
    private boolean alignedWithTotalsLine(
            AsianOddsSnapshot row,
            Optional<AsianMarketConsensusAggregator.LineConsensus> totalsConsensus
    ) {
        return totalsConsensus != null
                && totalsConsensus.isPresent()
                && row.getTotalLine().compareTo(totalsConsensus.get().line()) == 0;
    }

    /**
     * 报价相对共识公平价的 L1 距离。
     *
     * <p>该距离等于这家庄双边抽水的大小，因此这一键等价于「选抽水最低的报价」，
     * 与「抽水与报价家数反相关」的实测结论一致。</p>
     */
    private BigDecimal priceGap(
            AsianOddsSnapshot row,
            AsianMarketConsensusAggregator.LineConsensus consensus
    ) {
        return row.getHomeOdds().subtract(consensus.fairFirstPrice()).abs()
                .add(row.getAwayOdds().subtract(consensus.fairSecondPrice()).abs());
    }
}
