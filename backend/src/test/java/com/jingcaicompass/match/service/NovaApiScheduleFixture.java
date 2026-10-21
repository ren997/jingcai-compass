package com.jingcaicompass.match.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 把仓库内保存的 NovaAPI {@code /nova/sports-lottery/all-list} 真实抓包转成赛程条目。
 *
 * <p>抓包来自 2026-10-21 的数据源验证（{@code tools/novaapi-probe}），当日 13 场，
 * 是校验「赛程字段映射是否正确」的唯一真实样本，避免消耗额度。</p>
 *
 * <p>结构约定：{@code {code, message, data:[{matchMain, matchOddsList, matchPoolList}]}}，
 * {@code matchMain} 内含比赛基础信息。两个容易出错的点已在此显式固化：</p>
 * <ul>
 *   <li>{@code matchDate} 是<b>竞彩业务日</b>，{@code matchStartDate} 才是<b>开赛自然日</b>。
 *       当日第 4 场业务日为 10-21，开赛却在 10-22 00:45；用前者拼开赛时间会整体错 24 小时。</li>
 *   <li>{@code matchStatus} 实测取值 {@code Closed}，含义是<b>销售截止</b>而不是比赛结束，
 *       且接口会带回已完赛的比赛，因此绝不能映射为 {@code FINISHED}。</li>
 * </ul>
 */
final class NovaApiScheduleFixture {

    static final String SCHEDULE_2026_10_21 = "sporttery/novaapi-schedule-2026-10-21.json";

    /** NovaAPI 返回的时间戳均按北京时间解释（响应不带时区偏移）。 */
    static final ZoneId API_ZONE = ZoneId.of("Asia/Shanghai");

    private static final DateTimeFormatter MATCH_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private NovaApiScheduleFixture() {
    }

    /** 单场赛程条目，字段与 {@code matchMain} 一一对应。 */
    record Match(
            long matchId,
            String matchNumStr,
            /** 竞彩业务日（决定 lottery_date 的取值）。 */
            LocalDate matchDate,
            /** 开赛自然日（与 matchTime 组合出真实开赛时间）。 */
            LocalDate matchStartDate,
            LocalTime matchTime,
            /** 销售状态：Selling(销售中) / SoldOut(已售罄) / Closed(已关闭)，不是比赛状态。 */
            String matchStatus,
            /** 销售状态码：0(未开售) / 1(开售中) / 2(已停售)。接口返回字符串，如 {@code "2"}。 */
            String sellStatus,
            String leagueName,
            String homeTeamName,
            String awayTeamName
    ) {

        /**
         * 真实开赛时间 = {@code matchStartDate} + {@code matchTime}，按北京时间解释。
         *
         * <p>这是本夹具存在的核心目的：让所有消费方只有一处拼接实现，避免有人误用
         * {@code matchDate} 造成整体偏移 24 小时。</p>
         */
        OffsetDateTime kickoff() {
            return matchStartDate.atTime(matchTime).atZone(API_ZONE).toOffsetDateTime();
        }

        /** 业务日与自然日不一致，说明这场比赛跨零点了。 */
        boolean crossesMidnight() {
            return !matchDate.equals(matchStartDate);
        }
    }

    /** 加载整份赛程抓包，返回按响应原始顺序排列的条目。 */
    static List<Match> load(String resourceName) {
        JsonNode root = readTree(resourceName);
        requireSuccessCode(root);
        JsonNode data = root.path("data");
        if (!data.isArray()) {
            throw new IllegalStateException("schedule data must be an array: " + resourceName);
        }
        List<Match> matches = new ArrayList<>();
        for (JsonNode item : data) {
            matches.add(toMatch(item.path("matchMain")));
        }
        return matches;
    }

    static JsonNode readTree(String resourceName) {
        try (InputStream stream = NovaApiScheduleFixture.class.getClassLoader()
                .getResourceAsStream(resourceName)) {
            if (stream == null) {
                throw new IllegalStateException("fixture not found on classpath: " + resourceName);
            }
            return MAPPER.readTree(stream);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 校验 {@code code == 200}。
     *
     * <p>NovaAPI 的业务错误也返回 HTTP 200，只看状态码会把「鉴权失败」当成「当天没有比赛」，
     * 表现为静默零场次。因此任何解析入口都必须先过这一关。</p>
     */
    static void requireSuccessCode(JsonNode root) {
        int code = root.path("code").asInt(-1);
        if (code != 200) {
            throw new IllegalStateException(
                    "NovaAPI non-success code=" + code + " message=" + root.path("message").asText(""));
        }
    }

    private static Match toMatch(JsonNode main) {
        return new Match(
                main.path("matchId").asLong(),
                main.path("matchNumStr").asText(),
                LocalDate.parse(main.path("matchDate").asText()),
                LocalDate.parse(main.path("matchStartDate").asText()),
                LocalTime.parse(main.path("matchTime").asText(), MATCH_TIME_FORMAT),
                main.path("matchStatus").asText(),
                main.path("sellStatus").asText(),
                main.path("leagueName").asText(),
                main.path("homeTeamName").asText(),
                main.path("awayTeamName").asText());
    }
}
