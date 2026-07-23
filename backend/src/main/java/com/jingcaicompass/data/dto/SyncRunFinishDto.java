package com.jingcaicompass.data.dto;

/**
 * 同步运行收尾时的计数与额度快照。
 */
public record SyncRunFinishDto(
        /** 拉取到的记录数或载荷条数。 */
        int fetchedCount,
        /** 解析成功条数。 */
        int successCount,
        /** 解析失败条数。 */
        int failureCount,
        /** 本轮重试次数。 */
        int retryCount,
        /** 本轮额度消耗。 */
        int quotaCost,
        /** 错误摘要，可空。 */
        String errorMessage
) {
}
