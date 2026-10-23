package com.jingcaicompass.prediction.job;

import com.jingcaicompass.prediction.service.PredictionAutoPublishOrchestrator;
import com.jingcaicompass.prediction.vo.PredictionAutoPublishVo;
import com.jingcaicompass.system.observability.JobMetrics;
import java.time.LocalDate;
import java.time.ZoneId;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时把当天的预测从 DRAFT 自动推进到 PUBLISHED。
 *
 * <p>只负责取日期、计时和记指标；收敛规则、短路与失败落库全在
 * {@link PredictionAutoPublishOrchestrator} 里，因为那些规则需要脱离调度周期单独测试。</p>
 *
 * <p>「当天」按竞彩业务日（Asia/Shanghai）取，与赛程同步、赔率同步用的是同一个日期口径。
 * 用 UTC 或系统默认时区会让跨零点的场次错一天——实测 13 场里有 10 场开赛时间跨零点。</p>
 */
@Component
@ConditionalOnBean({DataSource.class, PredictionAutoPublishOrchestrator.class})
@ConditionalOnExpression("${app.tasks.enabled:false} && ${app.tasks.prediction-auto-publish.enabled:false}")
public class PredictionAutoPublishJob {

    private static final Logger log = LoggerFactory.getLogger(PredictionAutoPublishJob.class);
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final PredictionAutoPublishOrchestrator orchestrator;
    private final JobMetrics jobMetrics;

    public PredictionAutoPublishJob(PredictionAutoPublishOrchestrator orchestrator) {
        this(orchestrator, JobMetrics.noop());
    }

    @Autowired
    public PredictionAutoPublishJob(
            PredictionAutoPublishOrchestrator orchestrator,
            JobMetrics jobMetrics
    ) {
        this.orchestrator = orchestrator;
        this.jobMetrics = jobMetrics;
    }

    @Scheduled(
            fixedDelayString = "#{@syncTaskSchedule.predictionAutoPublishFixedDelayMillis()}",
            initialDelayString = "#{@syncTaskSchedule.predictionAutoPublishInitialDelayMillis()}"
    )
    public void autoPublishTodayPredictions() {
        JobMetrics.JobExecution execution = jobMetrics.start("prediction_auto_publish");
        LocalDate today = LocalDate.now(SHANGHAI);
        try {
            // 1) 一轮「短路 → 生成 → 收敛 → 发布 → 失败落库」，全在编排器内完成
            PredictionAutoPublishVo result = orchestrator.run(today);
            String status = result.status();
            log.info(
                    "event=prediction_auto_publish_job_finished lotteryDate={} status={} skippedReason={} "
                            + "pendingMatches={} generated={} inserted={} candidates={} published={} failed={}",
                    today,
                    status,
                    result.skippedReason(),
                    result.pendingMatchCount(),
                    result.generatedCount(),
                    result.insertedCount(),
                    result.candidateCount(),
                    result.publishedCount(),
                    result.failedCount()
            );
            jobMetrics.record(execution, status);
        } catch (RuntimeException exception) {
            log.error(
                    "event=prediction_auto_publish_job_failed lotteryDate={} exceptionType={}",
                    today,
                    exception.getClass().getSimpleName()
            );
            jobMetrics.record(execution, "FAILED");
            throw exception;
        }
    }
}
