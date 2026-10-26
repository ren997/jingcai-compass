package com.jingcaicompass.backtest.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jingcaicompass.backtest.enums.BacktestDecisionStageEnum;
import com.jingcaicompass.backtest.enums.BacktestResultStatusEnum;
import com.jingcaicompass.prediction.enums.AsianHandicapPickEnum;
import com.jingcaicompass.prediction.enums.BaselinePredictionSkipReasonEnum;
import com.jingcaicompass.prediction.enums.ConfidenceLevelEnum;
import com.jingcaicompass.prediction.enums.TotalGoalsPickEnum;
import com.jingcaicompass.settlement.enums.SettlementStatusEnum;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Data;

/**
 * 回测事实宽表：粒度 = 场次 × 参数组 × 决策时点。
 *
 * <p>三组列的生命周期完全不同，这个区分是整张表的骨架：</p>
 * <ol>
 *   <li><b>决策列</b>（冻结行七列 + 方向/置信度 + 诊断量）：写一次即冻结，
 *       由 {@code trg_backtest_cases_guard} 挡掉一切后续修改。允许事后改决策列，
 *       就等于允许拿赛果去修饰当初的判断——那样任何回测结果都可以是「调出来的」。</li>
 *   <li><b>结算列</b>：只允许写一次（{@code settled_at} 非空后不可再写），
 *       语义是「这行的收益已经确定」。</li>
 *   <li><b>身份列</b>（run/group/match/archive）：用于复算与去重，同样不可改。</li>
 * </ol>
 *
 * <p>{@code group_key} 进唯一键是为了断点续跑幂等：同一 run 重跑同一参数组时
 * 走 {@code ON CONFLICT DO NOTHING}，而不是插出第二份样本。</p>
 */
@Data
@TableName("backtest_cases")
public class BacktestCaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long runId;

    /** 参数组指纹（sha256 前 16 位十六进制），与 {@code backtest_runs.parameter_sets[].groupKey} 对应 */
    private String groupKey;

    private String externalMatchId;
    private Long matchId;
    private Long archiveId;

    /** 竞彩期号（如「周一001」） */
    private String lotteryMatchNo;

    private String leagueName;

    /** 决策截面时刻；DB 断言 {@code decision_at < kickoff_time} */
    private Instant decisionAt;

    private Instant kickoffTime;

    /**
     * 不生成方向的原因；为空表示至少一个市场有方向
     *
     * @see BaselinePredictionSkipReasonEnum#DESC
     */
    private BaselinePredictionSkipReasonEnum skipReason;

    /**
     * 判定链停点
     *
     * @see BacktestDecisionStageEnum#DESC
     */
    private BacktestDecisionStageEnum decisionStage;

    /** 冻结行：真实报出共识线的那一家、那一档，结算就按这行的报价计账 */
    private String frozenBookmakerCode;
    private BigDecimal frozenHandicapLine;
    private BigDecimal frozenHomeOdds;
    private BigDecimal frozenAwayOdds;
    private BigDecimal frozenTotalLine;
    private BigDecimal frozenOverOdds;
    private BigDecimal frozenUnderOdds;
    private Instant frozenCapturedAt;

    /**
     * 方向
     *
     * @see AsianHandicapPickEnum#DESC
     */
    private AsianHandicapPickEnum handicapPick;

    /**
     * 置信度
     *
     * @see ConfidenceLevelEnum#DESC
     */
    private ConfidenceLevelEnum handicapConfidence;

    /**
     * 大小球方向
     *
     * @see TotalGoalsPickEnum#DESC
     */
    private TotalGoalsPickEnum totalsPick;

    private ConfidenceLevelEnum totalsConfidence;

    /** 让球跨庄去水概率的总体标准差 */
    private BigDecimal handicapDispersion;

    /** 大小球跨庄去水概率的总体标准差 */
    private BigDecimal totalsDispersion;

    private BigDecimal lambdaHome;
    private BigDecimal lambdaAway;

    /** 模型主队赢盘概率（首边口径，与大球口径一致） */
    private BigDecimal modelCoverProb;

    /** 市场主队赢盘概率（去水中位） */
    private BigDecimal marketCoverProb;

    /** 模型 − 市场；亚盘下恒为 0，保留是为让「恒等式」这件事在数据里可见 */
    private BigDecimal edgeCover;

    private BigDecimal modelOverProb;
    private BigDecimal marketOverProb;
    private BigDecimal edgeOver;

    /** 邻线回投最大偏差；null 表示没有合格邻线（不是「校验通过」） */
    private BigDecimal neighborMaxDeviation;

    /** 主盘残差；量级 1e-16，定标 NUMERIC 会压成 0，故用双精度 */
    private Double maxResidual;

    /** 让球方向是否通过影子门槛（只记录不拦截） */
    private Boolean handicapGatePass;

    private Boolean totalsGatePass;

    /**
     * 赛果状态
     *
     * @see BacktestResultStatusEnum#DESC
     */
    private BacktestResultStatusEnum resultStatus;

    private Integer homeScore;
    private Integer awayScore;

    /**
     * 让球结算结果
     *
     * @see SettlementStatusEnum#DESC
     */
    private SettlementStatusEnum handicapStatus;

    private BigDecimal handicapProfit;

    /**
     * 大小球结算结果
     *
     * @see SettlementStatusEnum#DESC
     */
    private SettlementStatusEnum totalsStatus;

    private BigDecimal totalsProfit;

    private Instant settledAt;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
}
