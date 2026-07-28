package com.jingcaicompass.data.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jingcaicompass.data.entity.RawDataPayload;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RawDataPayloadMapper extends BaseMapper<RawDataPayload> {

    /** 按精确关联读取某次同步运行持有的全部原始载荷。 */
    @Select("""
            SELECT payload.*
            FROM raw_data_payloads payload
            INNER JOIN data_sync_run_payloads link
                ON link.raw_data_payload_id = payload.id
            WHERE link.sync_run_id = #{syncRunId}
            ORDER BY payload.requested_at DESC, payload.id DESC
            """)
    List<RawDataPayload> selectBySyncRunId(@Param("syncRunId") Long syncRunId);
}
