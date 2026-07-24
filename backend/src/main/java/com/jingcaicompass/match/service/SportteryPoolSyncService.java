package com.jingcaicompass.match.service;

import com.jingcaicompass.match.dto.SportteryPoolSyncRequestDto;
import com.jingcaicompass.match.dto.SportteryPoolSyncResultDto;

/**
 * 体彩比赛池同步：raw 入库后幂等写比赛并条件追加快照。
 */
public interface SportteryPoolSyncService {

    SportteryPoolSyncResultDto sync(SportteryPoolSyncRequestDto request);
}
