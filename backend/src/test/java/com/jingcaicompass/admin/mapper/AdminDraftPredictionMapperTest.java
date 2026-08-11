package com.jingcaicompass.admin.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class AdminDraftPredictionMapperTest {

    @Test
    void rendersOnlyDraftQueriesWithBoundFilters() {
        Configuration configuration = new Configuration();
        configuration.addMapper(AdminDraftPredictionMapper.class);
        AdminDraftPredictionCriteria criteria = new AdminDraftPredictionCriteria(
                LocalDate.of(2026, 8, 11), "baseline-v1", 20, 20
        );

        BoundSql listSql = configuration.getMappedStatement(statement("selectDraftPredictions"))
                .getBoundSql(Map.of("criteria", criteria));
        BoundSql countSql = configuration.getMappedStatement(statement("countDraftPredictions"))
                .getBoundSql(Map.of("criteria", criteria));

        assertThat(listSql.getSql()).contains("p.prediction_status = 'DRAFT'", "ORDER BY m.kickoff_time ASC, p.id ASC")
                .doesNotContain("baseline-v1");
        assertThat(countSql.getSql()).contains("p.prediction_status = 'DRAFT'").doesNotContain("baseline-v1");
        assertThat(listSql.getParameterMappings()).hasSize(4);
        assertThat(countSql.getParameterMappings()).hasSize(2);
    }

    private String statement(String method) {
        return AdminDraftPredictionMapper.class.getName() + "." + method;
    }
}
