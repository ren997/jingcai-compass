package com.jingcaicompass.data.service;

/** 同步运行与持久化原始载荷的幂等关联写入。 */
public interface DataSyncRunPayloadLinkService {

    /** 将本次运行与已保存载荷精确关联。 */
    void link(Long syncRunId, Long rawDataPayloadId);
}
