package com.jingcaicompass.snapshot.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jingcaicompass.snapshot.dto.PredictionSnapshotResultDto;
import com.jingcaicompass.snapshot.enums.PredictionSnapshotStatusEnum;
import com.jingcaicompass.snapshot.service.PredictionSnapshotService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SnapshotPublishJobTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-07-26T01:00:00Z"), ZoneOffset.UTC);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(PredictionSnapshotService.class, () -> mock(PredictionSnapshotService.class))
            .withBean(Clock.class, () -> FIXED_CLOCK)
            .withUserConfiguration(SnapshotPublishJob.class);

    @Test
    void createsJobOnlyWhenGlobalAndSnapshotSwitchesAreEnabled() {
        contextRunner
                .withPropertyValues(
                        "app.tasks.enabled=true",
                        "app.tasks.snapshot-publish.enabled=true",
                        "app.tasks.snapshot-publish.fixed-delay=5m",
                        "app.tasks.snapshot-publish.initial-delay=60s"
                )
                .run(context -> assertThat(context).hasSingleBean(SnapshotPublishJob.class));
    }

    @Test
    void globalSwitchDisablesSnapshotJob() {
        contextRunner
                .withPropertyValues(
                        "app.tasks.enabled=false",
                        "app.tasks.snapshot-publish.enabled=true"
                )
                .run(context -> assertThat(context).doesNotHaveBean(SnapshotPublishJob.class));
    }

    @Test
    void snapshotSwitchDefaultsToDisabled() {
        contextRunner
                .withPropertyValues("app.tasks.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(SnapshotPublishJob.class));
    }

    @Test
    void publishesShanghaiBusinessDateFromInjectedClock() {
        PredictionSnapshotService service = mock(PredictionSnapshotService.class);
        LocalDate expectedDate = LocalDate.of(2026, 7, 26);
        when(service.publish(expectedDate)).thenReturn(new PredictionSnapshotResultDto(
                1L,
                expectedDate,
                1,
                PredictionSnapshotStatusEnum.PUBLISHED,
                "a".repeat(64),
                2,
                "LOCAL",
                "object.json",
                null,
                "file:///object.json",
                "application/json",
                100L,
                Instant.parse("2026-07-26T01:00:00Z"),
                null,
                false
        ));
        SnapshotPublishJob job = new SnapshotPublishJob(service, FIXED_CLOCK);

        job.publishCurrentBusinessDate();

        verify(service).publish(expectedDate);
    }
}
