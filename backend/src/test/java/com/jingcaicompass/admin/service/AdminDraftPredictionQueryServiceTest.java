package com.jingcaicompass.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jingcaicompass.admin.dto.AdminDraftPredictionListQueryDto;
import com.jingcaicompass.admin.mapper.AdminDraftPredictionCriteria;
import com.jingcaicompass.admin.mapper.AdminDraftPredictionMapper;
import com.jingcaicompass.admin.mapper.AdminDraftPredictionRow;
import com.jingcaicompass.prediction.enums.ConfidenceLevelEnum;
import com.jingcaicompass.prediction.enums.HandicapPickEnum;
import com.jingcaicompass.prediction.enums.AsianHandicapPickEnum;
import com.jingcaicompass.prediction.enums.TotalGoalsPickEnum;
import com.jingcaicompass.odds.enums.OddsSnapshotTypeEnum;
import com.jingcaicompass.system.config.properties.PaginationProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class AdminDraftPredictionQueryServiceTest {

    private final AdminDraftPredictionMapper mapper = Mockito.mock(AdminDraftPredictionMapper.class);
    private final AdminDraftPredictionQueryService service = new AdminDraftPredictionQueryServiceImpl(
            mapper, new PaginationProperties(50)
    );

    @Test
    void normalizesFiltersCapsPaginationAndReturnsReviewFields() {
        when(mapper.selectDraftPredictions(any())).thenReturn(List.of(row()));
        when(mapper.countDraftPredictions(any())).thenReturn(1L);

        var result = service.list(new AdminDraftPredictionListQueryDto(
                LocalDate.of(2026, 8, 11), " baseline-v1 ", 0, 999
        ));

        ArgumentCaptor<AdminDraftPredictionCriteria> criteria = ArgumentCaptor.forClass(AdminDraftPredictionCriteria.class);
        verify(mapper).selectDraftPredictions(criteria.capture());
        assertThat(criteria.getValue()).isEqualTo(new AdminDraftPredictionCriteria(
                LocalDate.of(2026, 8, 11), "baseline-v1", 50, 0
        ));
        assertThat(result).satisfies(page -> {
            assertThat(page.pageNo()).isEqualTo(1);
            assertThat(page.pageSize()).isEqualTo(50);
            assertThat(page.total()).isEqualTo(1);
            assertThat(page.records()).singleElement().satisfies(item -> {
                assertThat(item.generationBatchId()).isEqualTo("batch-20260811");
                assertThat(item.match().lotteryMatchNo()).isEqualTo("周一001");
                assertThat(item.homeWinProb()).isEqualByComparingTo("0.4600");
                assertThat(item.asianHandicapPick()).isEqualTo(AsianHandicapPickEnum.HOME_COVER);
                assertThat(item.totalGoalsPick()).isEqualTo(TotalGoalsPickEnum.OVER);
                assertThat(item.asianMarket().bookmakerCode()).isEqualTo("BOOK_A");
            });
        });
    }

    private AdminDraftPredictionRow row() {
        AdminDraftPredictionRow row = new AdminDraftPredictionRow();
        row.setPredictionId(101L);
        row.setModelVersion("baseline-v1");
        row.setFeatureVersion("feature-v1");
        row.setGenerationBatchId("batch-20260811");
        row.setGenerationBatchHash("a".repeat(64));
        row.setPredictionVersion(1);
        row.setHomeWinProb(new BigDecimal("0.4600"));
        row.setDrawProb(new BigDecimal("0.2800"));
        row.setAwayWinProb(new BigDecimal("0.2600"));
        row.setHandicapPick(HandicapPickEnum.HOME_WIN);
        row.setExpectedTotalGoals(new BigDecimal("2.50"));
        row.setAsianOddsSnapshotId(501L);
        row.setAsianHandicapPick(AsianHandicapPickEnum.HOME_COVER);
        row.setTotalGoalsPick(TotalGoalsPickEnum.OVER);
        row.setAsianProviderCode("ASIAN_TEST");
        row.setAsianBookmakerCode("BOOK_A");
        row.setAsianHandicapLine(new BigDecimal("-0.5"));
        row.setAsianHomeOdds(new BigDecimal("1.80"));
        row.setAsianAwayOdds(new BigDecimal("2.05"));
        row.setAsianTotalLine(new BigDecimal("2.50"));
        row.setAsianOverOdds(new BigDecimal("1.85"));
        row.setAsianUnderOdds(new BigDecimal("2.00"));
        row.setAsianSnapshotType(OddsSnapshotTypeEnum.PRE_KICKOFF);
        row.setAsianCapturedAt(Instant.parse("2026-08-11T00:55:00Z"));
        row.setConfidenceLevel(ConfidenceLevelEnum.MEDIUM);
        row.setAnalysisSummary("可发布前复核摘要");
        row.setGeneratedAt(Instant.parse("2026-08-11T01:00:00Z"));
        row.setMatchId(27L);
        row.setLotteryDate(LocalDate.of(2026, 8, 11));
        row.setLotteryMatchNo("周一001");
        row.setLeagueName("欧冠");
        row.setHomeTeamName("主队");
        row.setAwayTeamName("客队");
        row.setKickoffTime(Instant.parse("2026-08-11T12:00:00Z"));
        return row;
    }
}
