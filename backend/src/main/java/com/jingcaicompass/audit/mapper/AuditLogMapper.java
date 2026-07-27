package com.jingcaicompass.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jingcaicompass.audit.entity.AuditLog;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {

    /** 查询由赛果修正结算任务写入过 SUPERSEDE 审计的结算 ID。 */
    @Select("""
            <script>
            SELECT target_id
            FROM audit_logs
            WHERE target_type = 'SETTLEMENT'
              AND action_type = 'SUPERSEDE'
              AND target_id IN
              <foreach collection="settlementIds" item="settlementId" open="(" separator="," close=")">
                  CAST(#{settlementId} AS VARCHAR)
              </foreach>
            </script>
            """)
    List<String> selectSupersededSettlementTargetIds(@Param("settlementIds") Collection<Long> settlementIds);
}
