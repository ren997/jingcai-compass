package com.jingcaicompass.prediction.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jingcaicompass.prediction.service.PredictionAutoPublishOrchestrator;
import com.jingcaicompass.prediction.vo.PredictionAutoPublishVo;
import com.jingcaicompass.system.observability.JobMetrics;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 自动发布 Job 的开关语义与「按竞彩业务日取当天」的日期口径。 */
class PredictionAutoPublishJobTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withBean(PredictionAutoPublishOrchestrator.class, () -> mock(PredictionAutoPublishOrchestrator.class))
            .withBean(JobMetrics.class, JobMetrics::noop)
            .withUserConfiguration(PredictionAutoPublishJob.class);

    @Test
    void createsJobOnlyWhenGlobalAndAutoPublishSwitchesAreEnabled() {
        contextRunner
                .withPropertyValues(
                        "app.tasks.enabled=true",
                        "app.tasks.prediction-auto-publish.enabled=true"
                )
                .run(context -> assertThat(context).hasSingleBean(PredictionAutoPublishJob.class));
    }

    @Test
    void autoPublishSwitchDefaultsToDisabled() {
        contextRunner
                .withPropertyValues("app.tasks.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(PredictionAutoPublishJob.class));
    }

    @Test
    void globalSwitchDisablesAutoPublishJob() {
        contextRunner
                .withPropertyValues(
                        "app.tasks.enabled=false",
                        "app.tasks.prediction-auto-publish.enabled=true"
                )
                .run(context -> assertThat(context).doesNotHaveBean(PredictionAutoPublishJob.class));
    }

    @Test
    void passesShanghaiBusinessDateToOrchestrator() {
        PredictionAutoPublishOrchestrator orchestrator = mock(PredictionAutoPublishOrchestrator.class);
        PredictionAutoPublishVo result = new PredictionAutoPublishVo(
                LocalDate.now(SHANGHAI),
                1,
                false,
                0,
                0,
                0,
                0,
                0,
                0,
                PredictionAutoPublishVo.SKIP_NO_CANDIDATE,
                Map.of()
        );
        when(orchestrator.run(org.mockito.ArgumentMatchers.any())).thenReturn(result);

        new PredictionAutoPublishJob(orchestrator).autoPublishTodayPredictions();

        // 竞彩业务日必须是上海时区：实测 13 场里有 10 场开赛时间跨零点，
        // 用 UTC 或系统默认时区会让这些场次归到错误的一天
        verify(orchestrator).run(LocalDate.now(SHANGHAI));
    }
}
