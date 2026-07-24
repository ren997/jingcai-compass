package com.jingcaicompass.match.service;

import com.jingcaicompass.match.dto.SportteryMatchDto;
import com.jingcaicompass.match.vo.MatchSummaryVo;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Service;

/** 比赛列表查询实现：委托 SportteryProvider，再映射为对外摘要 VO。 */
@Service
public class MatchQueryServiceImpl implements MatchQueryService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final SportteryProvider sportteryProvider;

    public MatchQueryServiceImpl(SportteryProvider sportteryProvider) {
        this.sportteryProvider = sportteryProvider;
    }

    @Override
    public List<MatchSummaryVo> findDailyMatches(LocalDate lotteryDate) {
        // 1) 从体彩 Provider 拉取当日比赛
        // 2) 转为对外 MatchSummaryVo（含 providerCode）
        return sportteryProvider.findDailyMatches(lotteryDate).stream()
                .map(this::toSummaryVo)
                .toList();
    }

    @Override
    public LocalDate currentLotteryDate() {
        return LocalDate.now(SHANGHAI);
    }

    private MatchSummaryVo toSummaryVo(SportteryMatchDto match) {
        return new MatchSummaryVo(
                match.matchId(),
                match.lotteryDate(),
                match.lotteryMatchNo(),
                match.leagueName(),
                match.homeTeamName(),
                match.awayTeamName(),
                match.kickoffTime(),
                match.officialHandicap(),
                match.matchStatus(),
                sportteryProvider.providerCode()
        );
    }
}
