package com.jingcaicompass.backtest.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jingcaicompass.backtest.enums.BacktestArchiveStatusEnum;
import com.jingcaicompass.system.infrastructure.persistence.PostgresJsonbTypeHandler;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

/**
 * 一场比赛的历史赔率原始序列归档（只追加）。
 *
 * <p>{@code payload} 是<b>裁剪后</b>的 odds-history 响应：只保留 {@code marketId} 4（让球）与
 * 5（大小球）的 option 与 points。裁剪不是优化而是必要：完整原始体实测 400KB/场，
 * 4 个月全量约 2400 场会白占近 1GB，而切片只需要这两个市场；体彩线在
 * {@code sporttery_pool_snapshots} 另有来源。</p>
 *
 * <p>{@code trimmed} 记录「这份载荷是否被裁过」，是为了让将来的切片器不去假设
 * 「载荷里一定有胜平负」——一旦有人从别处灌入未裁剪载荷，行为差异应当可解释。</p>
 *
 * <p>本表<b>没有 setter 之外的更新路径</b>：{@code trg_odds_history_archive_append_only}
 * 对 UPDATE / DELETE 一律抛错。补采靠 {@code ON CONFLICT DO NOTHING} 撞
 * {@code uk_odds_history_archive_success}，因此不需要也不允许就地改写。</p>
 */
@Data
@TableName(value = "odds_history_archive", autoResultMap = true)
public class OddsHistoryArchiveEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 来源编码（单源后恒为 NOVA_API） */
    private String providerCode;

    /** 竞彩 {@code matchId}，即 {@code matches.external_match_id} */
    private String externalMatchId;

    /** 内部比赛 ID；赛程里没有的场次为空（归档仍可保留，供将来回填后复用） */
    private Long matchId;

    /** 竞彩业务日 */
    private LocalDate lotteryDate;

    private String leagueName;
    private String homeTeamName;
    private String awayTeamName;

    /** 实际开赛时刻（{@code matchStartDate} + 开赛时间拼接，不是业务日） */
    private Instant kickoffTime;

    /**
     * 补采结论
     *
     * @see BacktestArchiveStatusEnum#DESC
     */
    private BacktestArchiveStatusEnum fetchStatus;

    private Integer httpStatus;

    /** 上游响应体里的 {@code code}（HTTP 可能仍是 200） */
    private Integer upstreamCode;

    /** 裁剪后的原始载荷 */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class, jdbcType = JdbcType.OTHER)
    private Map<String, Object> payload;

    /** 裁剪后载荷的 SHA-256 十六进制摘要 */
    private String payloadHash;

    /** 载荷中出现过的庄家数 */
    private Integer bookmakerCount;

    /** 两个市场内的变快点总数 */
    private Integer pointCount;

    /** 序列首点时刻 */
    private Instant seriesStartAt;

    /** 序列末点时刻 */
    private Instant seriesEndAt;

    /** 载荷是否已裁剪到让球与大小球两个市场 */
    private Boolean trimmed;

    private String failureMessage;

    /** 实际发起补采的时刻（不是序列首点时刻） */
    private Instant attemptedAt;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
}
