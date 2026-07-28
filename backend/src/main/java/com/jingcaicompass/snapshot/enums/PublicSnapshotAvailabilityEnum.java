package com.jingcaicompass.snapshot.enums;

/** 公开预测当前版本的可验证快照可用状态。 */
public enum PublicSnapshotAvailabilityEnum {
    /** 已找到包含当前预测且通过存储校验的已发布快照。 */
    AVAILABLE,
    /** 没有可公开关联到当前预测的已验证快照。 */
    UNAVAILABLE
}
