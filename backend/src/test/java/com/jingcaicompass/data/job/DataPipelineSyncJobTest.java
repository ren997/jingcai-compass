package com.jingcaicompass.data.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.jingcaicompass.data.service.DataPipelineService;
import com.jingcaicompass.system.observability.JobMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DataPipelineSyncJobTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(DataPipelineService.class, () -> mock(DataPipelineService.class))
            .withBean(JobMetrics.class, () -> new JobMetrics(new SimpleMeterRegistry()))
            .withUserConfiguration(DataPipelineSyncJob.class);

    @Test
    void createsJobOnlyWhenGlobalAndPipelineSwitchesAreEnabled() {
        contextRunner
                .withPropertyValues(
                        "app.tasks.enabled=true",
                        "app.tasks.data-pipeline.enabled=true",
                        "app.tasks.data-pipeline.fixed-delay=20m",
                        "app.tasks.data-pipeline.initial-delay=45s"
                )
                .run(context -> assertThat(context).hasSingleBean(DataPipelineSyncJob.class));
    }

    @Test
    void globalSwitchDisablesPipelineJob() {
        contextRunner
                .withPropertyValues(
                        "app.tasks.enabled=false",
                        "app.tasks.data-pipeline.enabled=true"
                )
                .run(context -> assertThat(context).doesNotHaveBean(DataPipelineSyncJob.class));
    }

    @Test
    void pipelineSwitchDefaultsToDisabled() {
        contextRunner
                .withPropertyValues("app.tasks.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(DataPipelineSyncJob.class));
    }
}
