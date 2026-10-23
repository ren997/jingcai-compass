package com.jingcaicompass.match.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.match.dto.SportteryMatchResultDto;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import com.jingcaicompass.match.exception.SportteryDataAccessException;
import com.jingcaicompass.system.provider.ProviderErrorCategory;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * NovaAPI {@code /nova/text/match-results} 载荷解析：{@code {code,data:{results:[...]}}} → 赛果条目。
 *
 * <p>与 {@link NovaSchedulePayloadMapper} 同源同风格：只读 {@code sectionsNo999}，且
 * {@code code != 200} 一律整体失败而不是返回空列表——鉴权失败、额度超限都走这条通路，
 * 静默空列表会让「第二天结算」静默空转。</p>
 *
 * <h2>四条不可调换的口径</h2>
 *
 * <ol>
 *   <li><b>{@code sectionsNo999} 是唯一比分来源</b>，它是竞彩官方开奖比分，按竞彩规则即
 *       90 分钟（含伤停补时），与亚盘结算口径同源同义。本类<b>绝不</b>读取
 *       {@code /nova/tsd/soccer-events} 的 {@code et*}/{@code pen*}（含加时、点球）或
 *       {@code int*}（实时累计含加时）——用错不报错，只会让 ROI 与 Brier 长期系统性偏移。
 *       已用一场真实打过加时的杯赛复核（结论见 {@code docs/data-sources-novaapi.md}）。</li>
 *   <li><b>该接口只返回已开奖场次</b>。实测对未来日期（2026-10-25）查询返回
 *       {@code results: []}，说明未开奖的场次根本不会出现。因此「有记录」本身就意味着
 *       「已开奖」，{@code matchResultStatus} 必须为 {@code 2}；出现其它取值说明契约变了，
 *       整体失败而不是逐条跳过——逐条跳过会让那场比赛永久停在 PENDING 且无人察觉。</li>
 *   <li><b>{@code matchDate} 是开赛自然日，不是竞彩业务日</b>——与赛程接口的 {@code matchDate}
 *       <b>同名不同义</b>。实测 2026-10-21 业务日的 13 场里 10 场跨零点（赛程
 *       {@code matchStartDate=2026-10-22}），赛果返回的 {@code matchDate} 是 <b>2026-10-22</b>。
 *       因此这个字段<b>不能</b>直接作为 {@code matches.lottery_date} 的定位键：按
 *       {@code (matchDate, matchNumStr)} 去查，跨零点的 10/13 场会「查不到」而整批记失败。
 *       （不会串场——{@code matchNumStr} 带着业务日的星期前缀，10-22 的 {@code 周三004}
 *       与 10-22 业务日的 {@code 周四004} 不是同一个字符串；但相差整数周的同名场次仍会撞。）
 *       定位必须用 {@code matchId}（= {@code matches.external_match_id}），这也是单源化
 *       之后唯一可靠的关联方式。</li>
 *   <li><b>队名一律不取</b>。赛果同时提供 {@code allHomeTeam}（全称）与 {@code homeTeam}
 *       （简称），赛事投影的队名以赛程写入的全称为准；本类不碰队名，避免用简称静默
 *       改写 {@code matches} 里的全称（如「大宫松鼠RB」→「大宫松鼠」）。</li>
 * </ol>
 *
 * <h2>两个派生字段的取值理由</h2>
 *
 * <ul>
 *   <li>{@code providerUpdatedAt} 取响应信封的 {@code timestamp}（毫秒纪元）。该接口<b>没有</b>
 *       逐条的更新时间字段，而 {@code MatchResultFactWriter} 要求「改判必须晚于既有事实」，
 *       用信封时间戳既满足单调性，又让<b>同一份载荷回放时保持幂等</b>（若用 {@code now()}，
 *       回放会不断刷新时间戳，虽然内容相同仍会走 UNCHANGED，但审计里的时间会漂移）。</li>
 *   <li>{@code amended = true}。单源之后不存在「另一家源给出不同比分」这种混淆，
 *       比分变化只可能是竞彩官方改判；写入器的 {@code amended} 守卫原本防的是<b>跨源误读</b>，
 *       在单源下由 {@code raw_data_payloads} 原文 + 事实版本链承担审计。
 *       保留该守卫的另两条限制仍然生效：终态不得回退到 PENDING、官方事实不得被人工事实替代。</li>
 * </ul>
 */
@Component
public class NovaMatchResultPayloadMapper {

    private static final int SUCCESS_CODE = 200;

    /** 全场比分字段。半场是 {@code sectionsNo1}，加时/点球不在本接口。 */
    private static final String FULL_TIME_SCORE_FIELD = "sectionsNo999";

    /** 已开奖。实测 2026-10-20~22 共 31 场全部为该值。 */
    private static final String RESULT_PUBLISHED = "2";

    /** 开奖比分形如 {@code 0:3}；允许两侧空白，但必须是纯数字。 */
    private static final Pattern SCORE_PATTERN = Pattern.compile("^(\\d{1,3})\\s*:\\s*(\\d{1,3})$");

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final ObjectMapper objectMapper;

    public NovaMatchResultPayloadMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 解析赛果。
     *
     * @param rawJson   已归档的原始响应
     * @param startDate 请求区间的竞彩业务日起点；为空则不过滤
     * @param endDate   请求区间的竞彩业务日终点（含）；为空则不过滤
     */
    public List<SportteryMatchResultDto> parseItems(
            String rawJson,
            LocalDate startDate,
            LocalDate endDate
    ) {
        if (!StringUtils.hasText(rawJson)) {
            throw new SportteryDataAccessException(
                    ProviderErrorCategory.PARSE_FAILURE,
                    "NovaAPI 赛果接口返回空响应"
            );
        }
        JsonNode root = readTree(rawJson);
        int code = root.path("code").asInt(-1);
        if (code != SUCCESS_CODE) {
            throw new SportteryDataAccessException(
                    ProviderErrorCategory.UPSTREAM_FAILURE,
                    "NovaAPI 赛果接口返回失败：code=%d message=%s".formatted(
                            code, root.path("message").asText("")
                    )
            );
        }

        JsonNode data = root.path("data");
        if (data.isMissingNode() || data.isNull()) {
            return List.of();
        }
        if (!data.isObject()) {
            // 结构变了要吵闹。历史上形状分发器把未知包装静默吞成空列表，
            // 表现是「同步成功但零条赛果」，排查成本极高。
            throw new SportteryDataAccessException(
                    ProviderErrorCategory.PARSE_FAILURE,
                    "NovaAPI 赛果响应 data 应为对象，实际是 " + data.getNodeType()
            );
        }
        JsonNode results = data.path("results");
        if (results.isMissingNode() || results.isNull()) {
            return List.of();
        }
        if (!results.isArray()) {
            throw new SportteryDataAccessException(
                    ProviderErrorCategory.PARSE_FAILURE,
                    "NovaAPI 赛果响应 data.results 应为数组，实际是 " + results.getNodeType()
            );
        }

        OffsetDateTime providerUpdatedAt = resolveProviderUpdatedAt(root);
        List<SportteryMatchResultDto> items = new ArrayList<>();
        for (JsonNode node : results) {
            SportteryMatchResultDto item = toResult(node, providerUpdatedAt);
            if (!withinRange(item.lotteryDate(), startDate, endDate)) {
                continue;
            }
            items.add(item);
        }
        return List.copyOf(items);
    }

    /**
     * 信封 {@code timestamp} 是毫秒纪元；缺失时退回抓取时刻。
     *
     * <p>缺字段本身不是数据问题（比分明明都在），因此不抛错；但绝不能返回 null，
     * 否则整批会被 {@code MatchResultFactWriter} 以「providerUpdatedAt must not be null」拒绝，
     * 表现为「同步成功、赛果一条没写」。</p>
     */
    private OffsetDateTime resolveProviderUpdatedAt(JsonNode root) {
        JsonNode timestamp = root.path("timestamp");
        if (timestamp.isNumber() && timestamp.asLong() > 0) {
            return Instant.ofEpochMilli(timestamp.asLong()).atZone(SHANGHAI).toOffsetDateTime();
        }
        return Instant.now().atZone(SHANGHAI).toOffsetDateTime();
    }

    private SportteryMatchResultDto toResult(JsonNode node, OffsetDateTime providerUpdatedAt) {
        String matchId = node.path("matchId").asText(null);
        if (!StringUtils.hasText(matchId)) {
            throw new SportteryDataAccessException(
                    ProviderErrorCategory.PARSE_FAILURE,
                    "NovaAPI 赛果缺少 matchId，无法定位比赛"
            );
        }

        String matchNumStr = node.path("matchNumStr").asText(null);
        if (!StringUtils.hasText(matchNumStr)) {
            throw new SportteryDataAccessException(
                    ProviderErrorCategory.PARSE_FAILURE,
                    "NovaAPI 赛果缺少 matchNumStr：matchId=" + matchId
            );
        }

        String matchDate = node.path("matchDate").asText(null);
        if (!StringUtils.hasText(matchDate)) {
            throw new SportteryDataAccessException(
                    ProviderErrorCategory.PARSE_FAILURE,
                    "NovaAPI 赛果缺少 matchDate（开赛自然日）：matchId=" + matchId
            );
        }

        String resultStatus = node.path("matchResultStatus").asText(null);
        if (!RESULT_PUBLISHED.equals(resultStatus)) {
            throw new SportteryDataAccessException(
                    ProviderErrorCategory.PARSE_FAILURE,
                    "NovaAPI 赛果出现非开奖状态，契约已变：matchId=%s matchResultStatus=%s（期望 %s）"
                            .formatted(matchId, resultStatus, RESULT_PUBLISHED)
            );
        }

        int[] score = parseScore(node, matchId);
        return new SportteryMatchResultDto(
                matchId,
                // 原样保留供应商日期（开赛自然日），不尝试换算成竞彩业务日——
                // 换算需要赛程数据，那正是本次改造要删掉的映射层。定位靠 matchId。
                LocalDate.parse(matchDate),
                matchNumStr,
                score[0],
                score[1],
                // 该接口只返回已开奖场次，故必然是完赛态；PENDING/LOCKED 由赛程同步产生，
                // 赛果写入方是唯一有权设置 FINISHED 的地方。
                MatchStatusEnum.FINISHED,
                true,
                false,
                providerUpdatedAt
        );
    }

    private int[] parseScore(JsonNode node, String matchId) {
        String raw = node.path(FULL_TIME_SCORE_FIELD).asText(null);
        if (!StringUtils.hasText(raw)) {
            throw new SportteryDataAccessException(
                    ProviderErrorCategory.PARSE_FAILURE,
                    "NovaAPI 已开奖赛果缺少 %s：matchId=%s".formatted(FULL_TIME_SCORE_FIELD, matchId)
            );
        }
        Matcher matcher = SCORE_PATTERN.matcher(raw.trim());
        if (!matcher.matches()) {
            throw new SportteryDataAccessException(
                    ProviderErrorCategory.PARSE_FAILURE,
                    "NovaAPI 赛果比分无法解析：matchId=%s %s=%s"
                            .formatted(matchId, FULL_TIME_SCORE_FIELD, raw)
            );
        }
        return new int[] {Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))};
    }

    /**
     * 区间过滤是防御性的：服务端按<b>同一个字段</b>（{@code matchDate}，即开赛自然日）
     * 过滤，这里保持一致，一旦它开始忽略参数也能保证写库范围正确。
     */
    private boolean withinRange(LocalDate lotteryDate, LocalDate startDate, LocalDate endDate) {
        if (startDate != null && lotteryDate.isBefore(startDate)) {
            return false;
        }
        return endDate == null || !lotteryDate.isAfter(endDate);
    }

    private JsonNode readTree(String payloadJson) {
        try {
            JsonNode root = objectMapper.readTree(payloadJson);
            if (root == null || root.isMissingNode()) {
                throw new SportteryDataAccessException("NovaAPI 赛果接口返回空响应");
            }
            return root;
        } catch (SportteryDataAccessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new SportteryDataAccessException(
                    ProviderErrorCategory.PARSE_FAILURE,
                    "无法解析 NovaAPI 赛果响应",
                    exception
            );
        }
    }
}
