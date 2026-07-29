package com.jingcaicompass.data.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.data.dto.ProviderFetchResult;
import com.jingcaicompass.data.dto.ProviderParseResult;
import com.jingcaicompass.data.dto.ProviderSyncOutcome;
import com.jingcaicompass.data.dto.RawDataPayloadSaveDto;
import com.jingcaicompass.data.dto.RawDataPayloadSaveResult;
import com.jingcaicompass.data.dto.SyncRunFinishDto;
import com.jingcaicompass.data.entity.DataSyncRun;
import com.jingcaicompass.data.entity.RawDataPayload;
import com.jingcaicompass.data.enums.ProviderDataTypeEnum;
import com.jingcaicompass.data.enums.SyncStatusEnum;
import com.jingcaicompass.system.provider.ProviderHttpException;
import com.jingcaicompass.system.observability.SensitiveDataSanitizer;
import com.jingcaicompass.system.observability.SyncMetrics;
import com.jingcaicompass.system.infrastructure.TraceIdContext;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;

/**
 * Provider 同步调用模板：运行 -> 请求 -> 原始入库 -> 解析 -> 完成。
 */
@Component
@ConditionalOnBean(DataSource.class)
public class ProviderSyncTemplate {

    private static final Logger log = LoggerFactory.getLogger(ProviderSyncTemplate.class);

    private final DataSyncRunService dataSyncRunService;
    private final RawDataPayloadService rawDataPayloadService;
    private final DataSyncRunPayloadLinkService dataSyncRunPayloadLinkService;
    private final SyncMetrics syncMetrics;
    private final SensitiveDataSanitizer sanitizer;

    public ProviderSyncTemplate(
            DataSyncRunService dataSyncRunService,
            RawDataPayloadService rawDataPayloadService,
            DataSyncRunPayloadLinkService dataSyncRunPayloadLinkService
    ) {
        this(
                dataSyncRunService,
                rawDataPayloadService,
                dataSyncRunPayloadLinkService,
                SyncMetrics.noop(),
                new SensitiveDataSanitizer(new ObjectMapper())
        );
    }

    @Autowired
    public ProviderSyncTemplate(
            DataSyncRunService dataSyncRunService,
            RawDataPayloadService rawDataPayloadService,
            DataSyncRunPayloadLinkService dataSyncRunPayloadLinkService,
            SyncMetrics syncMetrics,
            SensitiveDataSanitizer sanitizer
    ) {
        this.dataSyncRunService = dataSyncRunService;
        this.rawDataPayloadService = rawDataPayloadService;
        this.dataSyncRunPayloadLinkService = dataSyncRunPayloadLinkService;
        this.syncMetrics = syncMetrics;
        this.sanitizer = sanitizer;
    }

    /**
     * 按固定顺序执行一次 Provider 同步。
     */
    public ProviderSyncOutcome execute(
            String providerCode,
            ProviderDataTypeEnum dataType,
            ProviderPayloadFetcher fetcher,
            ProviderPayloadParser parser
    ) {
        if (!StringUtils.hasText(providerCode)) {
            throw new IllegalArgumentException("providerCode must not be blank");
        }
        if (dataType == null) {
            throw new IllegalArgumentException("dataType must not be null");
        }
        if (fetcher == null) {
            throw new IllegalArgumentException("fetcher must not be null");
        }
        if (parser == null) {
            throw new IllegalArgumentException("parser must not be null");
        }

        DataSyncRun run = dataSyncRunService.startRun(providerCode, dataType);
        String priorTraceId = MDC.get(TraceIdContext.MDC_KEY);
        String priorProviderCode = MDC.get("providerCode");
        String priorSyncRunId = MDC.get("syncRunId");
        String priorStatus = MDC.get("status");
        String priorDurationMs = MDC.get("durationMs");
        if (priorTraceId == null || priorTraceId.isBlank()) {
            MDC.put(TraceIdContext.MDC_KEY, TraceIdContext.currentOrCreate());
        }
        MDC.put("providerCode", providerCode);
        MDC.put("syncRunId", String.valueOf(run.getId()));
        MDC.put("status", SyncStatusEnum.RUNNING.getCode());
        MDC.put("durationMs", "0");
        try {
            log.info("event=sync_started dataType={}", dataType);

            ProviderFetchResult fetchResult;
            try {
            fetchResult = fetcher.fetch();
            } catch (RuntimeException exception) {
            ProviderHttpException httpFailure = findHttpException(exception);
            int retryCount = httpFailure == null ? 0 : httpFailure.retryCount();
            int quotaCost = httpFailure == null ? 0 : httpFailure.quotaCost();
            DataSyncRun finished = dataSyncRunService.finishFailed(
                    run.getId(),
                    new SyncRunFinishDto(0, 0, 0, retryCount, quotaCost, sanitize(exception.getMessage()))
            );
                log.warn("event=sync_fetch_failed dataType={} exceptionType={} error={}",
                        dataType, exception.getClass().getSimpleName(), sanitize(exception.getMessage()));
                return record(new ProviderSyncOutcome(finished, null, SyncStatusEnum.FAILED, false));
            }

            if (fetchResult == null || !StringUtils.hasText(fetchResult.payloadJson())) {
            DataSyncRun finished = dataSyncRunService.finishFailed(
                    run.getId(),
                    new SyncRunFinishDto(0, 0, 0, 0, 0, "empty provider response")
            );
                return record(new ProviderSyncOutcome(finished, null, SyncStatusEnum.FAILED, false));
            }

            Instant requestedAt = Instant.now();
            RawDataPayloadSaveResult saveResult = rawDataPayloadService.savePayload(
                new RawDataPayloadSaveDto(
                        providerCode,
                        dataType,
                        fetchResult.requestKey(),
                        fetchResult.payloadJson(),
                        fetchResult.httpStatus(),
                        fetchResult.providerUpdatedAt(),
                        requestedAt
                )
        );
            RawDataPayload payload = saveResult.payload();
        // 1) 无论是否命中去重，都将本次运行精确关联到该载荷
            dataSyncRunPayloadLinkService.link(run.getId(), payload.getId());

            ProviderParseResult parseResult;
            try {
            parseResult = parser.parse(dataType, fetchResult.requestKey(), payload);
            if (parseResult == null) {
                parseResult = ProviderParseResult.empty();
            }
            } catch (RuntimeException exception) {
            rawDataPayloadService.markParseFailed(payload.getId(), sanitize(exception.getMessage()));
            DataSyncRun finished = dataSyncRunService.finishFailed(
                    run.getId(),
                    new SyncRunFinishDto(
                            1,
                            0,
                            1,
                            fetchResult.retryCount(),
                            fetchResult.quotaCost(),
                            sanitize(exception.getMessage())
                    )
            );
                log.warn("event=sync_parse_failed dataType={} payloadId={} exceptionType={} error={}",
                        dataType, payload.getId(), exception.getClass().getSimpleName(), sanitize(exception.getMessage()));
                return record(new ProviderSyncOutcome(finished, payload, SyncStatusEnum.FAILED, saveResult.duplicate()));
            }

            int successCount = Math.max(parseResult.successCount(), 0);
            int failureCount = Math.max(parseResult.failureCount(), 0);
            int fetchedCount = Math.max(successCount + failureCount, 1);

            if (failureCount == 0) {
            rawDataPayloadService.markParseSuccess(payload.getId());
            DataSyncRun finished = dataSyncRunService.finishSuccess(
                    run.getId(),
                    new SyncRunFinishDto(
                            fetchedCount,
                            successCount,
                            0,
                            fetchResult.retryCount(),
                            fetchResult.quotaCost(),
                            null
                    )
            );
                return record(new ProviderSyncOutcome(finished, payload, SyncStatusEnum.SUCCESS, saveResult.duplicate()));
            }

            if (successCount > 0) {
            rawDataPayloadService.markParseFailed(payload.getId(), parseResult.errorMessage());
            DataSyncRun finished = dataSyncRunService.finishPartial(
                    run.getId(),
                    new SyncRunFinishDto(
                            fetchedCount,
                            successCount,
                            failureCount,
                            fetchResult.retryCount(),
                            fetchResult.quotaCost(),
                            parseResult.errorMessage()
                    )
            );
                return record(new ProviderSyncOutcome(finished, payload, SyncStatusEnum.PARTIAL, saveResult.duplicate()));
            }

        rawDataPayloadService.markParseFailed(payload.getId(), parseResult.errorMessage());
        DataSyncRun finished = dataSyncRunService.finishFailed(
                run.getId(),
                new SyncRunFinishDto(
                        fetchedCount,
                        0,
                        failureCount,
                        fetchResult.retryCount(),
                        fetchResult.quotaCost(),
                        parseResult.errorMessage()
                )
        );
            return record(new ProviderSyncOutcome(finished, payload, SyncStatusEnum.FAILED, saveResult.duplicate()));
        } finally {
            restoreMdc(TraceIdContext.MDC_KEY, priorTraceId);
            restoreMdc("providerCode", priorProviderCode);
            restoreMdc("syncRunId", priorSyncRunId);
            restoreMdc("status", priorStatus);
            restoreMdc("durationMs", priorDurationMs);
        }
    }

    private ProviderHttpException findHttpException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ProviderHttpException httpException) {
                return httpException;
            }
            current = current.getCause();
        }
        return null;
    }

    private ProviderSyncOutcome record(ProviderSyncOutcome outcome) {
        syncMetrics.record(outcome.syncRun());
        DataSyncRun run = outcome.syncRun();
        if (run != null) {
            long durationMs = run.getStartedAt() == null || run.getFinishedAt() == null
                    ? 0
                    : Math.max(java.time.Duration.between(run.getStartedAt(), run.getFinishedAt()).toMillis(), 0);
            MDC.put("status", outcome.status().getCode());
            MDC.put("durationMs", String.valueOf(durationMs));
            log.info("event=sync_finished status={} fetched={} succeeded={} failed={} durationMs={}",
                    outcome.status(), run.getFetchedCount(), run.getSuccessCount(), run.getFailureCount(), durationMs);
        }
        return outcome;
    }

    private String sanitize(String message) {
        return truncate(sanitizer.sanitizeText(message));
    }

    private void restoreMdc(String key, String previousValue) {
        if (previousValue == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, previousValue);
        }
    }

    private String truncate(String message) {
        if (!StringUtils.hasText(message)) {
            return message;
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
