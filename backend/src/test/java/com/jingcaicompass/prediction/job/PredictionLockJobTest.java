package com.jingcaicompass.prediction.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jingcaicompass.prediction.dto.PredictionLockResultDto;
import com.jingcaicompass.prediction.service.PredictionLockService;
import com.jingcaicompass.system.observability.JobMetrics;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

class PredictionLockJobTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(PredictionLockService.class, () -> mock(PredictionLockService.class))
            .withBean(JobMetrics.class, JobMetrics::noop)
            .withUserConfiguration(PredictionLockJob.class);

    @Test
    void createsJobOnlyWhenGlobalAndPredictionSwitchesAreEnabled() {
        contextRunner
                .withPropertyValues(
                        "app.tasks.enabled=true",
                        "app.tasks.prediction-lock.enabled=true",
                        "app.tasks.prediction-lock.fixed-delay=30s",
                        "app.tasks.prediction-lock.initial-delay=15s",
                        "app.tasks.prediction-lock.batch-size=100"
                )
                .run(context -> assertThat(context).hasSingleBean(PredictionLockJob.class));
    }

    @Test
    void globalSwitchDisablesPredictionLockJob() {
        contextRunner
                .withPropertyValues(
                        "app.tasks.enabled=false",
                        "app.tasks.prediction-lock.enabled=true"
                )
                .run(context -> assertThat(context).doesNotHaveBean(PredictionLockJob.class));
    }

    @Test
    void predictionLockSwitchDefaultsToDisabled() {
        contextRunner
                .withPropertyValues("app.tasks.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(PredictionLockJob.class));
    }

    @Test
    void executesConfiguredBatchSize() {
        PredictionLockService service = mock(PredictionLockService.class);
        when(service.lockDuePredictions(7))
                .thenReturn(new PredictionLockResultDto(0, 0, List.of(), List.of(), 1));
        PredictionLockJob job = new PredictionLockJob(service, JobMetrics.noop());
        ReflectionTestUtils.setField(job, "batchSize", 7);

        job.lockDuePredictions();

        verify(service).lockDuePredictions(7);
    }
}
