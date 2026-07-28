package com.jingcaicompass.match.enums;

/** 公开比赛详情中的映射与盘口数据可用状态。 */
public enum MatchDataAvailabilityEnum {
    /** 对应数据已可公开展示。 */
    AVAILABLE,
    /** 尚无体彩比赛池快照。 */
    NO_SPORTTERY_SNAPSHOT,
    /** 尚无亚盘快照。 */
    NO_ASIAN_ODDS_SNAPSHOT,
    /** 尚无任何供应商比赛映射。 */
    NO_SOURCE_MAPPING,
    /** 已有映射但尚未确认。 */
    MAPPING_UNCONFIRMED
}
