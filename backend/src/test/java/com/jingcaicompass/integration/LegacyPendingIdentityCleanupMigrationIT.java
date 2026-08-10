package com.jingcaicompass.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jingcaicompass.match.support.ProviderEntityKeySupport;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL 16 验证 V20 对 V17 遗留待复核身份的受控清理。 */
@Testcontainers
class LegacyPendingIdentityCleanupMigrationIT {

    private static final AtomicLong MATCH_SEQUENCE = new AtomicLong(20_900_000L);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("jingcai_legacy_cleanup")
            .withUsername("jingcai_test")
            .withPassword("jingcai_test");

    @BeforeEach
    void migratesOnlyThroughV17BeforePreparingLegacyRows() {
        Flyway flyway = flyway("17");
        flyway.clean();
        flyway.migrate();
    }

    @Test
    void removesOnlyUniquelyRecoverableOrphansAndPreservesProtectedEntities() throws Exception {
        long removableTeam = insertTeam("V20 可清理球队");
        insertLegacyTeamMapping("V20 可清理球队", null);
        long matchProtectedTeam = insertTeam("V20 比赛保护球队");
        insertLegacyTeamMapping("V20 比赛保护球队", null);
        insertMatch(null, matchProtectedTeam, null);
        long aliasProtectedTeam = insertTeam("V20 别名保护球队");
        insertLegacyTeamMapping("V20 别名保护球队", null);
        insertTeamAlias(aliasProtectedTeam, "V20 别名保护球队别名");
        long mappingProtectedTeam = insertTeam("V20 映射保护球队");
        insertLegacyTeamMapping("V20 映射保护球队", null);
        insertConfirmedTeamMapping(mappingProtectedTeam, "V20-PROTECTED-TEAM");
        long duplicateTeamOne = insertTeam("V20 歧义球队");
        long duplicateTeamTwo = insertTeam("V20 歧义球队");
        insertLegacyTeamMapping("V20 歧义球队", null);
        long scopedRemovableTeam = insertTeam("V20 作用域可清理球队");
        insertLegacyTeamMapping("V20 作用域可清理球队", "soccer_v20_test");

        long removableLeague = insertLeague("V20 可清理联赛");
        insertLegacyLeagueMapping("V20 可清理联赛", null);
        long protectedLeague = insertLeague("V20 比赛保护联赛");
        insertLegacyLeagueMapping("V20 比赛保护联赛", null);
        insertMatch(protectedLeague, null, null);

        assertConfirmedMappingsCannotBeUnlinked();
        migrateV20();

        assertThat(entityExists("teams", removableTeam)).isFalse();
        assertThat(entityExists("teams", scopedRemovableTeam)).isFalse();
        assertThat(entityExists("teams", matchProtectedTeam)).isTrue();
        assertThat(entityExists("teams", aliasProtectedTeam)).isTrue();
        assertThat(entityExists("teams", mappingProtectedTeam)).isTrue();
        assertThat(entityExists("teams", duplicateTeamOne)).isTrue();
        assertThat(entityExists("teams", duplicateTeamTwo)).isTrue();
        assertThat(entityExists("leagues", removableLeague)).isFalse();
        assertThat(entityExists("leagues", protectedLeague)).isTrue();
        assertThat(countLegacyUnlinkedMappings("provider_team_mappings", "team_id")).isEqualTo(6);
        assertThat(countLegacyUnlinkedMappings("provider_league_mappings", "league_id")).isEqualTo(2);
        assertThat(flyway(null).info().current().getVersion().getVersion()).isEqualTo("20");
    }

    private void migrateV20() {
        Flyway flyway = flyway(null);
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
    }

    private void assertConfirmedMappingsCannotBeUnlinked() {
        assertThatThrownBy(() -> executeUpdate("""
                INSERT INTO provider_team_mappings (
                    team_id, provider_code, external_team_id, mapping_status, mapping_method
                )
                VALUES (NULL, 'V20_INVALID', 'V20-INVALID-TEAM', 'MANUAL_CONFIRMED', 'V20_TEST')
                """))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> executeUpdate("""
                INSERT INTO provider_league_mappings (
                    league_id, provider_code, external_league_id, mapping_status, mapping_method
                )
                VALUES (NULL, 'V20_INVALID', 'V20-INVALID-LEAGUE', 'AUTO_CONFIRMED', 'V20_TEST')
                """))
                .isInstanceOf(SQLException.class);
    }

    private long insertTeam(String name) throws SQLException {
        return insertEntity("teams", name);
    }

    private long insertLeague(String name) throws SQLException {
        return insertEntity("leagues", name);
    }

    private long insertEntity(String table, String name) throws SQLException {
        return queryForLong("INSERT INTO " + table + " (name_zh, name_en) VALUES (?, ?) RETURNING id", name, name);
    }

    private void insertLegacyTeamMapping(String name, String scope) throws SQLException {
        String externalId = scope == null
                ? ProviderEntityKeySupport.nameKey(name)
                : ProviderEntityKeySupport.scopedNameKey(scope, name);
        executeUpdate("""
                INSERT INTO provider_team_mappings (
                    team_id, provider_code, external_team_id, external_scope, mapping_status, mapping_method
                )
                VALUES (NULL, 'THE_ODDS_API', ?, ?, 'PENDING', 'LEGACY_NAME_CANDIDATE_REVIEW_REQUIRED')
                """, externalId, scope);
    }

    private void insertLegacyLeagueMapping(String name, String scope) throws SQLException {
        String externalId = scope == null
                ? ProviderEntityKeySupport.nameKey(name)
                : ProviderEntityKeySupport.scopedNameKey(scope, name);
        executeUpdate("""
                INSERT INTO provider_league_mappings (
                    league_id, provider_code, external_league_id, external_scope, mapping_status, mapping_method
                )
                VALUES (NULL, 'THE_ODDS_API', ?, ?, 'PENDING', 'LEGACY_NAME_CANDIDATE_REVIEW_REQUIRED')
                """, externalId, scope);
    }

    private void insertConfirmedTeamMapping(long teamId, String externalId) throws SQLException {
        executeUpdate("""
                INSERT INTO provider_team_mappings (
                    team_id, provider_code, external_team_id, mapping_status, mapping_method
                )
                VALUES (?, 'V20_PROTECTED', ?, 'MANUAL_CONFIRMED', 'V20_TEST')
                """, teamId, externalId);
    }

    private void insertTeamAlias(long teamId, String alias) throws SQLException {
        executeUpdate("""
                INSERT INTO team_aliases (team_id, alias_raw, alias_normalized, source, confirmed_by)
                VALUES (?, ?, ?, 'V20_TEST', 'integration')
                """, teamId, alias, alias);
    }

    private void insertMatch(Long leagueId, Long homeTeamId, Long awayTeamId) throws SQLException {
        long sequence = MATCH_SEQUENCE.incrementAndGet();
        executeUpdate("""
                INSERT INTO matches (
                    lottery_match_no, lottery_date, league_id, home_team_id, away_team_id,
                    league_name, home_team_name, away_team_name, kickoff_time, match_status
                )
                VALUES (?, ?, ?, ?, ?, 'V20 League', 'V20 Home', 'V20 Away', ?, 'SCHEDULED')
                """,
                "V20-" + sequence,
                LocalDate.of(2026, 8, 10),
                leagueId,
                homeTeamId,
                awayTeamId,
                Instant.parse("2026-08-11T12:00:00Z"));
    }

    private boolean entityExists(String table, long id) throws SQLException {
        return queryForLong("SELECT COUNT(*) FROM " + table + " WHERE id = ?", id) == 1;
    }

    private long countLegacyUnlinkedMappings(String table, String entityIdColumn) throws SQLException {
        return queryForLong("""
                SELECT COUNT(*)
                FROM %s
                WHERE provider_code = 'THE_ODDS_API'
                  AND mapping_status = 'PENDING'
                  AND mapping_method = 'LEGACY_NAME_CANDIDATE_REVIEW_REQUIRED'
                  AND %s IS NULL
                """.formatted(table, entityIdColumn));
    }

    private long queryForLong(String sql, Object... parameters) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private void executeUpdate(String sql, Object... parameters) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            statement.executeUpdate();
        }
    }

    private Connection connection() throws SQLException {
        return POSTGRES.createConnection("");
    }

    private Flyway flyway(String target) {
        var configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .cleanDisabled(false);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private void bind(PreparedStatement statement, Object... parameters) throws SQLException {
        for (int index = 0; index < parameters.length; index++) {
            statement.setObject(index + 1, parameters[index]);
        }
    }
}
