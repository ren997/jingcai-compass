package com.jingcaicompass.match.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 赛果抓包加载器自检，并守住两条会静默算错的口径。
 *
 * <p>查询区间 {@code 2026-10-20 ~ 2026-10-21}，返回 21 场。</p>
 */
class NovaApiMatchResultFixtureTest {

    private static List<NovaApiMatchResultFixture.Result> results() {
        return NovaApiMatchResultFixture.load(NovaApiMatchResultFixture.RESULTS_2026_10_20_TO_21);
    }

    @Test
    void loadsAllResultsWithUniqueProviderIds() {
        List<NovaApiMatchResultFixture.Result> items = results();

        assertThat(items).hasSize(21);
        Set<Long> ids = items.stream()
                .map(NovaApiMatchResultFixture.Result::matchId)
                .collect(Collectors.toSet());
        assertThat(ids).hasSize(21);
    }

    /**
     * {@code sectionsNo999} 是竞彩官方 90 分钟比分，必须条条可直接解析。
     *
     * <p><b>待验证</b>：接口文档对该字段只写「全场比分」，未明确是否含加时/点球。
     * 判断它等于 90 分钟的依据是「同一响应里还带 {@code winFlag} 与
     * {@code oddsResults}（竞彩玩法的开奖明细），而竞彩足球以 90 分钟为准」。
     * 这个推断需要在阶段 4 用一场真实打过加时的杯赛样本复核一次，
     * 用错不会报错、只会让 ROI/Brier 系统性偏移。</p>
     */
    @Test
    void fullTimeScoreIsAlwaysParsable() {
        assertThat(results())
                .allSatisfy(item -> assertThat(
                        NovaApiMatchResultFixture.Result.isScoreText(item.fullTimeScore())).isTrue());
    }

    @Test
    void parsesFullTimeScoreIntoGoals() {
        NovaApiMatchResultFixture.Result match = results().stream()
                .filter(item -> item.matchId() == 2042108L)
                .findFirst()
                .orElseThrow();

        assertThat(match.matchNumStr()).isEqualTo("周三003");
        assertThat(match.fullTimeScore()).isEqualTo("0:3");
        assertThat(match.halfTimeScore()).isEqualTo("0:0");
        assertThat(match.homeScore()).isZero();
        assertThat(match.awayScore()).isEqualTo(3);
    }

    /**
     * {@code poolStatus} 是派奖状态，不是赛果是否确定。
     *
     * <p>实测有 2 场 {@code poolStatus} 为空但 {@code sectionsNo999} 与
     * {@code matchResultStatus=2} 都齐全（周二008 曼城 5:1、周二005 罗马 4:0）。
     * 若用 {@code poolStatus=Payout} 过滤结算候选，这 2 场会被静默漏掉，
     * 表现为「结算数少了但没有报错」。</p>
     */
    @Test
    void poolStatusIsNotAPreconditionForSettlement() {
        List<NovaApiMatchResultFixture.Result> missingPoolStatus = results().stream()
                .filter(item -> item.poolStatus() == null || item.poolStatus().isBlank())
                .toList();

        assertThat(missingPoolStatus).hasSize(2);
        assertThat(missingPoolStatus)
                .allSatisfy(item -> {
                    assertThat(item.matchResultStatus()).isEqualTo("2");
                    assertThat(NovaApiMatchResultFixture.Result.isScoreText(item.fullTimeScore()))
                            .isTrue();
                });
        assertThat(missingPoolStatus)
                .extracting(NovaApiMatchResultFixture.Result::matchNumStr)
                .containsExactlyInAnyOrder("周二008", "周二005");
    }

    /**
     * 队名必须取 {@code allHomeTeam} / {@code allAwayTeam}（全名），不是 {@code homeTeam}（短名）。
     *
     * <p>赛程接口写入 {@code matches} 的是全名，赛果若用短名会静默把已写好的队名改短
     * （如「大宫松鼠RB」→「大宫松鼠」），此后任何按队名做的比对都会失配。</p>
     */
    @Test
    void fullTeamNameMatchesScheduleWhileShortNameDoesNot() {
        Map<Long, NovaApiScheduleFixture.Match> scheduleById =
                NovaApiScheduleFixture.load(NovaApiScheduleFixture.SCHEDULE_2026_10_21).stream()
                        .collect(Collectors.toMap(
                                NovaApiScheduleFixture.Match::matchId,
                                Function.identity(),
                                (left, right) -> left));

        List<NovaApiMatchResultFixture.Result> overlapping = results().stream()
                .filter(item -> scheduleById.containsKey(item.matchId()))
                .toList();

        // 单源贯通：同一个 matchId 在赛程与赛果里可直接关联，无需任何映射表。
        assertThat(overlapping).hasSize(3);

        assertThat(overlapping).allSatisfy(item -> {
            NovaApiScheduleFixture.Match scheduled = scheduleById.get(item.matchId());
            assertThat(item.allHomeTeam())
                    .as("全名必须与赛程一致：matchId=%s", item.matchId())
                    .isEqualTo(scheduled.homeTeamName());
            assertThat(item.allAwayTeam()).isEqualTo(scheduled.awayTeamName());
        });

        // 短名确实与赛程不同 —— 证明这两套字段不能混用。
        // 2042108 差在主队（大宫松鼠RB → 大宫松鼠），2042107 差在客队（神户胜利船 → 神户胜利），
        // 因此必须两边都比，只比主队会漏掉其中一类。
        List<NovaApiMatchResultFixture.Result> shortNameDiffers = overlapping.stream()
                .filter(item -> {
                    NovaApiScheduleFixture.Match scheduled = scheduleById.get(item.matchId());
                    return !item.homeTeam().equals(scheduled.homeTeamName())
                            || !item.awayTeam().equals(scheduled.awayTeamName());
                })
                .toList();
        assertThat(shortNameDiffers)
                .as("至少一场短名与赛程不一致，说明必须用 allHomeTeam")
                .isNotEmpty();
        assertThat(shortNameDiffers)
                .extracting(NovaApiMatchResultFixture.Result::matchId)
                .containsExactlyInAnyOrder(2042108L, 2042107L);
    }

    /**
     * 赛果的 {@code matchDate} 是<b>开赛自然日</b>，不是竞彩业务日。
     *
     * <p>这是整次单源改造里最容易写错库的一条：赛程接口的 {@code matchDate} 是业务日
     * （10-21），而同一场比赛的赛果返回的是开赛自然日（10-22）。2026-10-21 的 13 场里
     * 有 10 场跨零点，这 10 场若按 {@code (matchDate, matchNumStr)} 去 {@code matches}
     * 里定位，会<b>查不到</b>而整批记失败（场次编号带着业务日的星期前缀，所以不会串场；
     * 但相差整数周的同名场次仍会撞）。</p>
     *
     * <p>因此赛果定位只能用 {@code matchId}（= {@code matches.external_match_id}）。</p>
     */
    @Test
    void resultMatchDateIsKickoffCalendarDateNotLotteryBusinessDate() {
        Map<Long, NovaApiScheduleFixture.Match> scheduleById =
                NovaApiScheduleFixture.load(NovaApiScheduleFixture.SCHEDULE_2026_10_21).stream()
                        .collect(Collectors.toMap(
                                NovaApiScheduleFixture.Match::matchId,
                                Function.identity(),
                                (left, right) -> left));

        List<NovaApiMatchResultFixture.Result> overlapping =
                NovaApiMatchResultFixture.load(NovaApiMatchResultFixture.RESULTS_2026_10_22).stream()
                        .filter(item -> scheduleById.containsKey(item.matchId()))
                        .toList();

        // 业务日 10-21 的 13 场里，10 场在赛果窗口内已经开奖。
        assertThat(overlapping).hasSize(10);

        assertThat(overlapping).allSatisfy(item -> {
            NovaApiScheduleFixture.Match scheduled = scheduleById.get(item.matchId());
            // 前提：这 10 场全部跨零点，否则本测试证明不了日期口径差异。
            assertThat(scheduled.matchStartDate())
                    .as("matchId=%s 应跨零点", item.matchId())
                    .isNotEqualTo(scheduled.matchDate());
            // 赛果日期 = 开赛自然日。
            assertThat(item.matchDate())
                    .as("赛果 matchDate 应等于赛程 matchStartDate：matchId=%s", item.matchId())
                    .isEqualTo(scheduled.matchStartDate());
            // 且不等于竞彩业务日 —— 这正是不该拿它当 lottery_date 的原因。
            assertThat(item.matchDate()).isNotEqualTo(scheduled.matchDate());
        });
    }

    @Test
    void extractsCanonicalFieldsFromNovaEventStream() {
        JsonNode event = NovaApiScheduleFixture.readTree(
                "sporttery/novaapi-soccer-events-2042118.json").path("data").path("matches").get(0);

        assertThat(event.path("matchId").asLong()).isEqualTo(2042118L);
        assertThat(event.path("tfStatusShort").asText()).isEqualTo("PEN");
    }
}
