package com.jingcaicompass.match.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.match.dto.SportteryMatchResultDto;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import com.jingcaicompass.match.exception.SportteryDataAccessException;
import com.jingcaicompass.system.provider.ProviderErrorCategory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * NovaAPI 赛果解析契约，全部基于 2026-10-20~21 的真实抓包（21 场）。
 *
 * <p>这里守的都是「写错了不报错、只会让结算悄悄错」的口径：比分取哪个字段、
 * 业务日用哪个字段、哪些情况必须整体失败。</p>
 */
class NovaMatchResultPayloadMapperTest {

    private static final LocalDate START = LocalDate.of(2026, 10, 20);
    private static final LocalDate END = LocalDate.of(2026, 10, 21);

    private final NovaMatchResultPayloadMapper mapper =
            new NovaMatchResultPayloadMapper(new ObjectMapper());

    @Test
    void parsesEverySettledResultFromRealCapture() {
        List<SportteryMatchResultDto> items = mapper.parseItems(rawFixture(), START, END);

        assertThat(items).hasSize(21);
        // matchId 与赛程同值，单源关联的唯一依据。
        assertThat(items).extracting(SportteryMatchResultDto::matchId)
                .contains("2042108", "2042073")
                .doesNotHaveDuplicates();
    }

    /**
     * 比分只能取 {@code sectionsNo999}。
     *
     * <p>{@code sectionsNo1} 是半场比分。2042108（大宫松鼠RB vs 富山胜利）半场 0:0、全场 0:3，
     * 若误读半场，结算与 Brier 会一起偏，但不会报任何错。</p>
     */
    @Test
    void readsFullTimeScoreFromSectionsNo999NotHalfTime() {
        Map<String, NovaApiMatchResultFixture.Result> fixtureById =
                NovaApiMatchResultFixture.load(NovaApiMatchResultFixture.RESULTS_2026_10_20_TO_21)
                        .stream()
                        .collect(Collectors.toMap(
                                result -> String.valueOf(result.matchId()),
                                Function.identity()));

        List<SportteryMatchResultDto> items = mapper.parseItems(rawFixture(), START, END);
        assertThat(items).allSatisfy(item -> {
            NovaApiMatchResultFixture.Result expected = fixtureById.get(item.matchId());
            assertThat(item.homeScore()).isEqualTo(expected.homeScore());
            assertThat(item.awayScore()).isEqualTo(expected.awayScore());
        });

        SportteryMatchResultDto resolved = items.stream()
                .filter(item -> "2042108".equals(item.matchId()))
                .findFirst()
                .orElseThrow();
        assertThat(resolved.homeScore()).isZero();
        assertThat(resolved.awayScore()).isEqualTo(3);
        // 半场 0:0 ≠ 全场 0:3，证明读的不是 sectionsNo1。
        assertThat(fixtureById.get("2042108").halfTimeScore()).isEqualTo("0:0");

        // 至少一场半场与全场不同，否则这条断言形同虚设。
        assertThat(fixtureById.values())
                .anySatisfy(result -> assertThat(result.halfTimeScore())
                        .isNotEqualTo(result.fullTimeScore()));
    }

    /** 业务日取 {@code matchDate}（竞彩销售日），与赛程同名同义，跨零点场次也一致。 */
    @Test
    void keepsLotteryIdentityFromBusinessDateAndMatchNumber() {
        SportteryMatchResultDto item = mapper.parseItems(rawFixture(), START, END).stream()
                .filter(candidate -> "2042108".equals(candidate.matchId()))
                .findFirst()
                .orElseThrow();

        assertThat(item.lotteryDate()).isEqualTo(LocalDate.of(2026, 10, 21));
        assertThat(item.lotteryMatchNo()).isEqualTo("周三003");
    }

    /**
     * 单源赛果一律按「已开奖终态 + 可能被官方改判」写入。
     *
     * <p>{@code amended=true} 的理由：单源之后不存在跨源比分裂；比分变化只可能是竞彩改判，
     * 而写入器仍保留「终态不得回退 PENDING」「官方事实不得被人工替代」两条硬约束。</p>
     */
    @Test
    void marksEveryResultAsOfficialFinalSubjectToAmendment() {
        assertThat(mapper.parseItems(rawFixture(), START, END)).allSatisfy(item -> {
            assertThat(item.matchStatus()).isEqualTo(MatchStatusEnum.FINISHED);
            assertThat(item.amended()).isTrue();
            assertThat(item.officialVoid()).isFalse();
        });
    }

    /** 该接口没有逐条更新时间，取信封 {@code timestamp}（毫秒纪元）作为供应商时间。 */
    @Test
    void derivesProviderUpdatedAtFromEnvelopeTimestamp() {
        OffsetDateTime expected = OffsetDateTime.parse("2026-10-21T22:56:03.148+08:00");

        assertThat(mapper.parseItems(rawFixture(), START, END))
                .allSatisfy(item -> assertThat(item.providerUpdatedAt()).isEqualTo(expected));
    }

    /**
     * 信封缺时间戳时退回抓取时刻，而不是 null。
     *
     * <p>返回 null 会被写入器整条拒绝（{@code providerUpdatedAt must not be null}），
     * 表现为「同步显示成功、赛果一条没写」。</p>
     */
    @Test
    void fallsBackToObservationTimeWhenEnvelopeTimestampMissing() {
        String payload = """
                {"code":200,"message":"操作成功","data":{"results":[
                  {"matchId":2042108,"matchDate":"2026-10-21","matchNumStr":"周三003",
                   "sectionsNo999":"0:3","matchResultStatus":"2"}
                ]},"success":true}
                """;

        List<SportteryMatchResultDto> items = mapper.parseItems(payload, START, END);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).providerUpdatedAt()).isNotNull();
    }

    /** 服务端会回显并遵守区间参数；这里额外兜一层，避免它哪天忽略参数后写超范围赛果。 */
    @Test
    void filtersResultsOutsideRequestedBusinessDateWindow() {
        List<SportteryMatchResultDto> onlyTwentyFirst =
                mapper.parseItems(rawFixture(), LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 21));

        assertThat(onlyTwentyFirst).hasSize(13);
        assertThat(onlyTwentyFirst).extracting(SportteryMatchResultDto::lotteryDate)
                .containsOnly(LocalDate.of(2026, 10, 21));
    }

    /**
     * {@code code != 200} 必须整体失败。
     *
     * <p>NovaAPI 把「密钥过期」放在响应体里而 HTTP 仍是 200。返回空列表会让同步记 SUCCESS、
     * 赛果 0 条，结算永远静默不执行。</p>
     */
    @Test
    void failsWholePayloadOnNonSuccessCode() {
        assertThatThrownBy(() -> mapper.parseItems(
                "{\"code\":401,\"message\":\"API密钥无效或已过期\",\"data\":null}", START, END))
                .isInstanceOf(SportteryDataAccessException.class)
                .hasMessageContaining("code=401")
                .satisfies(exception -> assertThat(((SportteryDataAccessException) exception).category())
                        .isEqualTo(ProviderErrorCategory.UPSTREAM_FAILURE));
    }

    /**
     * 「已开奖却没有比分」与「没开奖却有比分」都是契约破裂，整体失败。
     *
     * <p>逐条跳过会让那场比赛永久停在 PENDING 且无人察觉；整体失败会立刻在
     * {@code data_sync_runs} 上可见。实测该接口只返回已开奖场次（未来日期返回空数组），
     * 因此 {@code matchResultStatus} 必须是 {@code 2}。</p>
     */
    @Test
    void failsWhenPublishedResultHasNoScore() {
        String payload = """
                {"code":200,"data":{"results":[
                  {"matchId":2042108,"matchDate":"2026-10-21","matchNumStr":"周三003",
                   "sectionsNo999":"","matchResultStatus":"2"}
                ]}}
                """;

        assertThatThrownBy(() -> mapper.parseItems(payload, START, END))
                .isInstanceOf(SportteryDataAccessException.class)
                .hasMessageContaining("sectionsNo999");
    }

    @Test
    void failsWhenScoreTextIsMalformed() {
        String payload = """
                {"code":200,"data":{"results":[
                  {"matchId":2042108,"matchDate":"2026-10-21","matchNumStr":"周三003",
                   "sectionsNo999":"0:3(加时)","matchResultStatus":"2"}
                ]}}
                """;

        assertThatThrownBy(() -> mapper.parseItems(payload, START, END))
                .isInstanceOf(SportteryDataAccessException.class)
                .hasMessageContaining("比分无法解析");
    }

    @Test
    void failsWhenResultStatusIsNotPublished() {
        String payload = """
                {"code":200,"data":{"results":[
                  {"matchId":2042108,"matchDate":"2026-10-21","matchNumStr":"周三003",
                   "sectionsNo999":"0:3","matchResultStatus":"1"}
                ]}}
                """;

        assertThatThrownBy(() -> mapper.parseItems(payload, START, END))
                .isInstanceOf(SportteryDataAccessException.class)
                .hasMessageContaining("matchResultStatus=1");
    }

    @Test
    void failsWhenResultsShapeChanges() {
        assertThatThrownBy(() -> mapper.parseItems(
                "{\"code\":200,\"data\":{\"results\":{\"matchId\":1}}}", START, END))
                .isInstanceOf(SportteryDataAccessException.class)
                .hasMessageContaining("data.results 应为数组");
        assertThatThrownBy(() -> mapper.parseItems(
                "{\"code\":200,\"data\":[]}", START, END))
                .isInstanceOf(SportteryDataAccessException.class)
                .hasMessageContaining("data 应为对象");
    }

    /** 当日无开奖场次是正常情况（实测未来日期返回空数组），不是错误。 */
    @Test
    void returnsEmptyWhenProviderHasNoResults() {
        assertThat(mapper.parseItems("{\"code\":200,\"data\":{\"results\":[]}}", START, END)).isEmpty();
        assertThat(mapper.parseItems("{\"code\":200,\"data\":null}", START, END)).isEmpty();
    }

    @Test
    void failsOnBlankPayload() {
        assertThatThrownBy(() -> mapper.parseItems("  ", START, END))
                .isInstanceOf(SportteryDataAccessException.class)
                .hasMessageContaining("空响应");
    }

    private static String rawFixture() {
        String resource = NovaApiMatchResultFixture.RESULTS_2026_10_20_TO_21;
        try (InputStream stream = NovaMatchResultPayloadMapperTest.class
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
