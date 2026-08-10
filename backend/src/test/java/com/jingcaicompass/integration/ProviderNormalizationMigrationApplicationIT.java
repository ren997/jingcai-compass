package com.jingcaicompass.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL 16 验证 V17/V19 对历史 The Odds 待复核身份的受控清理与引用保护。 */
@Testcontainers
@TestMethodOrder(OrderAnnotation.class)
class ProviderNormalizationMigrationApplicationIT {

    private static final AtomicLong KEY_SEQUENCE = new AtomicLong(2_090_000L);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("jingcai_provider_normalization")
            .withUsername("jingcai_test")
            .withPassword("jingcai_test");

    @Test
    @Order(1)
    void v17DetachesLegacyCandidatesDeletesOnlyOrphansAndProtectsReferencedEntities() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()
        ));

        // 1) 先恢复 V16 的历史形态，构造待清理和必须保留的 The Odds 名称候选。
        assertThat(flyway("16").migrate().migrationsExecuted).isEqualTo(16);
        long orphanLeague = insertLeague(jdbcTemplate, "孤立联赛");
        long matchLeague = insertLeague(jdbcTemplate, "比赛引用联赛");
        long aliasLeague = insertLeague(jdbcTemplate, "别名引用联赛");
        long mappingLeague = insertLeague(jdbcTemplate, "映射引用联赛");
        long orphanTeam = insertTeam(jdbcTemplate, "孤立球队");
        long matchTeam = insertTeam(jdbcTemplate, "比赛引用球队");
        long aliasTeam = insertTeam(jdbcTemplate, "别名引用球队");
        long mappingTeam = insertTeam(jdbcTemplate, "映射引用球队");

        insertMatch(jdbcTemplate, matchLeague, null, "league-reference");
        insertMatch(jdbcTemplate, null, matchTeam, "team-reference");
        jdbcTemplate.update("""
                INSERT INTO league_aliases (league_id, alias_raw, alias_normalized, source, confirmed_by)
                VALUES (?, 'T209 联赛别名', ?, 'T209', 'tester')
                """, aliasLeague, uniqueKey("league-alias"));
        jdbcTemplate.update("""
                INSERT INTO team_aliases (team_id, alias_raw, alias_normalized, source, confirmed_by)
                VALUES (?, 'T209 球队别名', ?, 'T209', 'tester')
                """, aliasTeam, uniqueKey("team-alias"));
        insertLeagueMapping(jdbcTemplate, mappingLeague, "OTHER_PROVIDER", "confirmed-league", "MANUAL_CONFIRMED");
        insertTeamMapping(jdbcTemplate, mappingTeam, "OTHER_PROVIDER", "confirmed-team", "MANUAL_CONFIRMED");

        long orphanLeagueCandidate = insertLeagueCandidate(jdbcTemplate, orphanLeague, "orphan-league");
        long matchLeagueCandidate = insertLeagueCandidate(jdbcTemplate, matchLeague, "match-league");
        long aliasLeagueCandidate = insertLeagueCandidate(jdbcTemplate, aliasLeague, "alias-league");
        long mappingLeagueCandidate = insertLeagueCandidate(jdbcTemplate, mappingLeague, "mapping-league");
        long orphanTeamCandidate = insertTeamCandidate(jdbcTemplate, orphanTeam, "orphan-team");
        long matchTeamCandidate = insertTeamCandidate(jdbcTemplate, matchTeam, "match-team");
        long aliasTeamCandidate = insertTeamCandidate(jdbcTemplate, aliasTeam, "alias-team");
        long mappingTeamCandidate = insertTeamCandidate(jdbcTemplate, mappingTeam, "mapping-team");

        // 2) V17 先解绑候选，保留不可推断的历史身份供人工复核。
        MigrateResult migration = flyway("17").migrate();
        assertThat(migration.migrationsExecuted).isEqualTo(1);

        assertDetachedLegacyLeagueCandidate(jdbcTemplate, orphanLeagueCandidate);
        assertDetachedLegacyLeagueCandidate(jdbcTemplate, matchLeagueCandidate);
        assertDetachedLegacyLeagueCandidate(jdbcTemplate, aliasLeagueCandidate);
        assertDetachedLegacyLeagueCandidate(jdbcTemplate, mappingLeagueCandidate);
        assertDetachedLegacyTeamCandidate(jdbcTemplate, orphanTeamCandidate);
        assertDetachedLegacyTeamCandidate(jdbcTemplate, matchTeamCandidate);
        assertDetachedLegacyTeamCandidate(jdbcTemplate, aliasTeamCandidate);
        assertDetachedLegacyTeamCandidate(jdbcTemplate, mappingTeamCandidate);

        // 3) V19 仅清理仍可由元数据与同事务创建时间证明的孤立临时实体。
        assertThat(flyway("19").migrate().migrationsExecuted).isEqualTo(2);
        assertThat(countById(jdbcTemplate, "leagues", orphanLeague)).isZero();
        assertThat(countById(jdbcTemplate, "teams", orphanTeam)).isZero();
        assertThat(countById(jdbcTemplate, "leagues", matchLeague)).isOne();
        assertThat(countById(jdbcTemplate, "leagues", aliasLeague)).isOne();
        assertThat(countById(jdbcTemplate, "leagues", mappingLeague)).isOne();
        assertThat(countById(jdbcTemplate, "teams", matchTeam)).isOne();
        assertThat(countById(jdbcTemplate, "teams", aliasTeam)).isOne();
        assertThat(countById(jdbcTemplate, "teams", mappingTeam)).isOne();

        // 4) V17 允许外部 PENDING 身份不链接内部实体，但禁止已确认关系缺失实体。
        insertLeagueMapping(jdbcTemplate, null, "THE_ODDS_API", "pending-without-entity", "PENDING");
        insertTeamMapping(jdbcTemplate, null, "THE_ODDS_API", "pending-without-entity", "PENDING");
        assertThatThrownBy(() -> insertLeagueMapping(
                jdbcTemplate, null, "THE_ODDS_API", "confirmed-without-entity", "MANUAL_CONFIRMED"
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertTeamMapping(
                jdbcTemplate, null, "THE_ODDS_API", "confirmed-without-entity", "MANUAL_CONFIRMED"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Order(2)
    void onlyOnePendingNormalizationConfirmationWinsConcurrentConditionalUpdate() throws Exception {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()
        ));
        flyway("19").migrate();
        long originalLeague = insertLeague(jdbcTemplate, "并发待复核原始实体");
        long firstCandidate = insertLeague(jdbcTemplate, "并发确认候选一");
        long secondCandidate = insertLeague(jdbcTemplate, "并发确认候选二");
        long mappingId = insertLeagueMapping(
                jdbcTemplate, originalLeague, "THE_ODDS_API", "concurrent-conditional-update", "PENDING"
        );
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> first = () -> confirmLeagueAfterBarrier(mappingId, firstCandidate, ready, start);
            Callable<Integer> second = () -> confirmLeagueAfterBarrier(mappingId, secondCandidate, ready, start);
            Future<Integer> firstResult = executor.submit(first);
            Future<Integer> secondResult = executor.submit(second);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(firstResult.get(10, TimeUnit.SECONDS), secondResult.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(0, 1);
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM provider_league_mappings
                WHERE id = ? AND mapping_status = 'MANUAL_CONFIRMED' AND league_id IN (?, ?)
                """, Long.class, mappingId, firstCandidate, secondCandidate)).isOne();
    }

    private Flyway flyway(String targetVersion) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(targetVersion)
                .load();
    }

    private long insertLeague(JdbcTemplate jdbcTemplate, String name) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO leagues (name_zh) VALUES (?) RETURNING id", Long.class, "T209-" + name + "-" + uniqueKey("l")
        );
    }

    private long insertTeam(JdbcTemplate jdbcTemplate, String name) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO teams (name_zh) VALUES (?) RETURNING id", Long.class, "T209-" + name + "-" + uniqueKey("t")
        );
    }

    private void insertMatch(JdbcTemplate jdbcTemplate, Long leagueId, Long homeTeamId, String suffix) {
        String key = uniqueKey("match-" + suffix);
        jdbcTemplate.update("""
                INSERT INTO matches (
                    lottery_match_no, lottery_date, league_id, home_team_id, league_name, home_team_name,
                    away_team_name, kickoff_time, match_status
                ) VALUES (?, CURRENT_DATE, ?, ?, 'T209 引用联赛', 'T209 主队', 'T209 客队', CURRENT_TIMESTAMP, 'SCHEDULED')
                """, key, leagueId, homeTeamId);
    }

    private long insertLeagueCandidate(JdbcTemplate jdbcTemplate, long leagueId, String suffix) {
        long mappingId = insertLeagueMapping(jdbcTemplate, leagueId, "THE_ODDS_API", "candidate-" + suffix, "PENDING");
        jdbcTemplate.update("""
                UPDATE provider_league_mappings
                SET external_display_name = (SELECT name_zh FROM leagues WHERE id = ?),
                    created_at = (SELECT created_at FROM leagues WHERE id = ?)
                WHERE id = ?
                """, leagueId, leagueId, mappingId);
        return mappingId;
    }

    private int confirmLeagueAfterBarrier(
            long mappingId,
            long targetLeagueId,
            CountDownLatch ready,
            CountDownLatch start
    ) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("concurrent confirmation start timed out");
        }
        try (Connection connection = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()
        ); PreparedStatement statement = connection.prepareStatement("""
                UPDATE provider_league_mappings
                SET league_id = ?, mapping_status = 'MANUAL_CONFIRMED', mapping_method = 'MANUAL_REVIEW'
                WHERE id = ? AND mapping_status = 'PENDING'
                """)) {
            statement.setLong(1, targetLeagueId);
            statement.setLong(2, mappingId);
            return statement.executeUpdate();
        }
    }

    private long insertTeamCandidate(JdbcTemplate jdbcTemplate, long teamId, String suffix) {
        long mappingId = insertTeamMapping(jdbcTemplate, teamId, "THE_ODDS_API", "candidate-" + suffix, "PENDING");
        jdbcTemplate.update("""
                UPDATE provider_team_mappings
                SET external_display_name = (SELECT name_zh FROM teams WHERE id = ?),
                    created_at = (SELECT created_at FROM teams WHERE id = ?)
                WHERE id = ?
                """, teamId, teamId, mappingId);
        return mappingId;
    }

    private long insertLeagueMapping(
            JdbcTemplate jdbcTemplate, Long leagueId, String providerCode, String externalLeagueId, String status
    ) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO provider_league_mappings (
                    league_id, provider_code, external_league_id, mapping_status, mapping_method
                ) VALUES (?, ?, ?, ?, 'NAME_CANDIDATE')
                RETURNING id
                """, Long.class, leagueId, providerCode, uniqueKey(externalLeagueId), status);
    }

    private long insertTeamMapping(
            JdbcTemplate jdbcTemplate, Long teamId, String providerCode, String externalTeamId, String status
    ) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO provider_team_mappings (
                    team_id, provider_code, external_team_id, mapping_status, mapping_method
                ) VALUES (?, ?, ?, ?, 'NAME_CANDIDATE')
                RETURNING id
                """, Long.class, teamId, providerCode, uniqueKey(externalTeamId), status);
    }

    private void assertDetachedLegacyLeagueCandidate(JdbcTemplate jdbcTemplate, long mappingId) {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM provider_league_mappings
                WHERE id = ? AND league_id IS NULL AND mapping_method = 'LEGACY_NAME_CANDIDATE_REVIEW_REQUIRED'
                """, Long.class, mappingId)).isOne();
    }

    private void assertDetachedLegacyTeamCandidate(JdbcTemplate jdbcTemplate, long mappingId) {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM provider_team_mappings
                WHERE id = ? AND team_id IS NULL AND mapping_method = 'LEGACY_NAME_CANDIDATE_REVIEW_REQUIRED'
                """, Long.class, mappingId)).isOne();
    }

    private long countById(JdbcTemplate jdbcTemplate, String tableName, long id) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName + " WHERE id = ?", Long.class, id);
    }

    private String uniqueKey(String prefix) {
        return prefix + "-" + KEY_SEQUENCE.incrementAndGet();
    }
}
