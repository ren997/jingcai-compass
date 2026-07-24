package com.jingcaicompass.match.service;

import com.jingcaicompass.match.dto.SportteryPoolSyncRequestDto;
import com.jingcaicompass.match.dto.SportteryPoolSyncResultDto;

/**
 * 体彩比赛池同步：raw 入库后幂等写比赛并条件追加快照。
 */
public interface SportteryPoolSyncService {

    /**
     * 同步指定（或默认今日）竞彩业务日的比赛池。
     *
     * @param request 可空；businessDate 为空时取 Asia/Shanghai 今日
     */
    SportteryPoolSyncResultDto sync(SportteryPoolSyncRequestDto request);
}
