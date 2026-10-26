package com.jingcaicompass.backtest.service;

import com.jingcaicompass.backtest.dto.BacktestParameterSetDto;
import com.jingcaicompass.odds.client.AsianModelProperties;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 参数组 ↔ 引擎配置的换算。
 *
 * <p>回测要「同一批场次跑多组阈值」，而 {@code AsianDecisionEngine} 构造时就绑定了一份
 * {@link AsianModelProperties}。所以一个参数组 = 一个引擎实例，不能在判定过程中换阈值：
 * 换了之后同一场的两条样本会共用同一个 {@code decision_at}，唯一键撞车，后写的会被静默丢弃
 * （{@code ON CONFLICT DO NOTHING}），表现是「少了一组对比结果」而不是报错。</p>
 *
 * <p>JSONB 读回来的数字可能是 {@code BigDecimal}（Jackson 十进制）也可能是 {@code Double}
 * （JDBC 驱动按 double 返回），这里统一走 {@code new BigDecimal(String.valueOf(v))}：
 * 直接强转会在换驱动版本时抛 {@code ClassCastException}，而这条路径只在「续跑已有 run」时触发，
 * 于是缺陷会藏到第二次跑同一个 run 才暴露。</p>
 */
public final class BacktestParameterSets {

    /** 不传参数组时使用的那一组的 key。前端固定首列也用它。 */
    public static final String BASELINE_GROUP_KEY = "baseline";

    private BacktestParameterSets() {
    }

    /**
     * 把入参参数组换算成「key + 模型配置」。
     *
     * @param requests 入参；为空时返回一组基线
     * @param base     基线阈值（来自 {@code app.asian-model}）
     */
    public static List<ParamGroup> fromRequests(List<BacktestParameterSetDto> requests, AsianModelProperties base) {
        if (requests == null || requests.isEmpty()) {
            return List.of(new ParamGroup(BASELINE_GROUP_KEY, base));
        }
        List<ParamGroup> groups = new ArrayList<>(requests.size());
        Set<String> seen = new LinkedHashSet<>();
        for (BacktestParameterSetDto request : requests) {
            String groupKey = request.groupKey() == null ? "" : request.groupKey().trim();
            if (groupKey.isEmpty()) {
                throw new IllegalArgumentException("参数组 groupKey 不能为空");
            }
            if (!seen.add(groupKey)) {
                // 同一 run 内重复 key 会让 backtest_metrics 的唯一键 (run_id, group_key, scope) 冲突，
                // 而 INSERT 一旦走 ON CONFLICT 就会把第二组的指标静默覆盖。
                throw new IllegalArgumentException("参数组 groupKey 重复：" + groupKey);
            }
            groups.add(new ParamGroup(
                    groupKey,
                    base.withConfidenceThresholds(
                            request.highConfidenceMargin(),
                            request.mediumConfidenceMargin(),
                            request.highDirectionSignal(),
                            request.mediumDirectionSignal()
                    )
            ));
        }
        return List.copyOf(groups);
    }

    /**
     * 从已落库的 {@code parameter_sets} 还原参数组（续跑用）。
     *
     * <p>读不出的字段直接抛错而不是回退默认值：回退会让「续跑跑的是另一组参数」这件事
     * 完全无声，而这正是 run 定义列不可改所要防止的那类错误。</p>
     */
    public static List<ParamGroup> fromStored(List<Map<String, Object>> stored, AsianModelProperties base) {
        if (stored == null || stored.isEmpty()) {
            return List.of(new ParamGroup(BASELINE_GROUP_KEY, base));
        }
        List<ParamGroup> groups = new ArrayList<>(stored.size());
        for (Map<String, Object> raw : stored) {
            String groupKey = String.valueOf(raw.get("groupKey"));
            groups.add(new ParamGroup(
                    groupKey,
                    base.withConfidenceThresholds(
                            decimal(raw, "highConfidenceMargin"),
                            decimal(raw, "mediumConfidenceMargin"),
                            decimal(raw, "highDirectionSignal"),
                            decimal(raw, "mediumDirectionSignal")
                    )
            ));
        }
        return List.copyOf(groups);
    }

    /** 参数组序列化成 JSONB 列的内容。 */
    public static List<Map<String, Object>> toStored(List<ParamGroup> groups) {
        List<Map<String, Object>> stored = new ArrayList<>(groups.size());
        for (ParamGroup group : groups) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("groupKey", group.groupKey());
            row.put("highConfidenceMargin", group.model().highConfidenceMargin());
            row.put("mediumConfidenceMargin", group.model().mediumConfidenceMargin());
            row.put("highDirectionSignal", group.model().highDirectionSignal());
            row.put("mediumDirectionSignal", group.model().mediumDirectionSignal());
            stored.add(row);
        }
        return List.copyOf(stored);
    }

    private static BigDecimal decimal(Map<String, Object> raw, String field) {
        Object value = raw.get(field);
        if (value == null) {
            throw new IllegalStateException("回测定义的参数组缺少字段 " + field + "：" + raw);
        }
        return new BigDecimal(String.valueOf(value));
    }

    /**
     * 一个参数组及其对应的模型配置。
     *
     * @param groupKey 结论表里的分组标识
     * @param model    该组的阈值；其余字段与基线一致（不同组的差别只在阈值）
     */
    public record ParamGroup(String groupKey, AsianModelProperties model) {
    }
}
