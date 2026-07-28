package com.jingcaicompass.admin.vo;

import java.util.List;

/** 后台同步运行详情及精确关联的安全载荷片段。 */
public record AdminSyncRunDetailVo(
        AdminSyncRunListItemVo run,
        List<AdminRawPayloadSnippetVo> rawPayloads,
        String rawPayloadNotice
) {
}
