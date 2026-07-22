package com.jingcaicompass.match.application;

import com.jingcaicompass.match.api.vo.MatchSummaryVo;
import java.time.LocalDate;
import java.util.List;

public interface MatchQueryService {

    List<MatchSummaryVo> findDailyMatches(LocalDate lotteryDate);

    LocalDate currentLotteryDate();
}
