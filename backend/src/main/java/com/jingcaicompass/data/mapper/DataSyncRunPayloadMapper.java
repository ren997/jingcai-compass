package com.jingcaicompass.data.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jingcaicompass.data.entity.DataSyncRunPayload;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DataSyncRunPayloadMapper extends BaseMapper<DataSyncRunPayload> {

    /** 幂等写入同步运行与载荷的关联。 */
    @Insert("""
            INSERT INTO data_sync_run_payloads (sync_run_id, raw_data_payload_id)
            VALUES (#{syncRunId}, #{rawDataPayloadId})
            ON CONFLICT (sync_run_id, raw_data_payload_id) DO NOTHING
            """)
    int insertIgnore(
            @Param("syncRunId") Long syncRunId,
            @Param("rawDataPayloadId") Long rawDataPayloadId
    );
}
