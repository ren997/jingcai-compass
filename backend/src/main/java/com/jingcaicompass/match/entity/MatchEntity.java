package com.jingcaicompass.match.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Data;

/** 内部比赛实体，以体彩编号与竞彩日期锚定。 */
@Data
@TableName("matches")
public class MatchEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 体彩官方比赛编号 */
    private String lotteryMatchNo;

    /** 竞彩业务日期（Asia/Shanghai） */
    private LocalDate lotteryDate;

    /**
     * 供应商侧比赛 ID。
     *
     * <p>单源后这是 upsert 的权威键：NovaAPI 的赛程、赔率、赛果共用同一个
     * {@code matchId}，因此它能直接贯通三段而无需任何映射表。
     * 可空是为了兼容历史数据与手工造数据的测试，非空时由部分唯一索引保证唯一。</p>
     */
    private String externalMatchId;

    /** 标准联赛 ID，标准化前可为空 */
    private Long leagueId;

    /** 标准主队 ID，标准化前可为空 */
    private Long homeTeamId;

    /** 标准客队 ID，标准化前可为空 */
    private Long awayTeamId;

    /** 联赛展示名 */
    private String leagueName;

    /** 主队展示名 */
    private String homeTeamName;

    /** 客队展示名 */
    private String awayTeamName;

    /** 开赛时间 */
    private Instant kickoffTime;

    /**
     * 比赛状态
     *
     * @see MatchStatusEnum#DESC
     */
    private MatchStatusEnum matchStatus;

    /** 当前权威赛果投影的主队比分；历史事实以 MatchResultFact 为准。 */
    private Integer homeScore;

    /** 当前权威赛果投影的客队比分；历史事实以 MatchResultFact 为准。 */
    private Integer awayScore;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
