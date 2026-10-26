package com.jingcaicompass.backtest.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.jingcaicompass.prediction.enums.BaselinePredictionSkipReasonEnum;
import com.jingcaicompass.prediction.service.AsianDecision;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;

/**
 * 一场回测样本最终停在判定链的哪一步。
 *
 * <p>与 {@code skip_reason} 不是重复信息：{@code skip_reason} 是<b>对外归因</b>（也是生产跳过的同一套枚举），
 * 这里是<b>判定链位置</b>。两者的粒度差异全在
 * {@link BaselinePredictionSkipReasonEnum#INCONSISTENT_ASIAN_MARKETS}：一个枚举值背后有两个出口
 * （反解不可行 / 残差或邻线超限），用 stage 才能分开统计，否则「参数不可行」与「市场打架」
 * 会被混成一个数字，调参时完全看不出该动哪个阈值。</p>
 *
 * <p><b>为什么在这里推导，而不是让引擎多返一个字段</b>：{@link AsianDecision} 是生产与回测共用的
 * 契约，为回测的展示需要加字段，等于让线上路径承担一个纯展示概念；而这里推导所依赖的量
 * （{@code skipReason} + {@code solution.feasible()}）都在返回值里，推导是精确的、可单测的。
 * 一旦引擎新增出口而此处没有对应分支，{@link #of} 会在编译期或运行期直接报错，不会静默错分。</p>
 */
@Getter
public enum BacktestDecisionStageEnum {
    NOT_SCHEDULED("NOT_SCHEDULED", "非可生成状态"),
    KICKOFF_PASSED("KICKOFF_PASSED", "已开赛"),
    MISSING_ROWS("MISSING_ROWS", "该时点没有任何可交易让球行"),
    INVALID_ROWS("INVALID_ROWS", "有行但两侧水位或采集时刻不可用"),
    INSUFFICIENT_HANDICAP("INSUFFICIENT_HANDICAP", "让球报价庄家不足，无法形成共识主盘"),
    NO_TOTALS_CONSTRAINT("NO_TOTALS_CONSTRAINT", "缺大小球主盘，λ 反解只有一条方程"),
    INFEASIBLE("INFEASIBLE", "两条约束无解（反解不可行）"),
    INCONSISTENT("INCONSISTENT", "残差或邻线回投超限，两个市场定价不自洽"),
    NO_FROZEN_ROW("NO_FROZEN_ROW", "共识线上没有可冻结的真实快照行"),
    FROZEN_TOTALS_LINE_MISMATCH("FROZEN_TOTALS_LINE_MISMATCH", "冻结行的大小球线不是共识主盘线"),
    BOTH_MARKETS_REJECTED("BOTH_MARKETS_REJECTED", "走到置信度/分歧阶段但两个市场都没方向"),
    DECIDED("DECIDED", "至少一个市场有方向");

    public static final String DESC =
            "判定链停点: NOT_SCHEDULED/KICKOFF_PASSED/MISSING_ROWS/INVALID_ROWS/"
                    + "INSUFFICIENT_HANDICAP/NO_TOTALS_CONSTRAINT/INFEASIBLE/INCONSISTENT/"
                    + "NO_FROZEN_ROW/FROZEN_TOTALS_LINE_MISMATCH/BOTH_MARKETS_REJECTED/DECIDED";

    private static final Map<String, BacktestDecisionStageEnum> CODE_MAP = Stream.of(values())
            .collect(Collectors.toMap(BacktestDecisionStageEnum::getCode, Function.identity()));

    @EnumValue
    @JsonValue
    private final String code;

    private final String desc;

    BacktestDecisionStageEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static BacktestDecisionStageEnum fromCode(String code) {
        return CODE_MAP.get(code);
    }

    /**
     * 由一次判定结果推导判定链停点。
     *
     * <p>{@code INCONSISTENT_ASIAN_MARKETS} 的两个出口靠 {@code solution.feasible()} 区分：
     * 不可行的那一支在反解阶段就返回了，此时 {@code solution.feasible() == false}；走到残差/邻线
     * 校验的那一支必然 {@code feasible() == true}。这是引擎现状的精确逆推，不是启发式。</p>
     *
     * @throws IllegalStateException 遇到体彩市场相关的跳过原因——那说明回测误用了非亚盘路径
     */
    public static BacktestDecisionStageEnum of(AsianDecision decision) {
        if (decision.skipReason() == null) {
            return DECIDED;
        }
        return switch (decision.skipReason()) {
            case NOT_SCHEDULED -> NOT_SCHEDULED;
            case KICKOFF_PASSED -> KICKOFF_PASSED;
            case MISSING_CONFIRMED_ASIAN_MARKET -> MISSING_ROWS;
            case INVALID_ASIAN_MARKET -> INVALID_ROWS;
            case INSUFFICIENT_ASIAN_BOOKMAKERS -> INSUFFICIENT_HANDICAP;
            case INSUFFICIENT_ASIAN_CONSTRAINTS -> NO_TOTALS_CONSTRAINT;
            case INCONSISTENT_ASIAN_MARKETS -> decision.solution() != null && decision.solution().feasible()
                    ? INCONSISTENT
                    : INFEASIBLE;
            case NO_ASIAN_SNAPSHOT_ON_CONSENSUS_LINE -> NO_FROZEN_ROW;
            case NO_ASIAN_SNAPSHOT_ON_TOTALS_CONSENSUS_LINE -> FROZEN_TOTALS_LINE_MISMATCH;
            // 两个市场都没方向：分歧超限与低置信合并到同一 stage，二者靠 skip_reason 区分。
            case MARKET_DISPERSION_TOO_HIGH, LOW_CONFIDENCE_ASIAN_MARKETS -> BOTH_MARKETS_REJECTED;
            // 体彩玩法不在亚盘回测的输入里，出现即调用方接错了链路。
            case MISSING_SPORTTERY_MARKET, INVALID_SPORTTERY_MARKET -> throw new IllegalStateException(
                    "亚盘回测不应出现体彩市场跳过原因：" + decision.skipReason());
        };
    }
}
