package com.jingcaicompass.data.service;

import com.jingcaicompass.data.mapper.DataSyncRunPayloadMapper;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

/** 以数据库唯一约束保障同步运行关联的重复写入安全。 */
@Service
@ConditionalOnBean(DataSource.class)
public class DataSyncRunPayloadLinkServiceImpl implements DataSyncRunPayloadLinkService {

    private final DataSyncRunPayloadMapper dataSyncRunPayloadMapper;

    public DataSyncRunPayloadLinkServiceImpl(DataSyncRunPayloadMapper dataSyncRunPayloadMapper) {
        this.dataSyncRunPayloadMapper = dataSyncRunPayloadMapper;
    }

    @Override
    public void link(Long syncRunId, Long rawDataPayloadId) {
        if (syncRunId == null || rawDataPayloadId == null) {
            throw new IllegalArgumentException("syncRunId and rawDataPayloadId must not be null");
        }
        // 1) 写入唯一关联；去重载荷可关联不同运行
        dataSyncRunPayloadMapper.insertIgnore(syncRunId, rawDataPayloadId);
    }
}
