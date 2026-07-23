package com.jingcaicompass.data.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jingcaicompass.data.dto.SyncRunFinishDto;
import com.jingcaicompass.data.entity.DataSyncRun;
import com.jingcaicompass.data.enums.ProviderDataTypeEnum;
import com.jingcaicompass.data.enums.SyncStatusEnum;
import com.jingcaicompass.data.mapper.DataSyncRunMapper;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;

@Service
@ConditionalOnBean(DataSource.class)
public class DataSyncRunServiceImpl extends ServiceImpl<DataSyncRunMapper, DataSyncRun>
        implements DataSyncRunService {

    @Override
    @Transactional
    public DataSyncRun startRun(String providerCode, ProviderDataTypeEnum dataType) {
        if (!StringUtils.hasText(providerCode)) {
            throw new IllegalArgumentException("providerCode must not be blank");
        }
        if (dataType == null) {
            throw new IllegalArgumentException("dataType must not be null");
        }
        DataSyncRun run = new DataSyncRun();
        run.setProviderCode(providerCode);
        run.setDataType(dataType);
        run.setSyncStatus(SyncStatusEnum.RUNNING);
        Instant now = Instant.now();
        run.setStartedAt(now);
        run.setFetchedCount(0);
        run.setSuccessCount(0);
        run.setFailureCount(0);
        run.setRetryCount(0);
        run.setQuotaCost(0);
        save(run);
        return run;
    }

    @Override
    @Transactional
    public DataSyncRun finishSuccess(Long runId, SyncRunFinishDto finish) {
        return finish(runId, SyncStatusEnum.SUCCESS, finish);
    }

    @Override
    @Transactional
    public DataSyncRun finishPartial(Long runId, SyncRunFinishDto finish) {
        return finish(runId, SyncStatusEnum.PARTIAL, finish);
    }

    @Override
    @Transactional
    public DataSyncRun finishFailed(Long runId, SyncRunFinishDto finish) {
        return finish(runId, SyncStatusEnum.FAILED, finish);
    }

    private DataSyncRun finish(Long runId, SyncStatusEnum status, SyncRunFinishDto finish) {
        if (runId == null) {
            throw new IllegalArgumentException("runId must not be null");
        }
        if (finish == null) {
            throw new IllegalArgumentException("finish must not be null");
        }
        DataSyncRun run = getById(runId);
        if (run == null) {
            throw new IllegalArgumentException("sync run not found: " + runId);
        }
        if (run.getSyncStatus() != SyncStatusEnum.RUNNING) {
            throw new IllegalStateException(
                    "sync run %d is already finished as %s".formatted(runId, run.getSyncStatus())
            );
        }
        run.setSyncStatus(status);
        run.setFinishedAt(Instant.now());
        run.setFetchedCount(finish.fetchedCount());
        run.setSuccessCount(finish.successCount());
        run.setFailureCount(finish.failureCount());
        run.setRetryCount(finish.retryCount());
        run.setQuotaCost(finish.quotaCost());
        run.setErrorMessage(finish.errorMessage());
        updateById(run);
        return run;
    }
}
