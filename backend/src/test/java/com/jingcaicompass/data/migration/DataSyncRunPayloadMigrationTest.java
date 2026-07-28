package com.jingcaicompass.data.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** 静态检查 V13 的精确运行—载荷关联约束。 */
class DataSyncRunPayloadMigrationTest {

    @Test
    void definesExactManyToManyRunPayloadLink() throws IOException {
        String sql = new ClassPathResource("db/migration/V13__link_sync_runs_to_raw_payloads.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql).contains("CREATE TABLE data_sync_run_payloads");
        assertThat(sql).contains("sync_run_id");
        assertThat(sql).contains("raw_data_payload_id");
        assertThat(sql).contains("uk_data_sync_run_payloads_run_payload");
        assertThat(sql).contains("fk_data_sync_run_payloads_run");
        assertThat(sql).contains("fk_data_sync_run_payloads_payload");
    }
}
