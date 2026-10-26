package com.jingcaicompass.backtest.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jingcaicompass.backtest.enums.BacktestRunStatusEnum;
import com.jingcaicompass.backtest.enums.BacktestStageEnum;
import com.jingcaicompass.system.infrastructure.persistence.PostgresJsonbTypeHandler;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

/**
 * 一次回测的定义、进度与版本锚点。
 *
 * <p><b>定义列一旦写入就不可改</b>（触发器 {@code guard_backtest_runs}）：范围、阈值参数组、
 * 四个版本号、操作者与创建时刻全部冻结。理由不是洁癖——「同一 run_key 的结论到底基于什么输入」
 * 必须能在事后回答；允许中途改阈值，等于让一份结果同时对应两套参数，任何人都无法复算。</p>
 *
 * <p>{@code parameter_sets} 内嵌 JSONB 而不单独建表：参数组是本次运行的输入快照、没有独立生命周期。
 * 单独建表会暗示它可以跨 run 复用，而「同一组阈值换一批场次」恰恰是不可比的。</p>
 */
@Data
@TableName(value = "backtest_runs", autoResultMap = true)
public class BacktestRunEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 对外标识（32 位十六进制 uuid），前端用它寻址而不是自增 id */
    private String runKey;

    private String runName;

    /**
     * 运行状态
     *
     * @see BacktestRunStatusEnum#DESC
     */
    private BacktestRunStatusEnum runStatus;

    /**
     * 宏观阶段
     *
     * @see BacktestStageEnum#DESC
     */
    private BacktestStageEnum runStage;

    private String operatorUsername;

    /** 预测类型；亚盘回测恒为 ASIAN（表上有 CHECK 钉死） */
    private String predictionType;

    private LocalDate dateFrom;
    private LocalDate dateTo;

    /** 联赛白名单；null 表示不筛 */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class, jdbcType = JdbcType.OTHER)
    private List<String> leagueNames;

    /** 决策截面距开赛分钟数；仅记录，不参与对比 */
    private Integer decisionLeadMinutes;

    /** 阈值参数组数组（1~8 组），每组含 groupKey 与四个阈值 */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class, jdbcType = JdbcType.OTHER)
    private List<Map<String, Object>> parameterSets;

    private String providerCode;

    /** 运行时代码版本，用于把结论归因到某次构建 */
    private String gitSha;

    private String modelVersion;
    private String selectorVersion;
    private String aggregationVersion;
    private String builderVersion;

    private Integer totalMatchCount;
    private Integer processedMatchCount;
    private Integer archiveReusedCount;
    private Integer archiveFetchedCount;
    private Integer archiveFailedCount;
    private Integer caseCount;

    /** 入队时的额度预估，用于与 {@code quotaCost} 对比发现重试放大 */
    private Integer estimateQuotaCost;

    private Integer quotaCost;

    private Boolean cancelRequested;

    private Integer resumeCount;

    /** 每 25 场更新一次，供启动自愈判断是否真卡死 */
    private Instant lastHeartbeatAt;

    private String errorCode;
    private String errorMessage;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    private Instant startedAt;
    private Instant finishedAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
