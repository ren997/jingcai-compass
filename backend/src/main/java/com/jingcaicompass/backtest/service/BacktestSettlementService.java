package com.jingcaicompass.backtest.service;

import com.jingcaicompass.backtest.entity.BacktestCaseEntity;
import com.jingcaicompass.backtest.enums.BacktestResultStatusEnum;
import com.jingcaicompass.match.entity.MatchResultFact;
import com.jingcaicompass.match.enums.MatchResultFactStatusEnum;
import com.jingcaicompass.match.mapper.MatchResultFactMapper;
import com.jingcaicompass.prediction.enums.AsianHandicapPickEnum;
import com.jingcaicompass.prediction.enums.TotalGoalsPickEnum;
import com.jingcaicompass.settlement.dto.MarketSettlementInputDto;
import com.jingcaicompass.settlement.enums.MarketTypeEnum;
import com.jingcaicompass.settlement.enums.SettlementStatusEnum;
import com.jingcaicompass.settlement.service.MarketSettlementCalculatorRouter;
import com.jingcaicompass.settlement.service.SettlementMarginSupport;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

/**
 * 回测样本的结算：复用生产的市场计算器与收益换算，只把「输入从预测换成样本」。
 *
 * <p>这里<b>没有</b>任何公式。{@link MarketSettlementCalculatorRouter} 判定 HIT/MISS/PUSH/
 * HALF_WIN/HALF_LOSS，{@link SettlementMarginSupport#netProfit} 折算收益，两者都是线上结算
 * 正在用的同一份实现。复制一份「看起来一样」的实现在下次改口径时不会一起改，
 * 表现是回测收益与线上长期存在一个说不清来源的偏差——那正是回测最不能有的东西。</p>
 *
 * <p><b>价格取侧</b>：{@code HOME_COVER→frozen_home_odds}、{@code AWAY_COVER→frozen_away_odds}、
 * {@code OVER→frozen_over_odds}、{@code UNDER→frozen_under_odds}。押客队却按主队水位计账，
 * 会让所有客队方向的收益系统性偏高（客队水位通常更高），且这个偏差在「客队方向占比高」的
 * 批次里会伪装成模型变准。</p>
 *
 * <p>走盘（PUSH）收益为 0 且<b>进 Brier 不进命中率分母</b>——这一点由
 * {@code StatisticsCalculator} 保证，这里只如实写状态。</p>
 */
@Service
@ConditionalOnBean(DataSource.class)
public class BacktestSettlementService {

    private final MatchResultFactMapper factMapper;
    private final MarketSettlementCalculatorRouter calculatorRouter;

    public BacktestSettlementService(
            MatchResultFactMapper factMapper,
            MarketSettlementCalculatorRouter calculatorRouter
    ) {
        this.factMapper = factMapper;
        this.calculatorRouter = calculatorRouter;
    }

    /**
     * 结算一条样本。
     *
     * @param entity 已写入的样本（需要 id 与冻结行）
     * @return 结算结论；{@code PENDING} 表示赛果还没到，样本保持原状
     */
    public Settlement settle(BacktestCaseEntity entity) {
        MatchResultFact fact = entity.getMatchId() == null
                ? null
                : factMapper.selectCurrentByMatchId(entity.getMatchId());
        if (fact == null || fact.getFactStatus() == null) {
            return Settlement.pending();
        }
        if (fact.getFactStatus() == MatchResultFactStatusEnum.PENDING) {
            return Settlement.pending();
        }
        if (fact.getFactStatus() == MatchResultFactStatusEnum.VOID) {
            // 作废：两个市场都记 VOID、收益 0。留空会被 ck_backtest_cases_result 拒绝，
            // 而「留空」在语义上也不对——它不是「没结算」，是「结算为不成立」。
            return new Settlement(
                    BacktestResultStatusEnum.VOID,
                    null,
                    null,
                    SettlementStatusEnum.VOID,
                    BigDecimal.ZERO.setScale(4),
                    SettlementStatusEnum.VOID,
                    BigDecimal.ZERO.setScale(4),
                    Instant.now()
            );
        }
        if (fact.getFactStatus() != MatchResultFactStatusEnum.FINAL) {
            throw new IllegalStateException("未知赛果事实状态：" + fact.getFactStatus());
        }

        MarketLeg handicap = settleHandicap(entity, fact);
        MarketLeg totals = settleTotals(entity, fact);
        return new Settlement(
                BacktestResultStatusEnum.FINAL,
                fact.getHomeScore(),
                fact.getAwayScore(),
                handicap.status(),
                handicap.profit(),
                totals.status(),
                totals.profit(),
                Instant.now()
        );
    }

    private MarketLeg settleHandicap(BacktestCaseEntity entity, MatchResultFact fact) {
        AsianHandicapPickEnum pick = entity.getHandicapPick();
        if (pick == null) {
            // 没方向就是「不参与」，两列都留空——不要写 VOID，VOID 会被计入 settled_count 的分母。
            return MarketLeg.none();
        }
        SettlementStatusEnum status = calculatorRouter.calculate(new MarketSettlementInputDto(
                MarketTypeEnum.ASIAN_HANDICAP,
                null,
                fact,
                null,
                pick,
                null,
                Objects.requireNonNull(entity.getFrozenHandicapLine(), "让球方向必须带冻结让球线")
        ));
        BigDecimal price = pick == AsianHandicapPickEnum.HOME_COVER
                ? entity.getFrozenHomeOdds()
                : entity.getFrozenAwayOdds();
        return new MarketLeg(status, SettlementMarginSupport.netProfit(status, price));
    }

    private MarketLeg settleTotals(BacktestCaseEntity entity, MatchResultFact fact) {
        TotalGoalsPickEnum pick = entity.getTotalsPick();
        if (pick == null) {
            return MarketLeg.none();
        }
        SettlementStatusEnum status = calculatorRouter.calculate(new MarketSettlementInputDto(
                MarketTypeEnum.ASIAN_TOTAL_GOALS,
                null,
                fact,
                null,
                null,
                pick,
                Objects.requireNonNull(entity.getFrozenTotalLine(), "大小球方向必须带冻结大小球线")
        ));
        BigDecimal price = pick == TotalGoalsPickEnum.OVER
                ? entity.getFrozenOverOdds()
                : entity.getFrozenUnderOdds();
        return new MarketLeg(status, SettlementMarginSupport.netProfit(status, price));
    }

    private record MarketLeg(SettlementStatusEnum status, BigDecimal profit) {

        static MarketLeg none() {
            return new MarketLeg(null, null);
        }
    }

    /**
     * 一条样本的结算结论。
     *
     * <p>{@code PENDING} 时全字段为空：表上 {@code ck_backtest_cases_result} 要求
     * PENDING 行六列皆空且 {@code settled_at} 为空，写任何一个都会整批失败。</p>
     */
    public record Settlement(
            BacktestResultStatusEnum resultStatus,
            Integer homeScore,
            Integer awayScore,
            SettlementStatusEnum handicapStatus,
            BigDecimal handicapProfit,
            SettlementStatusEnum totalsStatus,
            BigDecimal totalsProfit,
            Instant settledAt
    ) {

        public static Settlement pending() {
            return new Settlement(BacktestResultStatusEnum.PENDING, null, null, null, null, null, null, null);
        }

        public boolean settled() {
            return resultStatus != BacktestResultStatusEnum.PENDING;
        }
    }
}
