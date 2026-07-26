package com.jingcaicompass.settlement.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** 静态检查 V10/V11 赛果事实与结算版本化契约。 */
class SettlementMigrationTest {

    @Test
    void definesVersionedFactsSettlementsAndProtectionGuards() throws IOException {
        String sql = new ClassPathResource("db/migration/V10__init_match_facts_and_settlements.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql).contains(
                "ck_matches_legacy_scores_unverified",
                "CREATE TABLE match_result_facts",
                "uk_match_result_facts_match_version UNIQUE (match_id, fact_version)",
                "CREATE UNIQUE INDEX uk_match_result_facts_current",
                "raw_data_payload_id       BIGINT       NOT NULL",
                "fact_status IN ('PENDING', 'FINAL', 'VOID')",
                "ck_match_result_facts_sporttery_result_payload",
                "validate_match_result_fact_payload",
                "CREATE TABLE settlements",
                "uk_settlements_prediction_market_version UNIQUE",
                "CREATE UNIQUE INDEX uk_settlements_current",
                "settlement_status IN ('HIT', 'MISS', 'VOID')",
                "validate_settlement_match_fact",
                "protect_match_result_fact",
                "protect_settlement",
                "BEFORE UPDATE OR DELETE ON match_result_facts",
                "BEFORE UPDATE OR DELETE ON settlements"
        );
        assertThat(sql).doesNotContain("PENDING')\n    ),\n    CONSTRAINT ck_settlements_version_chain");
    }

    @Test
    void addsOnlyQueryBackedCoreIndexes() throws IOException {
        String sql = new ClassPathResource("db/migration/V11__add_core_indexes.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql).contains(
                "idx_match_result_facts_current_eligible",
                "idx_settlements_current_match_fact",
                "uk_settlements_prediction_market_version",
                "fact_status IN ('FINAL', 'VOID')"
        );
        assertThat(sql).doesNotContain("idx_predictions_status_lock_time");
    }
}
