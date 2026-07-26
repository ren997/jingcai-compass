package com.jingcaicompass.match.service;

import com.jingcaicompass.match.dto.MatchResultSyncRequestDto;
import com.jingcaicompass.match.dto.MatchResultSyncResultDto;

/** 指定竞彩日期范围的体彩官方赛果同步。 */
public interface MatchResultSyncService {

    /** 拉取、存档并写入指定范围内的赛果事实。 */
    MatchResultSyncResultDto sync(MatchResultSyncRequestDto request);
}
