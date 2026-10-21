package com.jingcaicompass.match.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 赛程抓包加载器自检，同时把「字段语义」钉成回归守卫。
 *
 * <p>这里断言的不是加载器实现细节，而是 NovaAPI 的真实数据形状。任何一条失败都意味着
 * 上游改了契约，必须重新核对映射规则再改断言。</p>
 */
class NovaApiScheduleFixtureTest {

    private static List<NovaApiScheduleFixture.Match> schedule() {
        return NovaApiScheduleFixture.load(NovaApiScheduleFixture.SCHEDULE_2026_10_21);
    }

    @Test
    void loadsAllMatchesWithUniqueProviderIds() {
        List<NovaApiScheduleFixture.Match> matches = schedule();

        assertThat(matches).hasSize(13);
        Set<Long> ids = matches.stream()
                .map(NovaApiScheduleFixture.Match::matchId)
                .collect(Collectors.toSet());
        assertThat(ids).hasSize(13);
        assertThat(matches.get(0).matchId()).isEqualTo(2042106L);
        assertThat(matches.get(0).matchNumStr()).isEqualTo("周三001");
        assertThat(matches.get(0).leagueName()).isEqualTo("日本职业联赛");
        assertThat(matches.get(0).homeTeamName()).isEqualTo("东京FC");
        assertThat(matches.get(0).awayTeamName()).isEqualTo("柏太阳神");
    }

    /**
     * {@code matchStatus} 只有两个取值，且都不是「比赛结束」。
     *
     * <p>{@code Closed} 表示销售截止（实测 3 场全部是已经过了开赛时间的比赛）；
     * 接口同时还会带回已完赛的比赛，因此这个字段<b>不能</b>映射为 {@code FINISHED}，
     * 否则预测链路会把当天所有比赛当成已结束而全部跳过。</p>
     *
     * <p><b>注意：接口文档给出的全集是 {@code Selling(销售中) / SoldOut(已售罄) /
     * Closed(已关闭)}，当日只观测到前两者中的 Selling 与 Closed。</b>
     * {@code SoldOut} 从未出现在抓包里，但映射的 switch 必须显式处理它并给
     * {@code default} 抛错 —— 箭头 switch <b>语句</b>不要求穷尽且编译不报错，
     * 遗漏的取值会被静默丢弃。</p>
     */
    @Test
    void matchStatusOnlyCarriesSaleStateNotMatchOutcome() {
        Map<String, Long> counts = schedule().stream()
                .collect(Collectors.groupingBy(
                        NovaApiScheduleFixture.Match::matchStatus, Collectors.counting()));

        // 文档记录的完整取值域，用作映射实现的对照表。
        assertThat(Set.of("Selling", "SoldOut", "Closed"))
                .containsAll(counts.keySet());

        assertThat(counts).containsOnlyKeys("Closed", "Selling");
        assertThat(counts).containsEntry("Closed", 3L);
        assertThat(counts).containsEntry("Selling", 10L);
        assertThat(counts).doesNotContainKey("Finished");
        assertThat(counts).doesNotContainKey("Ended");

        // 三场 Closed 都已经过了开赛时间，印证 Closed 的语义是「已截止销售」。
        assertThat(schedule().stream()
                .filter(m -> "Closed".equals(m.matchStatus()))
                .map(NovaApiScheduleFixture.Match::matchNumStr))
                .containsExactly("周三001", "周三002", "周三003");
    }

    /**
     * {@code sellStatus} 是字符串枚举（文档：0 未开售 / 1 开售中 / 2 已停售），不是整数。
     *
     * <p>抓包里它形如 {@code "2"}。按数字读取虽然也能得到 2，但一旦上游改成返回
     * {@code "SoldOut"} 之类的文本就会静默变成 0。</p>
     */
    @Test
    void sellStatusIsStringEnumNotNumericCode() {
        assertThat(schedule())
                .allSatisfy(m -> assertThat(m.sellStatus()).isEqualTo("2"));
    }

    /**
     * 开赛时间必须由 {@code matchStartDate + matchTime} 拼，不能用 {@code matchDate}。
     *
     * <p>当日 13 场里有 10 场跨零点：业务日 10-21 但开赛是 10-22。用业务日拼会让
     * 开赛时间整体提前 24 小时，而开赛时间决定锁定与结算窗口，错了不会报错。</p>
     */
    @Test
    void kickoffMustComeFromStartDateNotLotteryDate() {
        List<NovaApiScheduleFixture.Match> matches = schedule();

        assertThat(matches.stream().filter(NovaApiScheduleFixture.Match::crossesMidnight))
                .as("当日 10 场跨零点，用业务日拼开赛时间必错")
                .hasSize(10);

        NovaApiScheduleFixture.Match crosses = matches.stream()
                .filter(m -> m.matchId() == 2042109L)
                .findFirst()
                .orElseThrow();
        assertThat(crosses.matchDate()).isEqualTo("2026-10-21");
        assertThat(crosses.kickoff()).isEqualTo(
                OffsetDateTime.parse("2026-10-22T00:45:00+08:00"));

        // 用业务日会得到 10-21T00:45，比真实开赛早 24 小时 —— 显式断言这个错误值不在实际值上。
        assertThat(crosses.kickoff()).isNotEqualTo(
                OffsetDateTime.parse("2026-10-21T00:45:00+08:00"));
    }

    @Test
    void kickoffIsInterpretedAsShanghaiOffset() {
        NovaApiScheduleFixture.Match sameDay = schedule().stream()
                .filter(m -> m.matchId() == 2042106L)
                .findFirst()
                .orElseThrow();

        assertThat(sameDay.crossesMidnight()).isFalse();
        assertThat(sameDay.kickoff()).isEqualTo(
                OffsetDateTime.parse("2026-10-21T18:00:00+08:00"));
        assertThat(sameDay.kickoff().getOffset().getTotalSeconds()).isEqualTo(8 * 3600);
    }

    /**
     * 业务错误也返回 HTTP 200，只看状态码会把「鉴权失败」当成「当天没有比赛」。
     */
    @Test
    void rejectsNonSuccessBusinessCode() {
        assertThatThrownBy(() -> NovaApiScheduleFixture.requireSuccessCode(
                json("{\"code\":401,\"message\":\"signature invalid\",\"data\":null}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("401")
                .hasMessageContaining("signature invalid");

        NovaApiScheduleFixture.requireSuccessCode(
                json("{\"code\":200,\"message\":\"ok\",\"data\":[]}"));
    }

    /** 已完赛比赛会同时出现在赛程接口里，这是「赛程不能写 FINISHED」的直接证据。 */
    @Test
    void includesAlreadyFinishedMatchesAlongsideUpcomingOnes() {
        Map<Long, String> resultsById = NovaApiMatchResultFixture
                .load(NovaApiMatchResultFixture.RESULTS_2026_10_20_TO_21)
                .stream()
                .collect(Collectors.toMap(
                        item -> item.matchId(),
                        NovaApiMatchResultFixture.Result::matchNumStr));

        List<NovaApiScheduleFixture.Match> finishedButStillListed = schedule().stream()
                .filter(m -> resultsById.containsKey(m.matchId()))
                .toList();

        assertThat(finishedButStillListed)
                .as("赛程接口带回已完赛比赛，故 matchStatus 不可当比赛结果使用")
                .hasSize(3);
        assertThat(finishedButStillListed)
                .allSatisfy(m -> assertThat(m.matchStatus()).isEqualTo("Closed"));
    }

    private static JsonNode json(String body) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
