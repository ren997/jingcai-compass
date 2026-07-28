package com.jingcaicompass.match.controller;

import com.jingcaicompass.match.dto.MatchDetailQueryDto;
import com.jingcaicompass.match.dto.MatchListQueryDto;
import com.jingcaicompass.match.service.MatchQueryService;
import com.jingcaicompass.match.vo.MatchDetailVo;
import com.jingcaicompass.match.vo.MatchListItemVo;
import com.jingcaicompass.match.vo.MatchSummaryVo;
import com.jingcaicompass.system.api.ApiResponse;
import com.jingcaicompass.system.api.PageResult;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/public/matches")
public class MatchController {

    private final MatchQueryService matchQueryService;

    public MatchController(MatchQueryService matchQueryService) {
        this.matchQueryService = matchQueryService;
    }

    @GetMapping
    public ApiResponse<List<MatchSummaryVo>> listMatches(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate lotteryDate
    ) {
        LocalDate queryDate = lotteryDate == null
                ? matchQueryService.currentLotteryDate()
                : lotteryDate;
        return ApiResponse.success(matchQueryService.findDailyMatches(queryDate));
    }

    /** 公开比赛分页列表；筛选与排序均由显式 Dto 和白名单控制。 */
    @PostMapping("/list")
    public ApiResponse<PageResult<MatchListItemVo>> list(
            @RequestBody(required = false) MatchListQueryDto query
    ) {
        return ApiResponse.success(matchQueryService.list(query));
    }

    /** 返回单场比赛基础资料、当前盘口与来源映射信息。 */
    @PostMapping("/detail")
    public ApiResponse<MatchDetailVo> detail(@Valid @RequestBody MatchDetailQueryDto query) {
        return ApiResponse.success(matchQueryService.detail(query));
    }
}
