package com.jingcaicompass.match.application;

import com.jingcaicompass.match.api.vo.MatchSummaryVo;
import com.jingcaicompass.match.application.provider.SportteryMatchDto;
import com.jingcaicompass.match.application.provider.SportteryProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Service
public class DefaultMatchQueryService implements MatchQueryService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final SportteryProvider sportteryProvider;

    public DefaultMatchQueryService(SportteryProvider sportteryProvider) {
        this.sportteryProvider = sportteryProvider;
    }

    @Override
    public List<MatchSummaryVo> findDailyMatches(LocalDate lotteryDate) {
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
