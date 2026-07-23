package com.jingcaicompass.data.service;

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
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public ProviderSyncTemplate(
            DataSyncRunService dataSyncRunService,
            RawDataPayloadService rawDataPayloadService
    ) {
        this.dataSyncRunService = dataSyncRunService;
        this.rawDataPayloadService = rawDataPayloadService;
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
        log.info("sync started syncRunId={} providerCode={} dataType={}",
                run.getId(), providerCode, dataType);

        ProviderFetchResult fetchResult;
        try {
            fetchResult = fetcher.fetch();
        } catch (RuntimeException exception) {
            ProviderHttpException httpFailure = findHttpException(exception);
            int retryCount = httpFailure == null ? 0 : httpFailure.retryCount();
            int quotaCost = httpFailure == null ? 0 : httpFailure.quotaCost();
            DataSyncRun finished = dataSyncRunService.finishFailed(
                    run.getId(),
                    new SyncRunFinishDto(0, 0, 0, retryCount, quotaCost, truncate(exception.getMessage()))
            );
            log.warn("sync fetch failed syncRunId={} providerCode={} dataType={}",
                    run.getId(), providerCode, dataType, exception);
            return new ProviderSyncOutcome(finished, null, SyncStatusEnum.FAILED, false);
        }

        if (fetchResult == null || !StringUtils.hasText(fetchResult.payloadJson())) {
            DataSyncRun finished = dataSyncRunService.finishFailed(
                    run.getId(),
                    new SyncRunFinishDto(0, 0, 0, 0, 0, "empty provider response")
            );
            return new ProviderSyncOutcome(finished, null, SyncStatusEnum.FAILED, false);
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

        ProviderParseResult parseResult;
        try {
            parseResult = parser.parse(dataType, fetchResult.requestKey(), payload);
            if (parseResult == null) {
                parseResult = ProviderParseResult.empty();
            }
        } catch (RuntimeException exception) {
            rawDataPayloadService.markParseFailed(payload.getId(), truncate(exception.getMessage()));
            DataSyncRun finished = dataSyncRunService.finishFailed(
                    run.getId(),
                    new SyncRunFinishDto(
                            1,
                            0,
                            1,
                            fetchResult.retryCount(),
                            fetchResult.quotaCost(),
                            truncate(exception.getMessage())
                    )
            );
            log.warn("sync parse threw syncRunId={} payloadId={}", run.getId(), payload.getId(), exception);
            return new ProviderSyncOutcome(finished, payload, SyncStatusEnum.FAILED, saveResult.duplicate());
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
            return new ProviderSyncOutcome(finished, payload, SyncStatusEnum.SUCCESS, saveResult.duplicate());
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
            return new ProviderSyncOutcome(finished, payload, SyncStatusEnum.PARTIAL, saveResult.duplicate());
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
        return new ProviderSyncOutcome(finished, payload, SyncStatusEnum.FAILED, saveResult.duplicate());
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

    private String truncate(String message) {
        if (!StringUtils.hasText(message)) {
            return message;
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
