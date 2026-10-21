package com.jingcaicompass.match.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.match.dto.SportteryPoolSyncItemDto;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import com.jingcaicompass.match.exception.SportteryDataAccessException;
import com.jingcaicompass.system.provider.ProviderErrorCategory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * NovaAPI 竞彩赛程载荷解析：{@code {code,data:[{matchMain,matchOddsList}]}} → 同步条目。
 *
 * <p>与 {@link SportteryPoolPayloadMapper} 解析的是同一份竞彩数据的两种包装，因此
 * 产出同一个 {@link SportteryPoolSyncItemDto}，写库路径完全复用。两条结构性差异必须
 * 在这里吸收掉：</p>
 *
 * <ol>
 *   <li><b>开赛时间取 {@code matchStartDate + matchTime}</b>，不是 {@code matchDate}。
 *       {@code matchDate} 是竞彩业务日：实测 2026-10-21 的 13 场里有 10 场业务日为
 *       10-21、实际开赛在 10-22 凌晨。用业务日拼时间会让绝大多数场次早 24 小时，
 *       而开赛时间决定锁定与结算窗口——错了不报错，只会让预测窗口整体偏移。</li>
 *   <li><b>{@code matchStatus} 是销售状态，不是比赛状态</b>。接口里根本没有「比赛是否
 *       结束」的字段，真正的比赛状态只在 {@code /nova/text/live}。因此这里只映射到
 *       {@code SCHEDULED}/{@code LOCKED}，{@code FINISHED} 一律由赛果写入方设置。</li>
 * </ol>
 */
@Component
public class NovaSchedulePayloadMapper {

    private static final Logger log = LoggerFactory.getLogger(NovaSchedulePayloadMapper.class);

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final int SUCCESS_CODE = 200;
    private static final String POOL_HAD = "HAD";
    private static final String POOL_HHAD = "HHAD";

    private final ObjectMapper objectMapper;

    public NovaSchedulePayloadMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 解析指定业务日的比赛条目。
     *
     * <p>{@code code != 200} 直接抛错而不是返回空列表：空列表会被同步模板当作
     * 「当天没有比赛」正常收尾，而鉴权失败、额度超限都会走到这里。静默空列表会让
     * 自动发布空转一整天且日志无异常。</p>
     */
    public List<SportteryPoolSyncItemDto> parseItems(String payloadJson, LocalDate businessDate) {
        JsonNode root = readTree(payloadJson);
        int code = root.path("code").asInt(-1);
        if (code != SUCCESS_CODE) {
            throw new SportteryDataAccessException(
                    ProviderErrorCategory.UPSTREAM_FAILURE,
                    "NovaAPI 赛程接口返回失败：code=%d message=%s".formatted(code, root.path("message").asText(""))
            );
        }

        JsonNode data = root.path("data");
        if (data.isMissingNode() || data.isNull()) {
            return List.of();
        }
        if (!data.isArray()) {
            // 结构变了要吵闹，不能当成空数据。历史上 {code,data:{providers}} 形态
            // 曾被多形状分发器静默吞成空列表，排查成本极高。
            throw new SportteryDataAccessException(
                    ProviderErrorCategory.PARSE_FAILURE,
                    "NovaAPI 赛程响应 data 应为数组，实际是 " + data.getNodeType()
            );
        }

        List<SportteryPoolSyncItemDto> items = new ArrayList<>();
        for (JsonNode node : data) {
            JsonNode main = node.path("matchMain");
            if (main.isMissingNode() || main.isNull()) {
                continue;
            }
            // 按竞彩业务日过滤：跨零点场次的 matchStartDate 会晚一天，但 matchDate 仍是业务日。
            if (!businessDate.toString().equals(main.path("matchDate").asText())) {
                continue;
            }
            items.add(toSyncItem(main, node.path("matchOddsList")));
        }
        return List.copyOf(items);
    }

    private JsonNode readTree(String payloadJson) {
        try {
            JsonNode root = objectMapper.readTree(payloadJson);
            if (root == null || root.isMissingNode()) {
                throw new SportteryDataAccessException("NovaAPI 赛程接口返回空响应");
            }
            return root;
        } catch (SportteryDataAccessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new SportteryDataAccessException(
                    ProviderErrorCategory.PARSE_FAILURE,
                    "无法解析 NovaAPI 赛程响应",
                    exception
            );
        }
    }

    private SportteryPoolSyncItemDto toSyncItem(JsonNode main, JsonNode oddsList) {
        String sellStatus = main.path("sellStatus").asText(null);
        return new SportteryPoolSyncItemDto(
                main.path("matchId").asText(null),
                LocalDate.parse(main.path("matchDate").asText()),
                main.path("matchNumStr").asText(null),
                // 用全称而不是 leagueShort：matches.league_name 直接展示给用户，
                // 「日本职业联赛」比「日职」更不需要额外解释。
                main.path("leagueName").asText(null),
                main.path("homeTeamName").asText(null),
                main.path("awayTeamName").asText(null),
                resolveKickoff(main),
                officialHandicapOf(oddsList),
                toMatchStatus(main.path("matchStatus").asText(null)),
                sellStatus,
                oddsOf(oddsList, POOL_HAD, "homeOdds"),
                oddsOf(oddsList, POOL_HAD, "drawOdds"),
                oddsOf(oddsList, POOL_HAD, "awayOdds"),
                oddsOf(oddsList, POOL_HHAD, "homeOdds"),
                oddsOf(oddsList, POOL_HHAD, "drawOdds"),
                oddsOf(oddsList, POOL_HHAD, "awayOdds")
        );
    }

    /** 开赛时间 = 实际比赛日期 + 开赛时刻，按 Asia/Shanghai 解释。 */
    private OffsetDateTime resolveKickoff(JsonNode main) {
        String startDate = main.path("matchStartDate").asText(null);
        String matchTime = main.path("matchTime").asText(null);
        if (!StringUtils.hasText(startDate) || !StringUtils.hasText(matchTime)) {
            throw new SportteryDataAccessException(
                    ProviderErrorCategory.PARSE_FAILURE,
                    "NovaAPI 赛程缺少 matchStartDate 或 matchTime，无法确定开赛时间：matchId="
                            + main.path("matchId").asText("?")
            );
        }
        return LocalDate.parse(startDate)
                .atTime(LocalTime.parse(normalizeTime(matchTime)))
                .atZone(SHANGHAI)
                .toOffsetDateTime();
    }

    /** 接口给的是 {@code HH:mm}，补一层容错避免个别行给成 {@code H:mm}。 */
    private String normalizeTime(String raw) {
        int colon = raw.indexOf(':');
        if (colon == 1) {
            return "0" + raw;
        }
        return raw;
    }

    /**
     * 销售状态 → 比赛业务状态。
     *
     * <p>文档定义的取值全集是 {@code Selling} / {@code SoldOut} / {@code Closed}。
     * 三者都是销售层面，与比赛是否结束无关，所以一律映射到未开赛或已锁定，
     * 绝不映射 {@code FINISHED}——接口会带回已完赛的比赛，那样做会让当日所有预测被跳过。</p>
     *
     * <p>未知取值记 warn 并保守按已锁定处理：单场状态映射保守的代价远小于
     * 抛错导致整日赛程同步失败（那会让当天一条预测都生成不出来）。</p>
     */
    private MatchStatusEnum toMatchStatus(String matchStatus) {
        if (!StringUtils.hasText(matchStatus)) {
            throw new SportteryDataAccessException(
                    ProviderErrorCategory.PARSE_FAILURE,
                    "NovaAPI 赛程缺少 matchStatus"
            );
        }
        return switch (matchStatus.toLowerCase()) {
            case "selling" -> MatchStatusEnum.SCHEDULED;
            // 售罄是额度用尽，比赛仍在未开赛状态。
            case "soldout" -> MatchStatusEnum.SCHEDULED;
            // 销售截止：可能刚开赛，也可能已结束，统一按已锁定看待，
            // 真正的 FINISHED 由赛果写入方设置。
            case "closed", "lock" -> MatchStatusEnum.LOCKED;
            default -> {
                log.warn("NovaAPI 赛程出现未文档化的 matchStatus={}，按 LOCKED 处理", matchStatus);
                yield MatchStatusEnum.LOCKED;
            }
        };
    }

    /**
     * 竞彩官方让球（整数），取自 HHAD 的 {@code goalLine}（形如 {@code -1.00} / {@code +1.00}）。
     *
     * <p>只用于 {@code sporttery_pool_snapshots.official_handicap} 这一展示字段；
     * 亚盘模型用的是 {@code asian_odds_snapshots}，与这里无关。</p>
     */
    private BigDecimal officialHandicapOf(JsonNode oddsList) {
        if (oddsList == null || !oddsList.isArray()) {
            return null;
        }
        for (JsonNode odds : oddsList) {
            if (!POOL_HHAD.equalsIgnoreCase(odds.path("poolCode").asText())) {
                continue;
            }
            String raw = odds.path("goalLine").asText(null);
            if (!StringUtils.hasText(raw)) {
                return null;
            }
            try {
                // -1.00 → -1，避免把两位小数带进「整数让球」字段。
                return new BigDecimal(raw).stripTrailingZeros();
            } catch (NumberFormatException exception) {
                throw new SportteryDataAccessException(
                        ProviderErrorCategory.PARSE_FAILURE,
                        "无法解析 HHAD.goalLine=" + raw,
                        exception
                );
            }
        }
        return null;
    }

    private BigDecimal oddsOf(JsonNode oddsList, String poolCode, String field) {        if (oddsList == null || !oddsList.isArray()) {
            return null;
        }
        for (JsonNode odds : oddsList) {
            if (!poolCode.equalsIgnoreCase(odds.path("poolCode").asText())) {
                continue;
            }
            String raw = odds.path(field).asText(null);
            if (!StringUtils.hasText(raw)) {
                return null;
            }
            try {
                return new BigDecimal(raw);
            } catch (NumberFormatException exception) {
                throw new SportteryDataAccessException(
                        ProviderErrorCategory.PARSE_FAILURE,
                        "无法解析 %s.%s=%s".formatted(poolCode, field, raw),
                        exception
                );
            }
        }
        // 竞彩允许只开让球不开胜平负（实测有一场只有 HHAD），因此缺玩法不是错误。
        return null;
    }
}
