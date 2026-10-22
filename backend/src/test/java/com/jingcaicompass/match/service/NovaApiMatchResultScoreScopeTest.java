package com.jingcaicompass.match.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.match.dto.SportteryMatchResultDto;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code sectionsNo999} 的 90 分钟口径复核——用一个真实打过加时 + 点球的杯赛样本。
 *
 * <p>这是阶段 4 唯一不能靠推断收口的假设：接口文档只写「全场比分」，若它其实含加时，
 * 亚盘结算与 Brier 会长期系统性偏移而<b>不报任何错</b>。</p>
 *
 * <p>样本：2026-10-22 南美解放者杯 帕尔梅拉斯 vs 弗鲁米嫩塞（{@code matchId=2042118}，
 * 竞彩周三013）。同一场比赛同时有两份真实响应：</p>
 *
 * <ul>
 *   <li>{@code /nova/text/match-results} → {@code sectionsNo999 = "0:1"}（竞彩开奖比分）</li>
 *   <li>{@code /nova/tsd/soccer-events} → {@code tfStatusShort=PEN}（踢到点球）、
 *       {@code ftHomeScore/ftAwayScore = 0:1}、{@code etHomeScore/etAwayScore = 0:0}、
 *       {@code penHomeScore/penAwayScore = 5:3}</li>
 * </ul>
 *
 * <p><b>结论</b>：竞彩开奖比分与 {@code ftHomeScore}（常规 90 分钟）逐字相等，且不等于点球比分；
 * 因此 {@code sectionsNo999} 就是 90 分钟口径，可直接用于亚盘结算。</p>
 *
 * <p><b>顺带修正一条文档认知</b>：{@code etHomeScore/etAwayScore} 是<b>加时阶段自身的比分</b>，
 * 不是「含加时的累计比分」。若按累计理解，本场加时结束后应为 0:1，实测却是 0:0。
 * 这条很重要——按「ft + et」相加会把加时进球重复计入。</p>
 */
class NovaApiMatchResultScoreScopeTest {

    private static final String RESULTS_2026_10_22 = "sporttery/novaapi-match-results-2026-10-22.json";
    private static final String SOCCER_EVENTS = "sporttery/novaapi-soccer-events-2042118.json";

    private static final String MATCH_ID = "2042118";

    @Test
    void extraTimeCupTieKeepsRegulationScoreInCompetitionResult() {
        SportteryMatchResultDto official = officialResult();
        JsonNode event = soccerEvent();

        // 前提：这确实是一场超出 90 分钟的淘汰赛，否则本测试什么也没证明。
        assertThat(event.path("tfStatusShort").asText()).isEqualTo("PEN");
        assertThat(event.path("penHomeScore").asInt()).isEqualTo(5);
        assertThat(event.path("penAwayScore").asInt()).isEqualTo(3);

        // 竞彩开奖比分 = 常规 90 分钟比分。
        assertThat(official.homeScore()).isEqualTo(event.path("ftHomeScore").asInt());
        assertThat(official.awayScore()).isEqualTo(event.path("ftAwayScore").asInt());

        // 且不是点球大战比分（若实现误取了最终结果，这里会挂）。
        assertThat(official.homeScore()).isNotEqualTo(event.path("penHomeScore").asInt());
        assertThat(official.awayScore()).isNotEqualTo(event.path("penAwayScore").asInt());
    }

    /**
     * {@code et*} 是加时阶段自身比分，不是累计。
     *
     * <p>本场 {@code ft=0:1} 而 {@code et=0:0}：若 {@code et} 是累计值，它不可能小于 {@code ft}。
     * 因此任何「ft + et」的换算都会重复计入加时进球——本仓库不读 {@code et*}，
     * 这条断言只防止将来有人「顺手」把它接进结算路径。</p>
     */
    @Test
    void extraTimeScoresAreIncrementalNotCumulative() {
        JsonNode event = soccerEvent();

        assertThat(event.path("etHomeScore").asInt()).isZero();
        assertThat(event.path("etAwayScore").asInt()).isZero();
        assertThat(event.path("ftHomeScore").asInt()).isZero();
        assertThat(event.path("ftAwayScore").asInt()).isEqualTo(1);
    }

    /** 同一场的两份响应能靠 {@code matchId} 直接关联，不需要任何映射表。 */
    @Test
    void competitionResultAndEventStreamShareTheSameMatchId() {
        assertThat(soccerEvent().path("matchId").asLong()).isEqualTo(Long.parseLong(MATCH_ID));
        assertThat(officialResult().matchId()).isEqualTo(MATCH_ID);
    }

    private static SportteryMatchResultDto officialResult() {
        List<SportteryMatchResultDto> items = new NovaMatchResultPayloadMapper(new ObjectMapper())
                .parseItems(
                        readRaw(RESULTS_2026_10_22),
                        LocalDate.of(2026, 10, 22),
                        LocalDate.of(2026, 10, 22)
                );
        return items.stream()
                .filter(item -> MATCH_ID.equals(item.matchId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("fixture must contain match " + MATCH_ID));
    }

    private static JsonNode soccerEvent() {
        JsonNode root = NovaApiScheduleFixture.readTree(SOCCER_EVENTS);
        NovaApiScheduleFixture.requireSuccessCode(root);
        JsonNode matches = root.path("data").path("matches");
        assertThat(matches).hasSize(1);
        return matches.get(0);
    }

    private static String readRaw(String resource) {
        try (InputStream stream = NovaApiMatchResultScoreScopeTest.class
                .getClassLoader()
                .getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("fixture not found on classpath: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
