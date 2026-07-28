package com.jingcaicompass.match.service;

import com.jingcaicompass.match.dto.MatchDetailQueryDto;
import com.jingcaicompass.match.dto.MatchListQueryDto;
import com.jingcaicompass.match.vo.MatchDetailVo;
import com.jingcaicompass.match.vo.MatchListItemVo;
import com.jingcaicompass.match.vo.MatchSummaryVo;
import com.jingcaicompass.system.api.PageResult;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/** 无数据库配置下的公开比赛查询占位，禁止回退到实时 Provider。 */
public class UnavailableMatchQueryService implements MatchQueryService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Override
    public List<MatchSummaryVo> findDailyMatches(LocalDate lotteryDate) {
        throw unavailable();
    }

    @Override
    public PageResult<MatchListItemVo> list(MatchListQueryDto query) {
        throw unavailable();
    }

    @Override
    public MatchDetailVo detail(MatchDetailQueryDto query) {
        throw unavailable();
    }

    @Override
    public LocalDate currentLotteryDate() {
        return LocalDate.now(SHANGHAI);
    }

    private BusinessException unavailable() {
        return new BusinessException(ErrorCode.DATA_SOURCE_UNAVAILABLE, "public match query requires a database");
    }
}
