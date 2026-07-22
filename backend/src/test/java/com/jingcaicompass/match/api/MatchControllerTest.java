package com.jingcaicompass.match.api;

import com.jingcaicompass.match.api.vo.MatchSummaryVo;
import com.jingcaicompass.match.application.MatchQueryService;
import com.jingcaicompass.match.domain.MatchStatus;
import com.jingcaicompass.system.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MatchController.class)
@Import(SecurityConfig.class)
class MatchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MatchQueryService matchQueryService;

    @Test
    void exposesPublicDailyMatchList() throws Exception {
        LocalDate lotteryDate = LocalDate.of(2026, 7, 22);
        when(matchQueryService.findDailyMatches(lotteryDate)).thenReturn(List.of(
                new MatchSummaryVo(
                        "stub-2026-07-22-001",
                        lotteryDate,
                        "周三001",
                        "英超",
                        "曼彻斯特城",
                        "阿森纳",
                        OffsetDateTime.parse("2026-07-22T19:30:00+08:00"),
                        -1,
                        MatchStatus.SCHEDULED,
                        "STUB"
                )
        ));

        mockMvc.perform(get("/api/public/matches").param("lotteryDate", "2026-07-22"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].lotteryMatchNo").value("周三001"))
                .andExpect(jsonPath("$[0].officialHandicap").value(-1))
                .andExpect(jsonPath("$[0].dataSource").value("STUB"));
    }
}
