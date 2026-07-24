package com.jingcaicompass.match.service;

import com.jingcaicompass.match.vo.MatchSummaryVo;
import java.time.LocalDate;
import java.util.List;

/** 比赛列表查询：按竞彩业务日从 Provider 读取当日比赛摘要。 */
public interface MatchQueryService {

    /**
     * 查询指定竞彩业务日的比赛列表摘要。
     *
     * @param lotteryDate 竞彩业务日（Asia/Shanghai）
     */
    List<MatchSummaryVo> findDailyMatches(LocalDate lotteryDate);

    /** 当前竞彩业务日（Asia/Shanghai 今日）。 */
    LocalDate currentLotteryDate();
}
