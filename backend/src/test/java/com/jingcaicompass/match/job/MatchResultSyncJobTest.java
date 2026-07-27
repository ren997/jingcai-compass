package com.jingcaicompass.match.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jingcaicompass.data.dto.ProviderSyncOutcome;
import com.jingcaicompass.data.entity.DataSyncRun;
import com.jingcaicompass.data.enums.SyncStatusEnum;
import com.jingcaicompass.match.dto.MatchResultSyncRequestDto;
import com.jingcaicompass.match.dto.MatchResultSyncResultDto;
import com.jingcaicompass.match.service.MatchResultSyncService;
import com.jingcaicompass.system.config.properties.SyncTaskProperties;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 定时赛果补数始终覆盖上海当日及前六个竞彩日。 */
@ExtendWith(MockitoExtension.class)
class MatchResultSyncJobTest {

    @Mock
    private MatchResultSyncService syncService;

    @Test
    void syncsSevenDayShanghaiWindow() {
        ProviderSyncOutcome outcome = new ProviderSyncOutcome(new DataSyncRun(), null, SyncStatusEnum.SUCCESS, false);
        when(syncService.sync(org.mockito.ArgumentMatchers.any())).thenReturn(
                new MatchResultSyncResultDto(outcome, 2, 1, 3)
        );
        MatchResultSyncJob job = new MatchResultSyncJob(syncService, taskProperties(7));

        job.syncRecentResults();

        ArgumentCaptor<MatchResultSyncRequestDto> requestCaptor = ArgumentCaptor.forClass(MatchResultSyncRequestDto.class);
        verify(syncService).sync(requestCaptor.capture());
        MatchResultSyncRequestDto request = requestCaptor.getValue();
        assertThat(request.endDate()).isEqualTo(LocalDate.now(ZoneId.of("Asia/Shanghai")));
        assertThat(request.startDate()).isEqualTo(request.endDate().minusDays(6));
    }

    private static SyncTaskProperties taskProperties(int lookbackDays) {
        return new SyncTaskProperties(
                false,
                new SyncTaskProperties.SportteryPoolTaskProperties(false, Duration.ofMinutes(15), Duration.ofSeconds(30)),
                new SyncTaskProperties.MatchResultTaskProperties(
                        false,
                        Duration.ofMinutes(15),
                        Duration.ofSeconds(60),
                        lookbackDays
                ),
                new SyncTaskProperties.AsianOddsTaskProperties(false, Duration.ofMinutes(20), Duration.ofSeconds(45)),
                new SyncTaskProperties.DataPipelineTaskProperties(false, Duration.ofMinutes(20), Duration.ofSeconds(45)),
                new SyncTaskProperties.PredictionLockTaskProperties(false, Duration.ofSeconds(30), Duration.ofSeconds(15), 100),
                new SyncTaskProperties.SnapshotPublishTaskProperties(false, Duration.ofMinutes(5), Duration.ofSeconds(60)),
                new SyncTaskProperties.SettlementTaskProperties(false, Duration.ofMinutes(5), Duration.ofSeconds(75), 100)
        );
    }
}
