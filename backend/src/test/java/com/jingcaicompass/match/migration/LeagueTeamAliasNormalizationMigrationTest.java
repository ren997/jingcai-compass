package com.jingcaicompass.match.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** 静态检查 V4 联赛/球队别名表契约。 */
class LeagueTeamAliasNormalizationMigrationTest {

    @Test
    void definesLeagueAndTeamAliasContracts() throws IOException {
        String sql = new ClassPathResource("db/migration/V4__init_league_and_team_aliases.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql).contains("CREATE TABLE league_aliases");
        assertThat(sql).contains("CREATE TABLE team_aliases");
        assertThat(sql).contains("alias_raw");
        assertThat(sql).contains("alias_normalized");
        assertThat(sql).contains("confirmed_by");
        assertThat(sql).contains("confirmed_at");
        assertThat(sql).contains("CONSTRAINT uk_league_aliases_normalized UNIQUE (alias_normalized)");
        assertThat(sql).contains("CONSTRAINT uk_team_aliases_normalized UNIQUE (alias_normalized)");
        assertThat(sql).contains("CONSTRAINT fk_league_aliases_league FOREIGN KEY (league_id) REFERENCES leagues (id)");
        assertThat(sql).contains("CONSTRAINT fk_team_aliases_team FOREIGN KEY (team_id) REFERENCES teams (id)");
    }
}
