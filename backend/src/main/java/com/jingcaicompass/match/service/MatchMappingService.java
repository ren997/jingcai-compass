package com.jingcaicompass.match.service;

import com.jingcaicompass.match.dto.MatchMapRequestDto;
import com.jingcaicompass.match.dto.MatchMapResultDto;
import com.jingcaicompass.match.entity.MatchSourceMapping;
import java.util.List;

/** 双源比赛自动映射：外部场次 → 内部 matches，高置信自动确认，其余待复核。 */
public interface MatchMappingService {

    /**
     * 解析并落库映射：已确认复用 → 时间窗打分 → AUTO/PENDING；无候选不插入。
     */
    MatchMapResultDto resolve(MatchMapRequestDto request);

    /**
     * 待复核队列：status=PENDING，按 updatedAt 倒序。
     *
     * @param providerCode 可空；非空时按供应商过滤
     */
    List<MatchSourceMapping> listPending(String providerCode);
}
