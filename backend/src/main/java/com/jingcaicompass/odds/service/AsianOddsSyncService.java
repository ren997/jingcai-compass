package com.jingcaicompass.odds.service;

import com.jingcaicompass.odds.dto.AsianOddsSyncRequestDto;
import com.jingcaicompass.odds.dto.AsianOddsSyncResultDto;

/** 亚盘赛前盘口同步：拉 raw、映射门禁、追加快照。 */
public interface AsianOddsSyncService {

    /** 同步指定业务日（空则上海今日）的赛前亚盘。 */
    AsianOddsSyncResultDto sync(AsianOddsSyncRequestDto request);
}
