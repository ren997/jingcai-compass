package com.jingcaicompass.odds.service;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实抓包加载器自检：防止「加载器与验收断言同错」。 */
class NovaApiOddsFixtureTest {

    @Test
    void loadsExpectedQuoteAndBookmakerCounts() {
        NovaApiOddsFixture.Event first = NovaApiOddsFixture.load(NovaApiOddsFixture.EVENT_2492881);
        assertThat(first.eventId()).isEqualTo("2492881");
        assertThat(first.matchId()).isEqualTo(2042106L);
        assertThat(first.handicap()).hasSize(43);
        assertThat(first.handicapBookmakerCount()).isEqualTo(4);
        assertThat(first.totals()).hasSize(47);
        assertThat(first.totalsBookmakerCount()).isEqualTo(4);

        NovaApiOddsFixture.Event second = NovaApiOddsFixture.load(NovaApiOddsFixture.EVENT_2407136);
        assertThat(second.eventId()).isEqualTo("2407136");
        assertThat(second.matchId()).isEqualTo(2041974L);
        assertThat(second.handicap()).hasSize(45);
        assertThat(second.handicapBookmakerCount()).isEqualTo(5);
        assertThat(second.totals()).hasSize(37);
        assertThat(second.totalsBookmakerCount()).isEqualTo(5);

        NovaApiOddsFixture.Event third = NovaApiOddsFixture.load(NovaApiOddsFixture.EVENT_2594567);
        assertThat(third.eventId()).isEqualTo("2594567");
        assertThat(third.matchId()).isNull();
        assertThat(third.handicap()).hasSize(55);
        assertThat(third.handicapBookmakerCount()).isEqualTo(6);
        assertThat(third.totals()).hasSize(57);
        assertThat(third.totalsBookmakerCount()).isEqualTo(6);
    }

    @Test
    void mapsChineseBookmakerNamesToStableAsciiCodes() {
        assertThat(NovaApiOddsFixture.bookmakerCode("平博")).isEqualTo("pinnacle");
        assertThat(NovaApiOddsFixture.bookmakerCode("必发")).isEqualTo("betfair");
        assertThat(NovaApiOddsFixture.bookmakerCode("威廉希尔")).isEqualTo("williamhill");
        assertThat(NovaApiOddsFixture.bookmakerCode("Bet365")).isEqualTo("bet365");
        assertThat(NovaApiOddsFixture.bookmakerCode("1xBet")).isEqualTo("1xbet");
        assertThat(NovaApiOddsFixture.bookmakerCode("韦德")).isEqualTo("betvictor");
        assertThat(NovaApiOddsFixture.bookmakerCode("利记")).isEqualTo("liji");
        // 未登记的庄家名回退为小写原文，保证不会静默命中白名单。
        assertThat(NovaApiOddsFixture.bookmakerCode("UnknownBook")).isEqualTo("unknownbook");
    }

    @Test
    void pairsBothSidesOnIdenticalLinesWithUsablePrices() {
        NovaApiOddsFixture.Event event = NovaApiOddsFixture.load(NovaApiOddsFixture.EVENT_2492881);

        for (AsianMarketConsensusAggregator.Quote quote : event.handicap()) {
            assertThat(quote.firstPrice()).isGreaterThan(BigDecimal.ONE);
            assertThat(quote.secondPrice()).isGreaterThan(BigDecimal.ONE);
            assertThat(quote.capturedAt()).isNotNull();
            assertThat(quote.line()).isNotNull();
        }
        assertThat(event.handicap().stream()
                .filter(quote -> "pinnacle".equals(quote.bookmakerCode()))
                .count()).isEqualTo(9);
    }

    @Test
    void mostBalancedLineUsesSingleBookmakerSample() {
        NovaApiOddsFixture.Event event = NovaApiOddsFixture.load(NovaApiOddsFixture.EVENT_2492881);

        assertThat(NovaApiOddsFixture.mostBalancedLine(event.handicap(), "pinnacle"))
                .contains(new BigDecimal("-0.25"));
        assertThat(NovaApiOddsFixture.mostBalancedLine(event.totals(), "pinnacle"))
                .contains(new BigDecimal("2.75"));
    }
}
