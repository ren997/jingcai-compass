package com.jingcaicompass.match.api;

import com.jingcaicompass.match.api.vo.MatchSummaryVo;
import com.jingcaicompass.match.application.MatchQueryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
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
    public List<MatchSummaryVo> listMatches(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate lotteryDate
    ) {
        LocalDate queryDate = lotteryDate == null
                ? matchQueryService.currentLotteryDate()
                : lotteryDate;
        return matchQueryService.findDailyMatches(queryDate);
    }
}
