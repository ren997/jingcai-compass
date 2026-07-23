package com.jingcaicompass.data.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.jingcaicompass.data.dto.SyncRunFinishDto;
import com.jingcaicompass.data.entity.DataSyncRun;
import com.jingcaicompass.data.enums.ProviderDataTypeEnum;

public interface DataSyncRunService extends IService<DataSyncRun> {

    /**
     * 创建一条 RUNNING 同步运行记录。
     */
    DataSyncRun startRun(String providerCode, ProviderDataTypeEnum dataType);

    /**
     * 以全部成功收尾。
     */
    DataSyncRun finishSuccess(Long runId, SyncRunFinishDto finish);

    /**
     * 以部分成功收尾。
     */
    DataSyncRun finishPartial(Long runId, SyncRunFinishDto finish);

    /**
     * 以失败收尾。
     */
    DataSyncRun finishFailed(Long runId, SyncRunFinishDto finish);
}
