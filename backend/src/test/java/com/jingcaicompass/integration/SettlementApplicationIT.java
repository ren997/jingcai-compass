package com.jingcaicompass.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.jingcaicompass.settlement.service.SettlementService;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** T404 PostgreSQL 16 验证自动结算幂等、原子写入、审计与人工补数边界。 */
@Testcontainers
@ActiveProfiles("integration")
@SpringBootTest
class SettlementApplicationIT {

    private static final String POSTGRES_IMAGE = "postgres:16-alpine";
    private static final AtomicLong KEY_SEQUENCE = new AtomicLong(4_040_000L);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE)
            .withDatabaseName("jingcai_settlement")
            .withUsername("jingcai_test")
            .withPassword("jingcai_test");

    @DynamicPropertySource
    static void registerPostgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SettlementService settlementService;

    @BeforeEach
    void verifiesIsolatedPostgresContainer() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            String expectedPrefix = "jdbc:postgresql://"
                    + POSTGRES.getHost()
                    + ":"
                    + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
                    + "/"
                    + POSTGRES.getDatabaseName();
            assertThat(metadata.getURL()).startsWith(expectedPrefix);
            assertThat(metadata.getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(metadata.getDatabaseMajorVersion()).isEqualTo(16);
        }
    }

    @Test
    void settlesBothMarketsOnceFromLockedFactAndPreLockOfficialHandicap() {
        long matchId = insertMatch("final");
        Instant lockTime = Instant.parse("2026-07-27T10:00:00Z");
        long factId = insertFact(matchId, "FINAL", 2, 1);
        long predictionId = insertLockedPrediction(matchId, lockTime, "final");
        insertPoolSnapshot(matchId, lockTime.minusSeconds(60), "-1");

        var first = settlementService.settlePendingPredictions(10);
        var second = settlementService.settlePendingPredictions(10);

        assertThat(first.candidatePredictionCount()).isEqualTo(1);
        assertThat(first.settledPredictionCount()).isEqualTo(1);
        assertThat(first.settledMarketCount()).isEqualTo(2);
        assertThat(first.failedPredictionCount()).isZero();
        assertThat(first.manualReviewPredictionCount()).isZero();
        assertThat(second.candidatePredictionCount()).isZero();
        assertThat(currentSettlements(predictionId)).containsExactly("HAD:HIT", "HHAD:MISS");
        assertThat(singleLong("SELECT COUNT(*) FROM settlements WHERE prediction_id = ?", predictionId)).isEqualTo(2L);
        assertThat(singleLong("""
                SELECT COUNT(*)
                FROM audit_logs
                WHERE target_type = 'SETTLEMENT'
                  AND action_type = 'SETTLE'
                  AND target_id IN (SELECT CAST(id AS VARCHAR) FROM settlements WHERE prediction_id = ?)
                """, predictionId)).isEqualTo(2L);
        assertThat(singleLong("SELECT match_fact_id FROM settlements WHERE prediction_id = ? LIMIT 1", predictionId))
                .isEqualTo(factId);
    }

    @Test
    void keepsBothMarketsUnwrittenWhenFinalFactLacksPreLockOfficialHandicap() {
        long matchId = insertMatch("manual");
        Instant lockTime = Instant.parse("2026-07-27T11:00:00Z");
        insertFact(matchId, "FINAL", 1, 0);
        long predictionId = insertLockedPrediction(matchId, lockTime, "manual");

        var result = settlementService.settlePendingPredictions(10);

        assertThat(result.candidatePredictionCount()).isEqualTo(1);
        assertThat(result.settledPredictionCount()).isZero();
        assertThat(result.failedPredictionCount()).isZero();
        assertThat(result.manualReviewPredictionCount()).isEqualTo(1);
        assertThat(singleLong("SELECT COUNT(*) FROM settlements WHERE prediction_id = ?", predictionId)).isZero();
        assertThat(singleLong("""
                SELECT COUNT(*)
                FROM audit_logs
                WHERE target_type = 'SETTLEMENT'
                  AND target_id IN (SELECT CAST(id AS VARCHAR) FROM settlements WHERE prediction_id = ?)
                """, predictionId)).isZero();
    }

    @Test
    void onlyUsesLockedPredictionsAndConfirmedFactsAndSettlesOfficialVoidWithoutHandicap() {
        Instant lockTime = Instant.parse("2026-07-27T12:00:00Z");
        long unlockedMatchId = insertMatch("unlocked");
        insertFact(unlockedMatchId, "FINAL", 1, 0);
        long unlockedPredictionId = insertPublishedPrediction(unlockedMatchId, lockTime, "unlocked");

        long pendingMatchId = insertMatch("pending");
        insertFact(pendingMatchId, "PENDING", null, null);
        long pendingPredictionId = insertLockedPrediction(pendingMatchId, lockTime, "pending");

        long voidMatchId = insertMatch("void");
        long voidFactId = insertFact(voidMatchId, "VOID", null, null);
        long voidPredictionId = insertLockedPrediction(voidMatchId, lockTime, "void");

        var result = settlementService.settlePendingPredictions(10);

        assertThat(result.candidatePredictionCount()).isEqualTo(1);
        assertThat(result.settledPredictionCount()).isEqualTo(1);
        assertThat(result.settledMarketCount()).isEqualTo(2);
        assertThat(singleLong("SELECT COUNT(*) FROM settlements WHERE prediction_id = ?", unlockedPredictionId)).isZero();
        assertThat(singleLong("SELECT COUNT(*) FROM settlements WHERE prediction_id = ?", pendingPredictionId)).isZero();
        assertThat(currentSettlements(voidPredictionId)).containsExactly("HAD:VOID", "HHAD:VOID");
        assertThat(singleLong("SELECT match_fact_id FROM settlements WHERE prediction_id = ? LIMIT 1", voidPredictionId))
                .isEqualTo(voidFactId);
    }

    private long insertMatch(String suffix) {
        long key = nextKey();
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO matches (
                    lottery_match_no, lottery_date, league_name, home_team_name,
                    away_team_name, kickoff_time, match_status
                )
                VALUES (?, CURRENT_DATE, ?, 'T404 主队', 'T404 客队', CURRENT_TIMESTAMP, 'SCHEDULED')
                RETURNING id
                """,
                Long.class,
                "T404-" + suffix + "-" + key,
                "T404 " + suffix + " 联赛"
        );
    }

    private long insertFact(long matchId, String factStatus, Integer homeScore, Integer awayScore) {
        long payloadId = jdbcTemplate.queryForObject(
                """
                INSERT INTO raw_data_payloads (
                    provider_code, data_type, request_key, requested_at,
                    payload, payload_hash, parse_status
                )
                VALUES ('T404_IT', 'SPORTTERY_RESULT', ?, CURRENT_TIMESTAMP,
                        '{}'::jsonb, ?, 'SUCCESS')
                RETURNING id
                """,
                Long.class,
                "T404-result-" + nextKey(),
                String.format("%064x", nextKey())
        );
        String matchStatus = switch (factStatus) {
            case "FINAL" -> "FINISHED";
            case "VOID" -> "CANCELLED";
            default -> "POSTPONED";
        };
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO match_result_facts (
                    match_id, fact_version, fact_status, match_status,
                    home_score, away_score, raw_data_payload_id, provider_updated_at, is_current
                )
                VALUES (?, 1, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, TRUE)
                RETURNING id
                """,
                Long.class,
                matchId,
                factStatus,
                matchStatus,
                homeScore,
                awayScore,
                payloadId
        );
    }

    private long insertLockedPrediction(long matchId, Instant lockTime, String suffix) {
        return insertPrediction(matchId, lockTime, suffix, "LOCKED");
    }

    private long insertPublishedPrediction(long matchId, Instant lockTime, String suffix) {
        return insertPrediction(matchId, lockTime, suffix, "PUBLISHED");
    }

    private long insertPrediction(long matchId, Instant lockTime, String suffix, String status) {
        long key = nextKey();
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO predictions (
                    match_id, model_version, feature_version, generation_batch_id,
                    generation_batch_hash, prediction_version, home_win_prob, draw_prob,
                    away_win_prob, handicap_pick, expected_total_goals, confidence_level,
                    analysis_summary, generated_at, prediction_status, publish_time,
                    lock_time, prediction_hash
                )
                VALUES (?, 't404-model', 't404-feature', ?, ?, 1,
                        0.500000, 0.250000, 0.250000, 'HOME_WIN', 2.50, 'MEDIUM',
                        'T404 自动结算测试预测', ?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                matchId,
                "T404-batch-" + suffix + "-" + key,
                "a".repeat(64),
                lockTime.minusSeconds(120),
                status,
                lockTime.minusSeconds(60),
                lockTime,
                "b".repeat(64)
        );
    }

    private void insertPoolSnapshot(long matchId, Instant capturedAt, String officialHandicap) {
        jdbcTemplate.update(
                """
                INSERT INTO sporttery_pool_snapshots (
                    match_id, lottery_match_no, lottery_date, official_handicap,
                    captured_at, raw_payload_hash
                )
                VALUES (?, ?, CURRENT_DATE, ?, ?, ?)
                """,
                matchId,
                "T404-SNAPSHOT-" + nextKey(),
                officialHandicap,
                capturedAt,
                String.format("%064x", nextKey())
        );
    }

    private List<String> currentSettlements(long predictionId) {
        return jdbcTemplate.queryForList(
                """
                SELECT market_type || ':' || settlement_status
                FROM settlements
                WHERE prediction_id = ?
                  AND is_current
                ORDER BY market_type
                """,
                String.class,
                predictionId
        );
    }

    private Long singleLong(String sql, Object argument) {
        return jdbcTemplate.queryForObject(sql, Long.class, argument);
    }

    private long nextKey() {
        return KEY_SEQUENCE.incrementAndGet();
    }
}
