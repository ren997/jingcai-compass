package com.jingcaicompass.backtest.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jingcaicompass.system.infrastructure.persistence.PostgresJsonbTypeHandler;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

/**
 * 参数组 × 市场口径的派生指标。
 *
 * <p>派生表<b>禁 UPDATE</b>（触发器 {@code trg_backtest_metrics_no_update}），重算走
 * 「按 run 删除 + 重新插入」。这不是过度设计：一旦允许就地改，「指标是算出来的」这条保证就没了，
 * 事后无法区分「算错了」与「被人改过」，而这两件事的处理方式完全不同。</p>
 *
 * <p>{@code hitRate} 与 {@code weightedHitRate} 必须同时保留：前者是纯命中率
 * （走盘不进分母），后者把半赢半输算成 .75/.25。回测对比时两者读数差异大是<b>正常</b>的——
 * 差异本身就说明这批样本里四分盘很多。</p>
 */
@Data
@TableName(value = "backtest_metrics", autoResultMap = true)
public class BacktestMetricEntity {

    /** 市场口径：整体 / 只看让球 / 只看大小球。 */
    public static final String SCOPE_ALL = "ALL";
    public static final String SCOPE_HANDICAP = "ASIAN_HANDICAP";
    public static final String SCOPE_TOTALS = "ASIAN_TOTAL_GOALS";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long runId;

    private String groupKey;

    /** {@link #SCOPE_ALL} / {@link #SCOPE_HANDICAP} / {@link #SCOPE_TOTALS} */
    private String marketScope;

    private Integer caseCount;
    private Integer decidedCount;
    private Integer skippedCount;

    /** 该口径下实际有方向的腿数（一场可能两腿都有） */
    private Integer pickCount;

    private Integer settledCount;
    private Integer pushCount;
    private Integer halfWinCount;
    private Integer halfLossCount;
    private Integer hitCount;
    private Integer missCount;

    /** 纯命中率，走盘不进分母 */
    private BigDecimal hitRate;

    /** 加权命中率：HIT 1 / HALF_WIN .75 / HALF_LOSS .25 / MISS 0 */
    private BigDecimal weightedHitRate;

    private BigDecimal profitSum;
    private BigDecimal roi;

    /** 单腿收益率 = profitSum / settledCount，与 roi（分母为全部腿）不同 */
    private BigDecimal yieldRate;

    private BigDecimal brierModel;
    private BigDecimal brierMarket;
    private BigDecimal brierDelta;
    private Integer brierSampleSize;

    /** 各跳过原因的场次数，便于一眼看出「这批场次主要死在哪一步」 */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class, jdbcType = JdbcType.OTHER)
    private Map<String, Object> skipReasonCounts;

    /** 完整统计对象快照，前端不需要二次计算 */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class, jdbcType = JdbcType.OTHER)
    private Map<String, Object> metricsJson;

    private Instant computedAt;
}
