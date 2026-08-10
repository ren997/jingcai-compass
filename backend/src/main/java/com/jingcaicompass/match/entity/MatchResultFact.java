package com.jingcaicompass.match.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jingcaicompass.match.enums.MatchResultFactStatusEnum;
import com.jingcaicompass.match.enums.MatchResultFactSourceEnum;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import java.time.Instant;
import lombok.Data;

/** 一场比赛的不可变官方或受控人工赛果事实版本。 */
@Data
@TableName("match_result_facts")
public class MatchResultFact {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 内部比赛 ID。 */
    private Long matchId;

    /** 同场比赛递增的事实版本号。 */
    private Integer factVersion;

    /** 被当前版本直接替代的上一事实版本；首版为空。 */
    private Integer supersedesFactVersion;

    /** 赛果的结算资格状态。 */
    private MatchResultFactStatusEnum factStatus;

    /** 体彩返回的比赛生命周期状态。 */
    private MatchStatusEnum matchStatus;

    /** 最终主队比分，仅 FINAL 事实可填写。 */
    private Integer homeScore;

    /** 最终客队比分，仅 FINAL 事实可填写。 */
    private Integer awayScore;

    /** 权威体彩赛果原始响应 ID。 */
    private Long rawDataPayloadId;

    /** 供应商声明的赛果更新时间。 */
    private Instant providerUpdatedAt;

    /** 事实来源；MANUAL 明确表示非官方源。 */
    private MatchResultFactSourceEnum resultSource;

    /** 人工证据说明；官方事实为空。 */
    private String sourceNote;

    /** 人工补录或修正原因；官方事实为空。 */
    private String entryReason;

    /** 已认证的人工补录操作者；官方事实为空。 */
    private String enteredBy;

    /** 是否为当前权威事实；数据库只允许 true 降为 false。 */
    private Boolean isCurrent;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
}
