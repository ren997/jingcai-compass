package com.jingcaicompass.admin.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.jingcaicompass.admin.enums.AdminPredictionLockDiagnosticEnum;
import com.jingcaicompass.admin.enums.AdminSettlementDiagnosticEnum;
import com.jingcaicompass.prediction.enums.PredictionStatusEnum;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class AdminPredictionStatusMapperTest {

    @Test
    void rendersLockAndSettlementFiltersAsBoundSqlWithoutRawClientValues() {
        Configuration configuration = new Configuration();
        configuration.addMapper(AdminPredictionStatusMapper.class);
        AdminPredictionStatusCriteria criteria = new AdminPredictionStatusCriteria(
                LocalDate.of(2026, 7, 29), "model-v1", List.of(PredictionStatusEnum.PUBLISHED),
                List.of(AdminPredictionLockDiagnosticEnum.OVERDUE),
                List.of(AdminSettlementDiagnosticEnum.SETTLEMENT_STALE_HAD), 20, 20,
                Instant.parse("2026-07-29T01:00:00Z")
        );

        BoundSql lockSql = configuration.getMappedStatement(statement("selectLockPredictionIds"))
                .getBoundSql(Map.of("criteria", criteria));
        BoundSql settlementSql = configuration.getMappedStatement(statement("selectSettlementPredictionIds"))
                .getBoundSql(Map.of("criteria", criteria));

        assertThat(lockSql.getSql()).contains("p.lock_time <= ?", "ORDER BY CASE").doesNotContain("model-v1");
        assertThat(lockSql.getParameterMappings()).isNotEmpty();
        assertThat(settlementSql.getSql()).contains("had.match_fact_id <> fact.id", "p.prediction_status = 'LOCKED'")
                .doesNotContain("model-v1");
        assertThat(settlementSql.getParameterMappings()).isNotEmpty();
    }

    private String statement(String method) {
        return AdminPredictionStatusMapper.class.getName() + "." + method;
    }
}
