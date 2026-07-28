package com.jingcaicompass.match.service;

import com.jingcaicompass.match.dto.MatchDetailQueryDto;
import com.jingcaicompass.match.dto.MatchListQueryDto;
import com.jingcaicompass.match.vo.MatchDetailVo;
import com.jingcaicompass.match.vo.MatchListItemVo;
import com.jingcaicompass.match.vo.MatchSummaryVo;
import com.jingcaicompass.system.api.PageResult;
import java.time.LocalDate;
import java.util.List;

/** 面向公开端的持久化比赛、盘口和映射信息查询。 */
public interface MatchQueryService {

    /**
     * 查询指定竞彩业务日的比赛列表摘要。
     *
     * @param lotteryDate 竞彩业务日（Asia/Shanghai）
     */
    List<MatchSummaryVo> findDailyMatches(LocalDate lotteryDate);

    /** 按固定筛选和排序规则分页查询公开比赛。 */
    PageResult<MatchListItemVo> list(MatchListQueryDto query);

    /** 查询单场比赛基础资料、当前盘口和映射透明信息。 */
    MatchDetailVo detail(MatchDetailQueryDto query);

    /** 当前竞彩业务日（Asia/Shanghai 今日）。 */
    LocalDate currentLotteryDate();
}
