package com.jingcaicompass.match.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** 静态检查 V6 审计表契约。 */
class MappingReviewAuditMigrationTest {

    @Test
    void definesAuditLogsContract() throws IOException {
        String sql = new ClassPathResource("db/migration/V6__init_audit_logs.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql).contains("CREATE TABLE audit_logs");
        assertThat(sql).contains("operator_id");
        assertThat(sql).contains("target_type");
        assertThat(sql).contains("target_id");
        assertThat(sql).contains("action_type");
        assertThat(sql).contains("old_value");
        assertThat(sql).contains("new_value");
        assertThat(sql).contains("CREATE INDEX idx_audit_logs_target");
    }
}
