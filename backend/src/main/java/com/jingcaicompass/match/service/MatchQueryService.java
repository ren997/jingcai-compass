package com.jingcaicompass.match.service;

import com.jingcaicompass.match.vo.MatchSummaryVo;

import java.time.LocalDate;
import java.util.List;

public interface MatchQueryService {

    List<MatchSummaryVo> findDailyMatches(LocalDate lotteryDate);

    LocalDate currentLotteryDate();
}
