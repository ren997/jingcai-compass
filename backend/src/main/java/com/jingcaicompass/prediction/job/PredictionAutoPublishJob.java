package com.jingcaicompass.prediction.job;

import com.jingcaicompass.prediction.service.PredictionAutoPublishOrchestrator;
import com.jingcaicompass.prediction.vo.PredictionAutoPublishVo;
import com.jingcaicompass.system.observability.JobMetrics;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 定时把当天的预测从 DRAFT 自动推进到 PUBLISHED。
 *
 * <p>只负责取日期、计时和记指标；收敛规则、短路与失败落库全在
 * {@link PredictionAutoPublishOrchestrator} 里，因为那些规则需要脱离调度周期单独测试。</p>
 *
 * <p>「当天」按竞彩业务日（Asia/Shanghai）取，与赛程同步、赔率同步用的是同一个日期口径。
 * 用 UTC 或系统默认时区会让跨零点的场次错一天——实测 13 场里有 10 场开赛时间跨零点。</p>
 *
 * <p><b>本类刻意不加 {@code @Component}</b>：它由
 * {@code PersistenceServicesAutoConfiguration} 以 {@code @Bean} + {@code @ConditionalOnProperty}
 * 提供，与其他七个任务保持一致。历史上这里是 {@code @Component} 配
 * {@code @ConditionalOnBean(PredictionAutoPublishOrchestrator.class)}，而编排器是配置类里的
 * {@code @Bean}——组件扫描阶段该 bean 尚未注册，条件恒为 false，于是<b>这个任务从未被创建过</b>：
 * 库里自 2026-10-23 之后没有任何新预测，首页永远显示「暂无已发布亚盘预测」，且完全没有任何
 * 报错。{@code @ConditionalOnBean} 只对自动配置类可靠，用普通组件上时必须假设它按注册顺序
 * 求值。</p>
 */
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
