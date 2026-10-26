package com.jingcaicompass.backtest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jingcaicompass.backtest.entity.BacktestCaseEntity;
import com.jingcaicompass.backtest.enums.BacktestResultStatusEnum;
import com.jingcaicompass.match.entity.MatchResultFact;
import com.jingcaicompass.match.enums.MatchResultFactStatusEnum;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import com.jingcaicompass.match.mapper.MatchResultFactMapper;
import com.jingcaicompass.prediction.enums.AsianHandicapPickEnum;
import com.jingcaicompass.prediction.enums.TotalGoalsPickEnum;
import com.jingcaicompass.settlement.enums.SettlementStatusEnum;
import com.jingcaicompass.settlement.service.AsianHandicapSettlementCalculator;
import com.jingcaicompass.settlement.service.AsianTotalGoalsSettlementCalculator;
import com.jingcaicompass.settlement.service.MarketSettlementCalculatorRouter;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 结算复用生产计算的验证。
 *
 * <p>重点不是「算得对不对」（那是生产计算器的测试职责），而是三件容易错的事：
 * 价格取哪一侧、无方向时是否留空、赛果未到时是否保持 PENDING。
 * 这三项任一错位都不会抛异常，只会让 ROI 系统性偏移。
 */
class BacktestSettlementServiceTest {

    private MatchResultFactMapper factMapper;

    private BacktestSettlementService service;

    @BeforeEach
    void setUp() {
        factMapper = mock(MatchResultFactMapper.class);
        service = new BacktestSettlementService(
                factMapper,
                new MarketSettlementCalculatorRouter(List.of(
                        new AsianHandicapSettlementCalculator(),
                        new AsianTotalGoalsSettlementCalculator()
                ))
        );
    }

    @Test
    void settlesHandicapHitAndUsesSelectedSidePrice() {
        when(factMapper.selectCurrentByMatchId(11L)).thenReturn(finalFact(2, 0));

        BacktestSettlementService.Settlement home = service.settle(
                caseEntity(AsianHandicapPickEnum.HOME_COVER, null));
        assertThat(home.resultStatus()).isEqualTo(BacktestResultStatusEnum.FINAL);
        assertThat(home.handicapStatus()).isEqualTo(SettlementStatusEnum.HIT);
        // 主让 -0.5、2:0 → 主队赢盘；收益 = 主队水位 1.9000 − 1。
        assertThat(home.handicapProfit()).isEqualByComparingTo("0.9000");
        assertThat(home.homeScore()).isEqualTo(2);
        assertThat(home.awayScore()).isZero();

        BacktestSettlementService.Settlement away = service.settle(
                caseEntity(AsianHandicapPickEnum.AWAY_COVER, null));
        assertThat(away.handicapStatus()).isEqualTo(SettlementStatusEnum.MISS);
        assertThat(away.handicapProfit()).isEqualByComparingTo("-1.0000");
    }

    @Test
    void usesAwayPriceWhenPickingAwayEvenIfItWins() {
        when(factMapper.selectCurrentByMatchId(11L)).thenReturn(finalFact(0, 1));

        BacktestSettlementService.Settlement away = service.settle(
                caseEntity(AsianHandicapPickEnum.AWAY_COVER, null));

        assertThat(away.handicapStatus()).isEqualTo(SettlementStatusEnum.HIT);
        // 客队水位 2.0000 → +1.0000。若误用主队 1.9000 会得到 +0.9000，偏差看起来像模型变差。
        assertThat(away.handicapProfit()).isEqualByComparingTo("1.0000");
    }

    @Test
    void settlesQuarterLineHalfWin() {
        when(factMapper.selectCurrentByMatchId(11L)).thenReturn(finalFact(1, 1));

        // 让球 -0.25、1:1 → 主队方向半输（拆成 0 与 -0.5 两个半盘）。
        BacktestCaseEntity entity = caseEntity(AsianHandicapPickEnum.HOME_COVER, null);
        entity.setFrozenHandicapLine(new BigDecimal("-0.25"));

        BacktestSettlementService.Settlement settlement = service.settle(entity);

        assertThat(settlement.handicapStatus()).isEqualTo(SettlementStatusEnum.HALF_LOSS);
        assertThat(settlement.handicapProfit()).isEqualByComparingTo("-0.5000");
    }

    @Test
    void settlesTotalsOnFrozenLine() {
        when(factMapper.selectCurrentByMatchId(11L)).thenReturn(finalFact(2, 1));

        BacktestSettlementService.Settlement over = service.settle(
                caseEntity(null, TotalGoalsPickEnum.OVER));
        // 2.75 是四分盘：总进球 3 拆成「大 2.5 赢」+「大 3.0 走盘」→ 半赢，
        // 收益 = (1.8800 − 1) / 2。**不是** HIT——这里最容易想当然。
        assertThat(over.totalsStatus()).isEqualTo(SettlementStatusEnum.HALF_WIN);
        assertThat(over.totalsProfit()).isEqualByComparingTo("0.4400");
        assertThat(over.handicapStatus()).isNull();
        assertThat(over.handicapProfit()).isNull();

        BacktestSettlementService.Settlement under = service.settle(
                caseEntity(null, TotalGoalsPickEnum.UNDER));
        assertThat(under.totalsStatus()).isEqualTo(SettlementStatusEnum.HALF_LOSS);
        assertThat(under.totalsProfit()).isEqualByComparingTo("-0.5000");
    }

    @Test
    void keepsBothMarketsNullWhenNoDirection() {
        when(factMapper.selectCurrentByMatchId(11L)).thenReturn(finalFact(2, 1));

        BacktestSettlementService.Settlement settlement = service.settle(caseEntity(null, null));

        // 没方向是「不参与」，不是「结算为 VOID」：写 VOID 会进 settled_count 的分母。
        assertThat(settlement.resultStatus()).isEqualTo(BacktestResultStatusEnum.FINAL);
        assertThat(settlement.handicapStatus()).isNull();
        assertThat(settlement.totalsStatus()).isNull();
        assertThat(settlement.handicapProfit()).isNull();
        assertThat(settlement.totalsProfit()).isNull();
    }

    @Test
    void staysPendingWhenResultMissing() {
        when(factMapper.selectCurrentByMatchId(11L)).thenReturn(null);

        BacktestSettlementService.Settlement settlement = service.settle(
                caseEntity(AsianHandicapPickEnum.HOME_COVER, null));

        assertThat(settlement.settled()).isFalse();
        assertThat(settlement.resultStatus()).isEqualTo(BacktestResultStatusEnum.PENDING);
        // PENDING 行必须六列全空：ck_backtest_cases_result 不允许半写。
        assertThat(settlement.handicapStatus()).isNull();
        assertThat(settlement.totalsStatus()).isNull();
        assertThat(settlement.settledAt()).isNull();
    }

    @Test
    void marksBothMarketsVoidWhenFactVoided() {
        MatchResultFact fact = new MatchResultFact();
        fact.setMatchId(11L);
        fact.setFactStatus(MatchResultFactStatusEnum.VOID);
        when(factMapper.selectCurrentByMatchId(11L)).thenReturn(fact);

        BacktestSettlementService.Settlement settlement = service.settle(
                caseEntity(AsianHandicapPickEnum.HOME_COVER, null));

        assertThat(settlement.resultStatus()).isEqualTo(BacktestResultStatusEnum.VOID);
        assertThat(settlement.handicapStatus()).isEqualTo(SettlementStatusEnum.VOID);
        assertThat(settlement.totalsStatus()).isEqualTo(SettlementStatusEnum.VOID);
        assertThat(settlement.handicapProfit()).isEqualByComparingTo("0");
        assertThat(settlement.settledAt()).isNotNull();
    }

    private static MatchResultFact finalFact(int home, int away) {
        MatchResultFact fact = new MatchResultFact();
        fact.setMatchId(11L);
        fact.setFactStatus(MatchResultFactStatusEnum.FINAL);
        fact.setMatchStatus(MatchStatusEnum.FINISHED);
        fact.setHomeScore(home);
        fact.setAwayScore(away);
        return fact;
    }

    private static BacktestCaseEntity caseEntity(AsianHandicapPickEnum handicapPick, TotalGoalsPickEnum totalsPick) {
        BacktestCaseEntity entity = new BacktestCaseEntity();
        entity.setId(1L);
        entity.setMatchId(11L);
        entity.setHandicapPick(handicapPick);
        entity.setTotalsPick(totalsPick);
        entity.setFrozenHandicapLine(new BigDecimal("-0.50"));
        entity.setFrozenHomeOdds(new BigDecimal("1.9000"));
        entity.setFrozenAwayOdds(new BigDecimal("2.0000"));
        entity.setFrozenTotalLine(new BigDecimal("2.75"));
        entity.setFrozenOverOdds(new BigDecimal("1.8800"));
        entity.setFrozenUnderOdds(new BigDecimal("1.9200"));
        return entity;
    }
}
