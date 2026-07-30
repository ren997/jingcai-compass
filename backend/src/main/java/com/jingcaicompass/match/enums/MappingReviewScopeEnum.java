package com.jingcaicompass.match.enums;

/** 比赛映射复核队列的时效范围。 */
public enum MappingReviewScopeEnum {
    /** 尚未开赛，允许确认候选关联。 */
    ACTIVE,
    /** 已开赛，仅保留审计和候选证据。 */
    HISTORY
}
