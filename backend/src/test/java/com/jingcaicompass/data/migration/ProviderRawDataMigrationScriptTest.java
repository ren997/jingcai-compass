package com.jingcaicompass.data.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * T003 跳过 Testcontainers 后的替代验证：静态检查 V1 migration 契约。
 */
class ProviderRawDataMigrationScriptTest {

    @Test
    void definesProviderRawPayloadAndSyncRunContracts() throws IOException {
        String sql = new ClassPathResource("db/migration/V1__init_provider_and_raw_data.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql).contains("CREATE TABLE data_providers");
        assertThat(sql).contains("CREATE TABLE raw_data_payloads");
        assertThat(sql).contains("CREATE TABLE data_sync_runs");
        assertThat(sql).contains("CONSTRAINT uk_data_providers_provider_code UNIQUE (provider_code)");
        assertThat(sql).contains(
                "CONSTRAINT uk_raw_data_payloads_dedupe UNIQUE (provider_code, data_type, request_key, payload_hash)"
        );
        assertThat(sql).contains("payload              JSONB        NOT NULL");
        assertThat(sql).contains("payload_hash         CHAR(64)     NOT NULL");
        assertThat(sql).contains("CREATE INDEX idx_data_sync_runs_provider_started");
        assertThat(sql).contains("INSERT INTO data_providers");
        assertThat(sql).doesNotContain("api_key");
        assertThat(sql).doesNotContain("password");
    }
}
