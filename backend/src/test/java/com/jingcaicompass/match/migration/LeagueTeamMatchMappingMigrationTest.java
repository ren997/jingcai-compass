package com.jingcaicompass.match.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * T003 跳过 Testcontainers 后的替代验证：静态检查 V2 migration 契约。
 */
class LeagueTeamMatchMappingMigrationTest {

    @Test
    void definesLeagueTeamMatchAndMappingContracts() throws IOException {
        String sql = new ClassPathResource("db/migration/V2__init_league_team_match_and_mapping.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql).contains("CREATE TABLE leagues");
        assertThat(sql).contains("CREATE TABLE teams");
        assertThat(sql).contains("CREATE TABLE matches");
        assertThat(sql).contains("CREATE TABLE provider_league_mappings");
        assertThat(sql).contains("CREATE TABLE provider_team_mappings");
        assertThat(sql).contains("CREATE TABLE match_source_mappings");

        assertThat(sql).contains(
                "CONSTRAINT uk_matches_lottery_match_no_date UNIQUE (lottery_match_no, lottery_date)"
        );
        assertThat(sql).contains(
                "CONSTRAINT uk_match_source_mappings_provider_external UNIQUE (provider_code, external_match_id)"
        );
        assertThat(sql).contains("CONSTRAINT ck_matches_home_away_team_diff CHECK");
        assertThat(sql).contains("CONSTRAINT ck_matches_scores_non_negative CHECK");
        assertThat(sql).contains("CONSTRAINT ck_match_source_mappings_confidence CHECK");
        assertThat(sql).contains("'SCHEDULED'");
        assertThat(sql).contains("'LOCKED'");
        assertThat(sql).contains("'IN_PROGRESS'");
        assertThat(sql).contains("'ABANDONED'");
        assertThat(sql).contains("'AUTO_CONFIRMED'");
        assertThat(sql).contains("'MANUAL_CONFIRMED'");
        assertThat(sql).contains("CREATE INDEX idx_matches_lottery_date");
        assertThat(sql).contains("CREATE INDEX idx_match_source_mappings_match");
    }
}
