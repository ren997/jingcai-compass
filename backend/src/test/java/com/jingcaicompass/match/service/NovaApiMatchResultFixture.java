package com.jingcaicompass.match.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 把仓库内保存的 NovaAPI {@code /nova/text/match-results} 真实抓包转成赛果条目。
 *
 * <p>抓包来自 2026-10-21 的数据源验证（{@code tools/novaapi-probe}），
 * 查询区间 {@code startDate=2026-10-20&endDate=2026-10-21}，返回 21 场。</p>
 *
 * <p>结构约定：{@code {code, message, data:{startDate, endDate, results:[...]}}}。
 * 三个容易出错的点已在此显式固化：</p>
 * <ul>
 *   <li>{@code sectionsNo999} 是<b>竞彩官方 90 分钟开奖比分</b>，与产品结算口径同源同义，
 *       是唯一可用的比分来源；{@code sectionsNo1} 是半场比分。</li>
 *   <li>同一场比赛有<b>两套队名</b>：{@code homeTeam} 是短名（如「大宫松鼠」）、
 *       {@code allHomeTeam} 是全名（如「大宫松鼠RB」）。赛程接口的 {@code homeTeamName}
 *       对应的是<b>全名</b>。用短名会静默改写 {@code matches} 里已写入的队名。</li>
 *   <li>{@code matchId} 与赛程接口同值，可据此关联，不需要任何映射表。</li>
 * </ul>
 */
final class NovaApiMatchResultFixture {

    static final String RESULTS_2026_10_20_TO_21 = "sporttery/novaapi-match-results-2026-10-20-to-21.json";

    private static final String FULL_TIME_SCORE = "sectionsNo999";
    private static final String HALF_TIME_SCORE = "sectionsNo1";

    private NovaApiMatchResultFixture() {
    }

    /** 单场赛果条目。 */
    record Result(
            long matchId,
            String matchNumStr,
            LocalDate matchDate,
            /** 短名主队（≠ 赛程的 homeTeamName）。 */
            String homeTeam,
            String awayTeam,
            /** 全名主队（= 赛程的 homeTeamName）。 */
            String allHomeTeam,
            String allAwayTeam,
            /** 半场比分原文，形如 {@code 0:0}。 */
            String halfTimeScore,
            /** 全场 90 分钟比分原文，形如 {@code 0:3}。 */
            String fullTimeScore,
            String matchResultStatus,
            String poolStatus
    ) {

        /** 90 分钟主队进球数。 */
        int homeScore() {
            return parseScore(fullTimeScore)[0];
        }

        /** 90 分钟客队进球数。 */
        int awayScore() {
            return parseScore(fullTimeScore)[1];
        }

        /** 比分文本是否为合法 {@code 数字:数字}。 */
        static boolean isScoreText(String text) {
            return text != null && text.matches("\\d+:\\d+");
        }

        private static int[] parseScore(String text) {
            if (!isScoreText(text)) {
                throw new IllegalStateException("invalid score text: " + text);
            }
            String[] parts = text.split(":");
            return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
        }
    }

    /** 加载整份赛果抓包，返回按响应原始顺序排列的条目。 */
    static List<Result> load(String resourceName) {
        JsonNode root = NovaApiScheduleFixture.readTree(resourceName);
        NovaApiScheduleFixture.requireSuccessCode(root);
        JsonNode results = root.path("data").path("results");
        if (!results.isArray()) {
            throw new IllegalStateException("results must be an array: " + resourceName);
        }
        List<Result> items = new ArrayList<>();
        for (JsonNode item : results) {
            items.add(toResult(item));
        }
        return items;
    }

    private static Result toResult(JsonNode node) {
        return new Result(
                node.path("matchId").asLong(),
                node.path("matchNumStr").asText(),
                LocalDate.parse(node.path("matchDate").asText()),
                node.path("homeTeam").asText(),
                node.path("awayTeam").asText(),
                node.path("allHomeTeam").asText(),
                node.path("allAwayTeam").asText(),
                node.path(HALF_TIME_SCORE).asText(),
                node.path(FULL_TIME_SCORE).asText(),
                node.path("matchResultStatus").asText(),
                node.path("poolStatus").asText());
    }
}
