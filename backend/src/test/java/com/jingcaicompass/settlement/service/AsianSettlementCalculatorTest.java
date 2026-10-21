package com.jingcaicompass.settlement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.jingcaicompass.match.entity.MatchResultFact;
import com.jingcaicompass.match.enums.MatchResultFactStatusEnum;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import com.jingcaicompass.prediction.enums.AsianHandicapPickEnum;
import com.jingcaicompass.prediction.enums.TotalGoalsPickEnum;
import com.jingcaicompass.settlement.dto.MarketSettlementInputDto;
import com.jingcaicompass.settlement.enums.MarketTypeEnum;
import com.jingcaicompass.settlement.enums.SettlementStatusEnum;
import java.math.BigDecimal;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** 亚盘让球与大小球的 margin 判定、四分之一盘拆盘与净收益换算。 */
class AsianSettlementCalculatorTest {

    private final AsianHandicapSettlementCalculator handicapCalculator = new AsianHandicapSettlementCalculator();
    private final AsianTotalGoalsSettlementCalculator totalGoalsCalculator = new AsianTotalGoalsSettlementCalculator();

    @ParameterizedTest(name = "{0}")
    @MethodSource("handicapCases")
    void settlesAsianHandicapByMargin(
            String description,
            int homeScore,
            int awayScore,
            String line,
            AsianHandicapPickEnum pick,
            SettlementStatusEnum expected
    ) {
        SettlementStatusEnum actual = handicapCalculator.calculate(input(
                MarketTypeEnum.ASIAN_HANDICAP,
                finalFact(homeScore, awayScore),
                line,
                pick,
                null
        ));

        assertThat(actual).as(description).isEqualTo(expected);
    }

    private static Stream<Arguments> handicapCases() {
        return Stream.of(
                // 半球盘：没有走盘，非赢即输。
                Arguments.of("半球盘让球，赢一球命中", 1, 0, "-0.5", AsianHandicapPickEnum.HOME_COVER, SettlementStatusEnum.HIT),
                Arguments.of("半球盘让球，平局未中", 0, 0, "-0.5", AsianHandicapPickEnum.HOME_COVER, SettlementStatusEnum.MISS),
                Arguments.of("半球盘受让，平局命中", 0, 0, "0.5", AsianHandicapPickEnum.HOME_COVER, SettlementStatusEnum.HIT),
                Arguments.of("半球盘受让，输一球未中", 0, 1, "0.5", AsianHandicapPickEnum.HOME_COVER, SettlementStatusEnum.MISS),
                Arguments.of("半球盘客队受让，输一球命中", 0, 1, "0.5", AsianHandicapPickEnum.AWAY_COVER, SettlementStatusEnum.HIT),

                // 整数盘：盘口打平即走盘。
                Arguments.of("整数盘让一球，赢两球命中", 2, 0, "-1", AsianHandicapPickEnum.HOME_COVER, SettlementStatusEnum.HIT),
                Arguments.of("整数盘让一球，刚好赢一球走盘", 1, 0, "-1", AsianHandicapPickEnum.HOME_COVER, SettlementStatusEnum.PUSH),
                Arguments.of("整数盘让一球，平局未中", 0, 0, "-1", AsianHandicapPickEnum.HOME_COVER, SettlementStatusEnum.MISS),
                Arguments.of("平手盘，平局走盘", 1, 1, "0", AsianHandicapPickEnum.HOME_COVER, SettlementStatusEnum.PUSH),
                Arguments.of("平手盘客队方向，平局走盘", 1, 1, "0", AsianHandicapPickEnum.AWAY_COVER, SettlementStatusEnum.PUSH),

                // 四分之一盘：拆上下两个半盘后合并。
                Arguments.of("让一球/球半，赢一球半输", 1, 0, "-1.25", AsianHandicapPickEnum.HOME_COVER, SettlementStatusEnum.HALF_LOSS),
                Arguments.of("让一球/球半，赢两球命中", 2, 0, "-1.25", AsianHandicapPickEnum.HOME_COVER, SettlementStatusEnum.HIT),
                Arguments.of("让一球/球半，平局未中", 0, 0, "-1.25", AsianHandicapPickEnum.HOME_COVER, SettlementStatusEnum.MISS),
                Arguments.of("让半球/一球，赢一球半赢", 1, 0, "-0.75", AsianHandicapPickEnum.HOME_COVER, SettlementStatusEnum.HALF_WIN),
                Arguments.of("让半球/一球，平局未中", 0, 0, "-0.75", AsianHandicapPickEnum.HOME_COVER, SettlementStatusEnum.MISS),
                Arguments.of("受让平半，平局半赢", 0, 0, "0.25", AsianHandicapPickEnum.HOME_COVER, SettlementStatusEnum.HALF_WIN),
                Arguments.of("让平半，平局半输", 0, 0, "-0.25", AsianHandicapPickEnum.HOME_COVER, SettlementStatusEnum.HALF_LOSS),
                Arguments.of("受让平半，赢一球命中", 1, 0, "0.25", AsianHandicapPickEnum.HOME_COVER, SettlementStatusEnum.HIT),
                Arguments.of("让一球/球半，客队受让赢一球半赢", 1, 0, "-1.25", AsianHandicapPickEnum.AWAY_COVER, SettlementStatusEnum.HALF_WIN),
                Arguments.of("让一球/球半，客队受让输两球未中", 2, 0, "-1.25", AsianHandicapPickEnum.AWAY_COVER, SettlementStatusEnum.MISS)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("totalGoalsCases")
    void settlesAsianTotalGoalsByMargin(
            String description,
            int homeScore,
            int awayScore,
            String line,
            TotalGoalsPickEnum pick,
            SettlementStatusEnum expected
    ) {
        SettlementStatusEnum actual = totalGoalsCalculator.calculate(input(
                MarketTypeEnum.ASIAN_TOTAL_GOALS,
                finalFact(homeScore, awayScore),
                line,
                null,
                pick
        ));

        assertThat(actual).as(description).isEqualTo(expected);
    }

    private static Stream<Arguments> totalGoalsCases() {
        return Stream.of(
                Arguments.of("两球半大球，进两球未中", 1, 1, "2.5", TotalGoalsPickEnum.OVER, SettlementStatusEnum.MISS),
                Arguments.of("两球半小球，进两球命中", 1, 1, "2.5", TotalGoalsPickEnum.UNDER, SettlementStatusEnum.HIT),
                Arguments.of("三球整数盘，刚好三球走盘", 2, 1, "3", TotalGoalsPickEnum.OVER, SettlementStatusEnum.PUSH),
                Arguments.of("三球整数盘小球，刚好三球走盘", 2, 1, "3", TotalGoalsPickEnum.UNDER, SettlementStatusEnum.PUSH),
                Arguments.of("两球/两球半大球，进两球半输", 1, 1, "2.25", TotalGoalsPickEnum.OVER, SettlementStatusEnum.HALF_LOSS),
                Arguments.of("两球/两球半小球，进两球半赢", 1, 1, "2.25", TotalGoalsPickEnum.UNDER, SettlementStatusEnum.HALF_WIN),
                Arguments.of("两球半/三球大球，进三球半赢", 2, 1, "2.75", TotalGoalsPickEnum.OVER, SettlementStatusEnum.HALF_WIN),
                Arguments.of("两球半/三球小球，进三球半输", 2, 1, "2.75", TotalGoalsPickEnum.UNDER, SettlementStatusEnum.HALF_LOSS),
                Arguments.of("两球半/三球大球，进两球未中", 1, 1, "2.75", TotalGoalsPickEnum.OVER, SettlementStatusEnum.MISS)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("voidAndPendingFacts")
    void keepsUnconfirmedOrVoidFactsWithoutReadingThePick(
            MarketTypeEnum marketType,
            MatchResultFactStatusEnum factStatus,
            MatchStatusEnum matchStatus,
            SettlementStatusEnum expected
    ) {
        SettlementStatusEnum actual = marketType == MarketTypeEnum.ASIAN_HANDICAP
                ? handicapCalculator.calculate(input(
                        marketType,
                        fact(factStatus, matchStatus, null, null),
                        null,
                        null,
                        null
                ))
                : totalGoalsCalculator.calculate(input(
                        marketType,
                        fact(factStatus, matchStatus, null, null),
                        null,
                        null,
                        null
                ));

        assertThat(actual).isEqualTo(expected);
    }

    private static Stream<Arguments> voidAndPendingFacts() {
        return Stream.of(
                Arguments.of(MarketTypeEnum.ASIAN_HANDICAP, MatchResultFactStatusEnum.PENDING,
                        MatchStatusEnum.POSTPONED, SettlementStatusEnum.PENDING),
                Arguments.of(MarketTypeEnum.ASIAN_TOTAL_GOALS, MatchResultFactStatusEnum.PENDING,
                        MatchStatusEnum.ABANDONED, SettlementStatusEnum.PENDING),
                Arguments.of(MarketTypeEnum.ASIAN_HANDICAP, MatchResultFactStatusEnum.VOID,
                        MatchStatusEnum.CANCELLED, SettlementStatusEnum.VOID),
                Arguments.of(MarketTypeEnum.ASIAN_TOTAL_GOALS, MatchResultFactStatusEnum.VOID,
                        MatchStatusEnum.CANCELLED, SettlementStatusEnum.VOID)
        );
    }

    @Test
    void rejectsMissingAsianHandicapPick() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> handicapCalculator.calculate(input(
                        MarketTypeEnum.ASIAN_HANDICAP,
                        finalFact(1, 0),
                        "-0.5",
                        null,
                        null
                )))
                .withMessageContaining("asianHandicapPick");
    }

    @Test
    void rejectsMissingTotalGoalsPick() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> totalGoalsCalculator.calculate(input(
                        MarketTypeEnum.ASIAN_TOTAL_GOALS,
                        finalFact(1, 0),
                        "2.5",
                        null,
                        null
                )))
                .withMessageContaining("totalGoalsPick");
    }

    @Test
    void rejectsMissingMarketLine() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> handicapCalculator.calculate(input(
                        MarketTypeEnum.ASIAN_HANDICAP,
                        finalFact(1, 0),
                        null,
                        AsianHandicapPickEnum.HOME_COVER,
                        null
                )))
                .withMessageContaining("marketLine");
    }

    @ParameterizedTest
    @MethodSource("nonQuarterLines")
    void rejectsNonQuarterMarketLine(String line) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> totalGoalsCalculator.calculate(input(
                        MarketTypeEnum.ASIAN_TOTAL_GOALS,
                        finalFact(1, 0),
                        line,
                        null,
                        TotalGoalsPickEnum.OVER
                )))
                .withMessageContaining("multiple of 0.25");
    }

    private static Stream<Arguments> nonQuarterLines() {
        return Stream.of(Arguments.of("0.3"), Arguments.of("2.1"), Arguments.of("1.6"));
    }

    @ParameterizedTest(name = "{0} 解 {1} 赔率净收益 {2}")
    @MethodSource("profitCases")
    void convertsStatusAndPriceIntoNetProfit(
            SettlementStatusEnum status,
            String price,
            String expected
    ) {
        BigDecimal actual = SettlementMarginSupport.netProfit(
                status,
                price == null ? null : new BigDecimal(price)
        );

        assertThat(actual).isEqualByComparingTo(expected);
    }

    private static Stream<Arguments> profitCases() {
        return Stream.of(
                Arguments.of(SettlementStatusEnum.HIT, "1.85", "0.85"),
                Arguments.of(SettlementStatusEnum.HALF_WIN, "1.85", "0.425"),
                Arguments.of(SettlementStatusEnum.PUSH, null, "0"),
                Arguments.of(SettlementStatusEnum.VOID, null, "0"),
                Arguments.of(SettlementStatusEnum.HALF_LOSS, "1.85", "-0.5"),
                Arguments.of(SettlementStatusEnum.MISS, "1.85", "-1")
        );
    }

    @Test
    void keepsProfitEmptyForPendingStatus() {
        assertThat(SettlementMarginSupport.netProfit(SettlementStatusEnum.PENDING, null)).isNull();
    }

    @Test
    void rejectsSettledStatusWithoutPrice() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SettlementMarginSupport.netProfit(SettlementStatusEnum.HIT, null))
                .withMessageContaining("price");
    }

    private static MarketSettlementInputDto input(
            MarketTypeEnum marketType,
            MatchResultFact matchResultFact,
            String line,
            AsianHandicapPickEnum asianHandicapPick,
            TotalGoalsPickEnum totalGoalsPick
    ) {
        return new MarketSettlementInputDto(
                marketType,
                null,
                matchResultFact,
                null,
                asianHandicapPick,
                totalGoalsPick,
                line == null ? null : new BigDecimal(line)
        );
    }

    private static MatchResultFact finalFact(int homeScore, int awayScore) {
        return fact(MatchResultFactStatusEnum.FINAL, MatchStatusEnum.FINISHED, homeScore, awayScore);
    }

    private static MatchResultFact fact(
            MatchResultFactStatusEnum factStatus,
            MatchStatusEnum matchStatus,
            Integer homeScore,
            Integer awayScore
    ) {
        MatchResultFact fact = new MatchResultFact();
        fact.setFactStatus(factStatus);
        fact.setMatchStatus(matchStatus);
        fact.setHomeScore(homeScore);
        fact.setAwayScore(awayScore);
        return fact;
    }
}
