package com.jingcaicompass.data.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jingcaicompass.data.entity.DataSyncRun;
import com.jingcaicompass.data.mapper.DataSyncRunMapper;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnBean(DataSource.class)
public class DataSyncRunServiceImpl extends ServiceImpl<DataSyncRunMapper, DataSyncRun>
        implements DataSyncRunService {
}
