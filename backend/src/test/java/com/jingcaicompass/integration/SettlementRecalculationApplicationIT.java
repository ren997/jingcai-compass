package com.jingcaicompass.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.jingcaicompass.settlement.service.SettlementRecalculationService;
import com.jingcaicompass.settlement.service.SettlementService;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

/** T405 PostgreSQL 16 验证结算替代链、审计可追溯和并发幂等。 */
@Testcontainers
@ActiveProfiles("integration")
@SpringBootTest
class SettlementRecalculationApplicationIT {

    private static final String POSTGRES_IMAGE = "postgres:16-alpine";
    private static final AtomicLong KEY_SEQUENCE = new AtomicLong(4_050_000L);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE)
            .withDatabaseName("jingcai_settlement_recalculation")
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

    @Autowired
    private SettlementRecalculationService recalculationService;

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
        jdbcTemplate.execute("""
                TRUNCATE TABLE audit_logs, settlements, match_result_facts, sporttery_pool_snapshots,
                raw_data_payloads, predictions, matches RESTART IDENTITY CASCADE
                """);
    }

    @Test
    void preservesBothMarketHistoriesAndAuditsWhenFinalFactIsCorrected() {
        Fixture fixture = settledFixture("corrected", "FINAL", 2, 1);
        long correctedFactId = supersedeFact(fixture.matchId(), fixture.firstFactId(), 2, "FINAL", 0, 1);

        var first = recalculationService.recalculateOutdatedSettlements(10);
        var second = recalculationService.recalculateOutdatedSettlements(10);

        assertThat(first.candidatePredictionCount()).isEqualTo(1);
        assertThat(first.recalculatedPredictionCount()).isEqualTo(1);
        assertThat(first.recalculatedMarketCount()).isEqualTo(2);
        assertThat(first.failedPredictionCount()).isZero();
        assertThat(first.manualReviewPredictionCount()).isZero();
        assertThat(second.candidatePredictionCount()).isZero();
        assertThat(currentSettlements(fixture.predictionId())).containsExactly("HAD:MISS:2", "HHAD:MISS:2");
        assertThat(settlementHistory(fixture.predictionId())).containsExactly(
                "HAD:1:false:null:" + fixture.firstFactId(),
                "HAD:2:true:1:" + correctedFactId,
                "HHAD:1:false:null:" + fixture.firstFactId(),
                "HHAD:2:true:1:" + correctedFactId
        );
        assertThat(singleLong("""
                SELECT COUNT(*) FROM audit_logs
                WHERE target_type = 'SETTLEMENT'
                  AND action_type = 'SUPERSEDE'
                  AND new_value LIKE '%reason=OFFICIAL_FACT_SUPERSEDED%'
                  AND new_value LIKE ?
                """, "%matchFactId=" + correctedFactId + "%")).isEqualTo(2L);
        assertThat(singleLong("""
                SELECT COUNT(*) FROM settlements
                WHERE prediction_id = ?
                  AND is_current
                """, fixture.predictionId())).isEqualTo(2L);
    }

    @Test
    void replacesFinalSettlementsWithVoidVersionsWithoutHandicapAndRetainsOldResults() {
        Fixture fixture = settledFixture("void", "FINAL", 1, 0);
        long voidFactId = supersedeFact(fixture.matchId(), fixture.firstFactId(), 2, "VOID", null, null);

        var result = recalculationService.recalculateOutdatedSettlements(10);

        assertThat(result.recalculatedPredictionCount()).isEqualTo(1);
        assertThat(result.recalculatedMarketCount()).isEqualTo(2);
        assertThat(currentSettlements(fixture.predictionId())).containsExactly("HAD:VOID:2", "HHAD:VOID:2");
        assertThat(settlementHistory(fixture.predictionId())).containsExactly(
                "HAD:1:false:null:" + fixture.firstFactId(),
                "HAD:2:true:1:" + voidFactId,
                "HHAD:1:false:null:" + fixture.firstFactId(),
                "HHAD:2:true:1:" + voidFactId
        );
    }

    @Test
    void concurrentScansCreateExactlyOneReplacementGroup() throws Exception {
        Fixture fixture = settledFixture("concurrent", "FINAL", 2, 1);
        long correctedFactId = supersedeFact(fixture.matchId(), fixture.firstFactId(), 2, "FINAL", 0, 1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Void> scan = () -> {
                recalculationService.recalculateOutdatedSettlements(10);
                return null;
            };
            List<Future<Void>> futures = executor.invokeAll(List.of(scan, scan));
            for (Future<Void> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(singleLong("SELECT COUNT(*) FROM settlements WHERE prediction_id = ?", fixture.predictionId()))
                .isEqualTo(4L);
        assertThat(singleLong("""
                SELECT COUNT(*) FROM settlements
                WHERE prediction_id = ?
                  AND is_current
                  AND match_fact_id = ?
                """, fixture.predictionId(), correctedFactId)).isEqualTo(2L);
        assertThat(singleLong("""
                SELECT COUNT(*) FROM audit_logs
                WHERE target_type = 'SETTLEMENT'
                  AND action_type = 'SUPERSEDE'
                """)).isEqualTo(2L);
    }

    private Fixture settledFixture(String suffix, String factStatus, Integer homeScore, Integer awayScore) {
        long matchId = insertMatch(suffix);
        Instant lockTime = Instant.parse("2026-07-27T10:00:00Z");
        long factId = insertFact(matchId, 1, null, factStatus, homeScore, awayScore);
        long predictionId = insertLockedPrediction(matchId, lockTime, suffix);
        insertPoolSnapshot(matchId, lockTime.minusSeconds(60), "-1");
        var firstSettlement = settlementService.settlePendingPredictions(10);
        assertThat(firstSettlement.settledPredictionCount()).isEqualTo(1);
        return new Fixture(matchId, predictionId, factId);
    }

    private long supersedeFact(
            long matchId,
            long previousFactId,
            int newVersion,
            String factStatus,
            Integer homeScore,
            Integer awayScore
    ) {
        assertThat(jdbcTemplate.update(
                "UPDATE match_result_facts SET is_current = FALSE WHERE id = ? AND is_current = TRUE",
                previousFactId
        )).isEqualTo(1);
        return insertFact(matchId, newVersion, newVersion - 1, factStatus, homeScore, awayScore);
    }

    private long insertMatch(String suffix) {
        long key = nextKey();
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO matches (
                    lottery_match_no, lottery_date, league_name, home_team_name,
                    away_team_name, kickoff_time, match_status
                )
                VALUES (?, CURRENT_DATE, ?, 'T405 主队', 'T405 客队', CURRENT_TIMESTAMP, 'SCHEDULED')
                RETURNING id
                """,
                Long.class,
                "T405-" + suffix + "-" + key,
                "T405 " + suffix + " 联赛"
        );
    }

    private long insertFact(
            long matchId,
            int factVersion,
            Integer supersedesFactVersion,
            String factStatus,
            Integer homeScore,
            Integer awayScore
    ) {
        long payloadId = jdbcTemplate.queryForObject(
                """
                INSERT INTO raw_data_payloads (
                    provider_code, data_type, request_key, requested_at,
                    payload, payload_hash, parse_status
                )
                VALUES ('T405_IT', 'SPORTTERY_RESULT', ?, CURRENT_TIMESTAMP,
                        '{}'::jsonb, ?, 'SUCCESS')
                RETURNING id
                """,
                Long.class,
                "T405-result-" + nextKey(),
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
                    match_id, fact_version, supersedes_fact_version, fact_status, match_status,
                    home_score, away_score, raw_data_payload_id, provider_updated_at, is_current
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, TRUE)
                RETURNING id
                """,
                Long.class,
                matchId,
                factVersion,
                supersedesFactVersion,
                factStatus,
                matchStatus,
                homeScore,
                awayScore,
                payloadId
        );
    }

    private long insertLockedPrediction(long matchId, Instant lockTime, String suffix) {
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
                VALUES (?, 't405-model', 't405-feature', ?, ?, 1,
                        0.500000, 0.250000, 0.250000, 'HOME_WIN', 2.50, 'MEDIUM',
                        'T405 赛果修正重算测试预测', ?, 'LOCKED', ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                matchId,
                "T405-batch-" + suffix + "-" + key,
                "a".repeat(64),
                Timestamp.from(lockTime.minusSeconds(120)),
                Timestamp.from(lockTime.minusSeconds(60)),
                Timestamp.from(lockTime),
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
                "T405-SNAPSHOT-" + nextKey(),
                new BigDecimal(officialHandicap),
                Timestamp.from(capturedAt),
                String.format("%064x", nextKey())
        );
    }

    private List<String> currentSettlements(long predictionId) {
        return jdbcTemplate.queryForList(
                """
                SELECT market_type || ':' || settlement_status || ':' || settlement_version
                FROM settlements
                WHERE prediction_id = ?
                  AND is_current
                ORDER BY market_type
                """,
                String.class,
                predictionId
        );
    }

    private List<String> settlementHistory(long predictionId) {
        return jdbcTemplate.queryForList(
                """
                SELECT market_type || ':' || settlement_version || ':' || is_current || ':'
                       || COALESCE(supersedes_settlement_version::VARCHAR, 'null') || ':' || match_fact_id
                FROM settlements
                WHERE prediction_id = ?
                ORDER BY market_type, settlement_version
                """,
                String.class,
                predictionId
        );
    }

    private Long singleLong(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, Long.class, arguments);
    }

    private long nextKey() {
        return KEY_SEQUENCE.incrementAndGet();
    }

    private record Fixture(long matchId, long predictionId, long firstFactId) {
    }
}
