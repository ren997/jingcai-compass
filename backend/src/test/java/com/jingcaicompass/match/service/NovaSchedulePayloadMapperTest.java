package com.jingcaicompass.match.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.match.dto.SportteryPoolSyncItemDto;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import com.jingcaicompass.match.exception.SportteryDataAccessException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link NovaSchedulePayloadMapper} 对真实抓包的映射守卫。
 *
 * <p>用的样本就是 {@code sporttery/novaapi-schedule-2026-10-21.json}（2026-10-21 抓的
 * 13 场），因此这里的每条断言都对应一个真实存在过的字段组合，而不是构造的理想数据。</p>
 */
class NovaSchedulePayloadMapperTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 10, 21);
    private static final ZoneOffset SHANGHAI = ZoneOffset.ofHours(8);

    private NovaSchedulePayloadMapper mapper;
    private String scheduleJson;

    @BeforeEach
    void setUp() {
        mapper = new NovaSchedulePayloadMapper(new ObjectMapper());
        scheduleJson = readText(NovaApiScheduleFixture.SCHEDULE_2026_10_21);
    }

    @Test
    void mapsEveryMatchOfTheBusinessDay() {
        List<SportteryPoolSyncItemDto> items = mapper.parseItems(scheduleJson, BUSINESS_DATE);

        assertThat(items).hasSize(13);
        assertThat(items).extracting(SportteryPoolSyncItemDto::externalMatchId)
                .doesNotHaveDuplicates()
                .doesNotContainNull();
        // 外部 ID 是单源改造的权威键，必须与供应商原值逐字一致。
        assertThat(items).extracting(SportteryPoolSyncItemDto::externalMatchId)
                .startsWith("2042106");
        assertThat(items).extracting(SportteryPoolSyncItemDto::lotteryMatchNo)
                .startsWith("周三001");
    }

    /**
     * 开赛时间必须由 {@code matchStartDate + matchTime} 拼出。
     *
     * <p>这是本类最重要的一条：13 场里 10 场业务日为 10-21、开赛在 10-22 凌晨。
     * 用 {@code matchDate} 拼会让这些场次整体早 24 小时，而开赛时间决定锁定与
     * 结算窗口——错了不报错，只会让预测窗口整体偏移。</p>
     */
    @Test
    void derivesKickoffFromStartDateNotBusinessDate() {
        List<SportteryPoolSyncItemDto> items = mapper.parseItems(scheduleJson, BUSINESS_DATE);

        SportteryPoolSyncItemDto sameDay = byMatchNo(items, "周三001");
        assertThat(sameDay.kickoffTime())
                .isEqualTo(OffsetDateTime.of(2026, 10, 21, 18, 0, 0, 0, SHANGHAI));

        SportteryPoolSyncItemDto crossMidnight = byMatchNo(items, "周三004");
        assertThat(crossMidnight.kickoffTime())
                .isEqualTo(OffsetDateTime.of(2026, 10, 22, 0, 45, 0, 0, SHANGHAI));

        long crossCount = items.stream()
                .filter(item -> !item.lotteryDate().equals(item.kickoffTime().toLocalDate()))
                .count();
        assertThat(crossCount)
                .as("13 场里有 10 场跨零点；若这里是 0 说明误用了业务日")
                .isEqualTo(10);
    }

    /** {@code lotteryDate} 必须是竞彩业务日，而不是开赛日。 */
    @Test
    void keepsBusinessDateAsLotteryDate() {
        List<SportteryPoolSyncItemDto> items = mapper.parseItems(scheduleJson, BUSINESS_DATE);

        assertThat(items).allSatisfy(item -> assertThat(item.lotteryDate()).isEqualTo(BUSINESS_DATE));
    }

    /**
     * 销售状态只能落到 {@code SCHEDULED}/{@code LOCKED}，绝不能落 {@code FINISHED}。
     *
     * <p>接口没有「比赛是否结束」字段，且会把已完赛的比赛一起返回。若这里映射成
     * {@code FINISHED}，当天所有预测都会被跳过。</p>
     */
    @Test
    void mapsSaleStateToScheduledOrLockedOnly() {
        List<SportteryPoolSyncItemDto> items = mapper.parseItems(scheduleJson, BUSINESS_DATE);

        assertThat(items).extracting(SportteryPoolSyncItemDto::sellStatus)
                // sellStatus 在响应里是字符串 "2"（文档示例也是字符串）。用 asInt 读文本
                // 会静默变 0，所以这里断言的是「原样保留的字符串」而不是数值。
                .containsOnly("2");
        assertThat(items).allSatisfy(item -> assertThat(item.matchStatus())
                .isIn(MatchStatusEnum.SCHEDULED, MatchStatusEnum.LOCKED));
        assertThat(items).noneMatch(item -> item.matchStatus() == MatchStatusEnum.FINISHED);

        assertThat(byMatchNo(items, "周三001").matchStatus()).isEqualTo(MatchStatusEnum.LOCKED);
        assertThat(byMatchNo(items, "周三004").matchStatus()).isEqualTo(MatchStatusEnum.SCHEDULED);
        assertThat(items).filteredOn(item -> item.matchStatus() == MatchStatusEnum.LOCKED).hasSize(3);
    }

    /** 文档定义了 {@code SoldOut}，实测当日一次都没出现——但它必须被显式处理。 */
    @Test
    void treatsSoldOutAsStillScheduled() {
        List<SportteryPoolSyncItemDto> items = mapper.parseItems(
                singleMatchPayload("SoldOut", "\"2036-03-01\"", "\"20:00\"", ""),
                LocalDate.of(2036, 3, 1)
        );

        assertThat(items).hasSize(1);
        assertThat(items.get(0).matchStatus()).isEqualTo(MatchStatusEnum.SCHEDULED);
    }

    /** 未文档化的销售状态保守按已锁定处理，绝不抛错阻断整日同步。 */
    @Test
    void fallsBackToLockedOnUnknownSaleState() {
        List<SportteryPoolSyncItemDto> items = mapper.parseItems(
                singleMatchPayload("SomethingNew", "\"2036-03-01\"", "\"20:00\"", ""),
                LocalDate.of(2036, 3, 1)
        );

        assertThat(items).hasSize(1);
        assertThat(items.get(0).matchStatus()).isEqualTo(MatchStatusEnum.LOCKED);
    }

    /** HAD 与 HHAD 的 SP、以及竞彩官方让球都要取到。 */
    @Test
    void mapsHadHhadSpAndOfficialHandicap() {
        List<SportteryPoolSyncItemDto> items = mapper.parseItems(scheduleJson, BUSINESS_DATE);
        SportteryPoolSyncItemDto first = byMatchNo(items, "周三001");

        assertThat(first.hadHomeSp()).isEqualByComparingTo("1.57");
        assertThat(first.hadDrawSp()).isEqualByComparingTo("3.95");
        assertThat(first.hadAwaySp()).isEqualByComparingTo("4.20");
        assertThat(first.hhadHomeSp()).isEqualByComparingTo("3.10");
        assertThat(first.hhadDrawSp()).isEqualByComparingTo("3.12");
        assertThat(first.hhadAwaySp()).isEqualByComparingTo("2.06");
        // HHAD.goalLine 是 "-1.00"，写入整数让球字段时应为 -1。
        assertThat(first.officialHandicap()).isEqualByComparingTo(BigDecimal.valueOf(-1));
    }

    /** 竞彩允许只开让球不开胜平负：实测 周三011 只有 HHAD，缺玩法不是错误。 */
    @Test
    void allowsMissingHadPool() {
        List<SportteryPoolSyncItemDto> items = mapper.parseItems(scheduleJson, BUSINESS_DATE);
        SportteryPoolSyncItemDto item = byMatchNo(items, "周三011");

        assertThat(item.hadHomeSp()).isNull();
        assertThat(item.hadDrawSp()).isNull();
        assertThat(item.hadAwaySp()).isNull();
        assertThat(item.hhadHomeSp()).isEqualByComparingTo("2.23");
        assertThat(item.officialHandicap()).isEqualByComparingTo(BigDecimal.valueOf(-2));
    }

    @Test
    void ignoresMatchesOfOtherBusinessDays() {
        assertThat(mapper.parseItems(scheduleJson, BUSINESS_DATE.minusDays(1))).isEmpty();
        assertThat(mapper.parseItems(scheduleJson, BUSINESS_DATE.plusDays(1))).isEmpty();
    }

    /**
     * 业务错误码必须抛错，不能返回空列表。
     *
     * <p>NovaAPI 的鉴权失败、额度超限都返回 HTTP 200 + 非 200 的 {@code code}。
     * 若这里当成「当天没有比赛」，自动发布会空转一整天且日志无异常。</p>
     */
    @Test
    void rejectsNonSuccessCode() {
        assertThatThrownBy(() -> mapper.parseItems(
                """
                {"code":401,"message":"invalid signature","data":[]}
                """,
                BUSINESS_DATE
        ))
                .isInstanceOf(SportteryDataAccessException.class)
                .hasMessageContaining("code=401");
    }

    /** 结构漂移要吵闹：{@code {code,data:{providers}}` 形态曾被静默吞成空列表。 */
    @Test
    void rejectsNonArrayData() {
        assertThatThrownBy(() -> mapper.parseItems(
                """
                {"code":200,"message":"ok","data":{"providers":[]}}
                """,
                BUSINESS_DATE
        ))
                .isInstanceOf(SportteryDataAccessException.class)
                .hasMessageContaining("data 应为数组");
    }

    private static SportteryPoolSyncItemDto byMatchNo(
            List<SportteryPoolSyncItemDto> items,
            String lotteryMatchNo
    ) {
        return items.stream()
                .filter(item -> lotteryMatchNo.equals(item.lotteryMatchNo()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing " + lotteryMatchNo));
    }

    private static String singleMatchPayload(
            String matchStatus,
            String matchDate,
            String matchTime,
            String extra
    ) {
        return """
                {"code":200,"message":"ok","data":[{"matchMain":{
                  "matchId":900001,"matchNumStr":"周一001","matchDate":%s,
                  "matchStartDate":%s,"matchTime":%s,"matchStatus":"%s","sellStatus":"1",
                  "leagueName":"测试联赛","homeTeamName":"主队","awayTeamName":"客队"%s
                },"matchOddsList":[{"poolCode":"HAD","homeOdds":1.9,"drawOdds":3.3,"awayOdds":3.8,
                  "goalLine":""}]}]}
                """.formatted(matchDate, matchDate, matchTime, matchStatus, extra);
    }

    private static String readText(String resourceName) {
        try (InputStream stream = NovaSchedulePayloadMapperTest.class.getClassLoader()
                .getResourceAsStream(resourceName)) {
            if (stream == null) {
                throw new IllegalStateException("fixture not found: " + resourceName);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
