package com.jingcaicompass.match.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * T003 跳过 Testcontainers 后的替代验证：静态检查 V3 快照表约束契约。
 */
class SportteryAsianOddsSnapshotConstraintTest {

    @Test
    void definesAppendOnlySnapshotContracts() throws IOException {
        String sql = new ClassPathResource(
                        "db/migration/V3__init_sporttery_and_asian_odds_snapshots.sql"
                )
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql).contains("CREATE TABLE sporttery_pool_snapshots");
        assertThat(sql).contains("CREATE TABLE asian_odds_snapshots");
        assertThat(sql).contains("had_home_sp         NUMERIC(10,4)");
        assertThat(sql).contains("hhad_away_sp        NUMERIC(10,4)");
        assertThat(sql).contains("handicap_line       NUMERIC(6,2)   NOT NULL");
        assertThat(sql).contains("home_odds           NUMERIC(10,4)  NOT NULL");
        assertThat(sql).contains("total_line          NUMERIC(6,2)");
        assertThat(sql).contains("over_odds           NUMERIC(10,4)");
        assertThat(sql).contains("under_odds          NUMERIC(10,4)");
        assertThat(sql).contains(
                "CONSTRAINT uk_sporttery_pool_snapshots_dedupe UNIQUE (match_id, captured_at, raw_payload_hash)"
        );
        assertThat(sql).contains("CONSTRAINT uk_asian_odds_snapshots_dedupe UNIQUE");
        assertThat(sql).contains("CONSTRAINT ck_asian_odds_snapshots_totals_complete CHECK");
        assertThat(sql).contains("bookmaker_code,");
        assertThat(sql).contains("captured_at,");
        assertThat(sql).contains("handicap_line");
        assertThat(sql).contains("'FIRST_SEEN'");
        assertThat(sql).contains("'PRE_KICKOFF'");
        assertThat(sql).contains("CREATE INDEX idx_sporttery_pool_snapshots_match_captured");
        assertThat(sql).contains("CREATE INDEX idx_asian_odds_snapshots_match_captured");
        assertThat(sql).contains("provider_updated_at");
        // Snapshots are append-only: no mutable updated_at column (provider_updated_at is allowed).
        assertThat(sql).doesNotContain("\n    updated_at");
    }
}
