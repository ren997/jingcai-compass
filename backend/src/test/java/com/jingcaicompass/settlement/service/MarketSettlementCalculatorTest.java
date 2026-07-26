package com.jingcaicompass.settlement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.jingcaicompass.match.entity.MatchResultFact;
import com.jingcaicompass.match.enums.MatchResultFactStatusEnum;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import com.jingcaicompass.prediction.enums.HandicapPickEnum;
import com.jingcaicompass.settlement.dto.MarketSettlementInputDto;
import com.jingcaicompass.settlement.enums.MarketTypeEnum;
import com.jingcaicompass.settlement.enums.SettlementStatusEnum;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class MarketSettlementCalculatorTest {

    private final WinDrawLossSettlementCalculator hadCalculator = new WinDrawLossSettlementCalculator();
    private final SportteryHandicapSettlementCalculator hhadCalculator = new SportteryHandicapSettlementCalculator();
    private final MarketSettlementCalculatorRouter router = new MarketSettlementCalculatorRouter(
            List.of(hadCalculator, hhadCalculator)
    );

    @ParameterizedTest(name = "{0}")
    @MethodSource("hadCases")
    void settlesHadFromTheExplicitSelection(
            String description,
            int homeScore,
            int awayScore,
            HandicapPickEnum selectedOutcome,
            SettlementStatusEnum expected
    ) {
        SettlementStatusEnum actual = hadCalculator.calculate(input(
                MarketTypeEnum.HAD,
                selectedOutcome,
                finalFact(homeScore, awayScore),
                null
        ));

        assertThat(actual).as(description).isEqualTo(expected);
    }

    private static Stream<Arguments> hadCases() {
        return Stream.of(
                Arguments.of("主胜命中", 2, 1, HandicapPickEnum.HOME_WIN, SettlementStatusEnum.HIT),
                Arguments.of("主胜未中", 2, 1, HandicapPickEnum.DRAW, SettlementStatusEnum.MISS),
                Arguments.of("平局命中", 1, 1, HandicapPickEnum.DRAW, SettlementStatusEnum.HIT),
                Arguments.of("平局未中", 1, 1, HandicapPickEnum.AWAY_WIN, SettlementStatusEnum.MISS),
                Arguments.of("客胜命中", 0, 1, HandicapPickEnum.AWAY_WIN, SettlementStatusEnum.HIT),
                Arguments.of("客胜未中", 0, 1, HandicapPickEnum.HOME_WIN, SettlementStatusEnum.MISS)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hhadCases")
    void settlesHhadWithTheOfficialHomeHandicap(
            String description,
            int homeScore,
            int awayScore,
            String officialHandicap,
            HandicapPickEnum selectedOutcome,
            SettlementStatusEnum expected
    ) {
        SettlementStatusEnum actual = hhadCalculator.calculate(input(
                MarketTypeEnum.HHAD,
                selectedOutcome,
                finalFact(homeScore, awayScore),
                new BigDecimal(officialHandicap)
        ));

        assertThat(actual).as(description).isEqualTo(expected);
    }

    private static Stream<Arguments> hhadCases() {
        return Stream.of(
                Arguments.of("正让球后的主胜", 1, 2, "2", HandicapPickEnum.HOME_WIN, SettlementStatusEnum.HIT),
                Arguments.of("负让球后的平局", 2, 1, "-1", HandicapPickEnum.DRAW, SettlementStatusEnum.HIT),
                Arguments.of("零让球后的客胜", 0, 1, "0", HandicapPickEnum.AWAY_WIN, SettlementStatusEnum.HIT),
                Arguments.of("让球结果与选项不符", 1, 2, "2", HandicapPickEnum.DRAW, SettlementStatusEnum.MISS),
                Arguments.of("整数小数表示仍为有效让球", 1, 1, "1.0", HandicapPickEnum.HOME_WIN, SettlementStatusEnum.HIT)
        );
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("pendingFacts")
    void keepsUnconfirmedFactsPending(MarketTypeEnum marketType, MatchStatusEnum matchStatus) {
        SettlementStatusEnum actual = router.calculate(input(
                marketType,
                null,
                fact(MatchResultFactStatusEnum.PENDING, matchStatus, null, null),
                null
        ));

        assertThat(actual).isEqualTo(SettlementStatusEnum.PENDING);
    }

    private static Stream<Arguments> pendingFacts() {
        return Stream.of(
                Arguments.of(MarketTypeEnum.HAD, MatchStatusEnum.POSTPONED),
                Arguments.of(MarketTypeEnum.HHAD, MatchStatusEnum.POSTPONED),
                Arguments.of(MarketTypeEnum.HAD, MatchStatusEnum.CANCELLED),
                Arguments.of(MarketTypeEnum.HHAD, MatchStatusEnum.CANCELLED),
                Arguments.of(MarketTypeEnum.HAD, MatchStatusEnum.ABANDONED),
                Arguments.of(MarketTypeEnum.HHAD, MatchStatusEnum.ABANDONED)
        );
    }

    @ParameterizedTest
    @EnumSource(MarketTypeEnum.class)
    void returnsVoidOnlyForAnOfficialVoidFact(MarketTypeEnum marketType) {
        SettlementStatusEnum actual = router.calculate(input(
                marketType,
                null,
                fact(MatchResultFactStatusEnum.VOID, MatchStatusEnum.CANCELLED, null, null),
                null
        ));

        assertThat(actual).isEqualTo(SettlementStatusEnum.VOID);
    }

    @Test
    void rejectsFinalFactWithMissingScore() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> hadCalculator.calculate(input(
                        MarketTypeEnum.HAD,
                        HandicapPickEnum.HOME_WIN,
                        fact(MatchResultFactStatusEnum.FINAL, MatchStatusEnum.FINISHED, null, 1),
                        null
                )))
                .withMessageContaining("requires both scores");
    }

    @ParameterizedTest
    @MethodSource("negativeScoreFacts")
    void rejectsNegativeFinalScores(int homeScore, int awayScore) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> hadCalculator.calculate(input(
                        MarketTypeEnum.HAD,
                        HandicapPickEnum.HOME_WIN,
                        finalFact(homeScore, awayScore),
                        null
                )))
                .withMessageContaining("must not be negative");
    }

    private static Stream<Arguments> negativeScoreFacts() {
        return Stream.of(Arguments.of(-1, 0), Arguments.of(0, -1));
    }

    @Test
    void rejectsMissingSelectionForFinalSettlement() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> hadCalculator.calculate(input(
                        MarketTypeEnum.HAD,
                        null,
                        finalFact(1, 0),
                        null
                )))
                .withMessageContaining("selectedOutcome");
    }

    @Test
    void rejectsMissingHhadHandicap() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> hhadCalculator.calculate(input(
                        MarketTypeEnum.HHAD,
                        HandicapPickEnum.HOME_WIN,
                        finalFact(1, 0),
                        null
                )))
                .withMessageContaining("officialHandicap");
    }

    @ParameterizedTest
    @MethodSource("nonIntegerHandicaps")
    void rejectsNonIntegerHhadHandicap(String officialHandicap) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> hhadCalculator.calculate(input(
                        MarketTypeEnum.HHAD,
                        HandicapPickEnum.HOME_WIN,
                        finalFact(1, 0),
                        new BigDecimal(officialHandicap)
                )))
                .withMessageContaining("must be an integer");
    }

    private static Stream<Arguments> nonIntegerHandicaps() {
        return Stream.of(Arguments.of("0.5"), Arguments.of("-1.25"));
    }

    @Test
    void rejectsUnknownMarket() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> router.calculate(input(
                        null,
                        HandicapPickEnum.HOME_WIN,
                        finalFact(1, 0),
                        null
                )))
                .withMessageContaining("marketType");
    }

    @Test
    void rejectsMissingFactOrFactStatus() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> hadCalculator.calculate(input(
                        MarketTypeEnum.HAD,
                        HandicapPickEnum.HOME_WIN,
                        null,
                        null
                )))
                .withMessageContaining("matchResultFact");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> hadCalculator.calculate(input(
                        MarketTypeEnum.HAD,
                        HandicapPickEnum.HOME_WIN,
                        new MatchResultFact(),
                        null
                )))
                .withMessageContaining("factStatus");
    }

    private static MarketSettlementInputDto input(
            MarketTypeEnum marketType,
            HandicapPickEnum selectedOutcome,
            MatchResultFact matchResultFact,
            BigDecimal officialHandicap
    ) {
        return new MarketSettlementInputDto(marketType, selectedOutcome, matchResultFact, officialHandicap);
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
