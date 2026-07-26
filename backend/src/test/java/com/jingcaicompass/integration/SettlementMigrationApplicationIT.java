package com.jingcaicompass.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jingcaicompass.match.entity.MatchResultFact;
import com.jingcaicompass.match.enums.MatchResultFactStatusEnum;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import com.jingcaicompass.match.mapper.MatchResultFactMapper;
import com.jingcaicompass.prediction.entity.Prediction;
import com.jingcaicompass.prediction.enums.ConfidenceLevelEnum;
import com.jingcaicompass.prediction.enums.HandicapPickEnum;
import com.jingcaicompass.prediction.enums.PredictionStatusEnum;
import com.jingcaicompass.prediction.mapper.PredictionMapper;
import com.jingcaicompass.settlement.entity.Settlement;
import com.jingcaicompass.settlement.enums.MarketTypeEnum;
import com.jingcaicompass.settlement.enums.SettlementStatusEnum;
import com.jingcaicompass.settlement.mapper.SettlementMapper;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL 16 验证 V10/V11 的版本链、不可变保护和查询索引。 */
@Testcontainers
@ActiveProfiles("integration")
@SpringBootTest
class SettlementMigrationApplicationIT {

    private static final String POSTGRES_IMAGE = "postgres:16-alpine";
    private static final int INDEX_DATASET_SIZE = 5_000;
    private static final AtomicLong KEY_SEQUENCE = new AtomicLong(4_010_000L);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(POSTGRES_IMAGE)
                    .withDatabaseName("jingcai_settlement_migration")
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
    private MatchResultFactMapper matchResultFactMapper;

    @Autowired
    private PredictionMapper predictionMapper;

    @Autowired
    private SettlementMapper settlementMapper;

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
    void persistsFactAndSettlementEnumsThroughMybatis() {
        long matchId = insertMatch();
        long rawPayloadId = insertResultPayload();
        MatchResultFact fact = insertFact(matchId, rawPayloadId, 1, null, true);
        Prediction prediction = insertPrediction(matchId, "mapper");
        Settlement settlement = insertSettlement(
                prediction.getId(),
                fact.getId(),
                MarketTypeEnum.HAD,
                1,
                null,
                SettlementStatusEnum.HIT,
                true
        );

        MatchResultFact savedFact = matchResultFactMapper.selectById(fact.getId());
        Settlement savedSettlement = settlementMapper.selectById(settlement.getId());
        assertThat(savedFact.getFactStatus()).isEqualTo(MatchResultFactStatusEnum.FINAL);
        assertThat(savedFact.getMatchStatus()).isEqualTo(MatchStatusEnum.FINISHED);
        assertThat(savedFact.getHomeScore()).isEqualTo(2);
        assertThat(savedSettlement.getMarketType()).isEqualTo(MarketTypeEnum.HAD);
        assertThat(savedSettlement.getSettlementStatus()).isEqualTo(SettlementStatusEnum.HIT);
        assertThat(savedSettlement.getRuleVersion()).isEqualTo("sporttery-v1");
    }

    @Test
    void preservesFactAndSettlementVersionsWhileRejectingDirectMutation() {
        long matchId = insertMatch();
        long rawPayloadId = insertResultPayload();
        MatchResultFact firstFact = insertFact(matchId, rawPayloadId, 1, null, true);

        assertThatThrownBy(() -> insertFact(matchId, rawPayloadId, 2, 1, true))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbcTemplate.update(
                "UPDATE match_result_facts SET is_current = FALSE WHERE id = ?",
                firstFact.getId()
        )).isEqualTo(1);
        MatchResultFact secondFact = insertFact(matchId, rawPayloadId, 2, 1, true);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE match_result_facts SET home_score = 9 WHERE id = ?",
                secondFact.getId()
        )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM match_result_facts WHERE id = ?",
                firstFact.getId()
        )).isInstanceOf(DataAccessException.class);

        Prediction prediction = insertPrediction(matchId, "version");
        Settlement firstSettlement = insertSettlement(
                prediction.getId(),
                secondFact.getId(),
                MarketTypeEnum.HHAD,
                1,
                null,
                SettlementStatusEnum.MISS,
                true
        );
        assertThatThrownBy(() -> insertSettlement(
                prediction.getId(),
                secondFact.getId(),
                MarketTypeEnum.HHAD,
                2,
                1,
                SettlementStatusEnum.HIT,
                true
        )).isInstanceOf(DataAccessException.class);

        assertThat(jdbcTemplate.update(
                "UPDATE settlements SET is_current = FALSE WHERE id = ?",
                firstSettlement.getId()
        )).isEqualTo(1);
        Settlement secondSettlement = insertSettlement(
                prediction.getId(),
                secondFact.getId(),
                MarketTypeEnum.HHAD,
                2,
                1,
                SettlementStatusEnum.HIT,
                true
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE settlements SET settlement_status = 'VOID' WHERE id = ?",
                secondSettlement.getId()
        )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM settlements WHERE id = ?",
                firstSettlement.getId()
        )).isInstanceOf(DataAccessException.class);
    }

    @Test
    void rejectsInvalidFactSettlementAndCrossMatchReferences() {
        long matchId = insertMatch();
        long rawPayloadId = insertResultPayload();

        assertThatThrownBy(() -> insertFact(matchId, insertPayload("SPORTTERY_POOL"), 1, null, true))
                .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO match_result_facts (
                    match_id, fact_version, fact_status, match_status,
                    home_score, away_score, raw_data_payload_id, provider_updated_at
                )
                VALUES (?, 1, 'FINAL', 'FINISHED', 1, NULL, ?, CURRENT_TIMESTAMP)
                """,
                matchId,
                rawPayloadId
        )).isInstanceOf(DataAccessException.class);

        MatchResultFact fact = insertFact(matchId, rawPayloadId, 1, null, true);
        Prediction pendingPrediction = insertPrediction(matchId, "pending");
        assertThatThrownBy(() -> insertSettlement(
                pendingPrediction.getId(),
                fact.getId(),
                MarketTypeEnum.HAD,
                1,
                null,
                SettlementStatusEnum.PENDING,
                true
        )).isInstanceOf(DataAccessException.class);

        long anotherMatchId = insertMatch();
        MatchResultFact anotherFact = insertFact(anotherMatchId, insertResultPayload(), 1, null, true);
        Prediction prediction = insertPrediction(matchId, "mismatch");
        assertThatThrownBy(() -> insertSettlement(
                prediction.getId(),
                anotherFact.getId(),
                MarketTypeEnum.HAD,
                1,
                null,
                SettlementStatusEnum.HIT,
                true
        )).isInstanceOf(DataAccessException.class);
    }

    @Test
    void rejectsLegacyScoresWhenMigratingFromV9() {
        String schema = "t401_legacy_" + nextKey();
        jdbcTemplate.execute("CREATE SCHEMA " + schema);
        try {
            Flyway.configure()
                    .dataSource(dataSource)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .createSchemas(true)
                    .locations("classpath:db/migration")
                    .target("9")
                    .load()
                    .migrate();

            jdbcTemplate.update(
                    """
                    INSERT INTO %s.matches (
                        lottery_match_no, lottery_date, league_name, home_team_name,
                        away_team_name, kickoff_time, match_status, home_score, away_score
                    )
                    VALUES ('T401-LEGACY', CURRENT_DATE, 'T401 联赛', 'T401 主队',
                            'T401 客队', CURRENT_TIMESTAMP, 'FINISHED', 2, 1)
                    """.formatted(schema)
            );

            assertThatThrownBy(() -> Flyway.configure()
                    .dataSource(dataSource)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .createSchemas(true)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate())
                    .hasStackTraceContaining("V10 refused");
        } finally {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void usesCoreIndexesForCurrentFactsCurrentSettlementsAndHistory() {
        String batchKey = "T401-INDEX-" + nextKey();
        insertIndexDataset(batchKey);

        Long targetMatchId = jdbcTemplate.queryForObject(
                """
                SELECT fact.match_id
                FROM match_result_facts fact
                JOIN predictions prediction ON prediction.match_id = fact.match_id
                WHERE prediction.generation_batch_id = ?
                  AND fact.fact_status = 'FINAL'
                ORDER BY fact.id
                OFFSET 1000 LIMIT 1
                """,
                Long.class,
                batchKey
        );
        Long targetRawPayloadId = jdbcTemplate.queryForObject(
                """
                SELECT raw_data_payload_id
                FROM match_result_facts
                WHERE match_id = ?
                  AND is_current
                """,
                Long.class,
                targetMatchId
        );
        Long targetPredictionId = jdbcTemplate.queryForObject(
                "SELECT id FROM predictions WHERE match_id = ? AND generation_batch_id = ?",
                Long.class,
                targetMatchId,
                batchKey
        );
        jdbcTemplate.update(
                "UPDATE match_result_facts SET is_current = FALSE WHERE match_id = ? AND is_current",
                targetMatchId
        );
        Long targetFactId = jdbcTemplate.queryForObject(
                """
                INSERT INTO match_result_facts (
                    match_id, fact_version, supersedes_fact_version, fact_status, match_status,
                    home_score, away_score, raw_data_payload_id, provider_updated_at, is_current
                )
                VALUES (?, 2, 1, 'FINAL', 'FINISHED', 3, 1, ?, CURRENT_TIMESTAMP, TRUE)
                RETURNING id
                """,
                Long.class,
                targetMatchId,
                targetRawPayloadId
        );
        jdbcTemplate.update(
                "UPDATE settlements SET is_current = FALSE WHERE prediction_id = ? AND market_type = 'HAD'",
                targetPredictionId
        );
        jdbcTemplate.update(
                """
                INSERT INTO settlements (
                    prediction_id, market_type, settlement_version, supersedes_settlement_version,
                    settlement_status, match_fact_id, rule_version, is_current
                )
                VALUES (?, 'HAD', 2, 1, 'HIT', ?, 'sporttery-v2', TRUE)
                """,
                targetPredictionId,
                targetFactId
        );
        jdbcTemplate.execute("ANALYZE match_result_facts");
        jdbcTemplate.execute("ANALYZE settlements");

        String currentFactPlan = explain(
                """
                SELECT id
                FROM match_result_facts
                WHERE match_id = %d
                  AND is_current
                  AND fact_status IN ('FINAL', 'VOID')
                """.formatted(targetMatchId)
        );
        String currentSettlementPlan = explain(
                """
                SELECT id
                FROM settlements
                WHERE match_fact_id = %d
                  AND is_current
                """.formatted(targetFactId)
        );
        String historyPlan = explain(
                """
                SELECT settlement_version
                FROM settlements
                WHERE prediction_id = %d
                  AND market_type = 'HAD'
                ORDER BY settlement_version DESC
                """.formatted(targetPredictionId)
        );

        assertThat(currentFactPlan).contains("idx_match_result_facts_current_eligible");
        assertThat(currentSettlementPlan).contains("idx_settlements_current_match_fact");
        assertThat(historyPlan).contains("uk_settlements_prediction_market_version");
    }

    private void insertIndexDataset(String batchKey) {
        jdbcTemplate.update(
                """
                INSERT INTO matches (
                    lottery_match_no, lottery_date, league_name, home_team_name,
                    away_team_name, kickoff_time, match_status
                )
                SELECT ? || '-' || series, CURRENT_DATE, 'T401 索引联赛', 'T401 索引主队',
                       'T401 索引客队', CURRENT_TIMESTAMP + INTERVAL '1 day', 'LOCKED'
                FROM generate_series(1, ?) AS series
                """,
                batchKey,
                INDEX_DATASET_SIZE
        );
        jdbcTemplate.update(
                """
                INSERT INTO raw_data_payloads (
                    provider_code, data_type, request_key, requested_at,
                    payload, payload_hash, parse_status
                )
                SELECT 'CHINA_SPORTTERY', 'SPORTTERY_RESULT', ? || '-' || series,
                       CURRENT_TIMESTAMP, jsonb_build_object('match', series),
                       lpad(to_hex(series), 64, '0'), 'SUCCESS'
                FROM generate_series(1, ?) AS series
                """,
                batchKey,
                INDEX_DATASET_SIZE
        );
        jdbcTemplate.update(
                """
                INSERT INTO match_result_facts (
                    match_id, fact_version, fact_status, match_status,
                    home_score, away_score, raw_data_payload_id, provider_updated_at, is_current
                )
                SELECT match_record.id,
                       1,
                       CASE WHEN match_record.id % 2 = 0 THEN 'FINAL' ELSE 'PENDING' END,
                       CASE WHEN match_record.id % 2 = 0 THEN 'FINISHED' ELSE 'LOCKED' END,
                       CASE WHEN match_record.id % 2 = 0 THEN 2 ELSE NULL END,
                       CASE WHEN match_record.id % 2 = 0 THEN 1 ELSE NULL END,
                       raw.id,
                       CURRENT_TIMESTAMP,
                       TRUE
                FROM matches match_record
                JOIN raw_data_payloads raw ON raw.request_key = match_record.lottery_match_no
                WHERE match_record.lottery_match_no LIKE ? || '-%'
                """,
                batchKey
        );
        jdbcTemplate.update(
                """
                INSERT INTO predictions (
                    match_id, model_version, feature_version, generation_batch_id,
                    generation_batch_hash, prediction_version, home_win_prob, draw_prob,
                    away_win_prob, handicap_pick, expected_total_goals, confidence_level,
                    analysis_summary, generated_at, prediction_status, publish_time,
                    lock_time, prediction_hash
                )
                SELECT id, 'model-t401-index', 'feature-t401-index', ?,
                       repeat('a', 64), 1, 0.4, 0.3, 0.3, 'HOME_WIN', 2.5, 'MEDIUM',
                       'T401 索引计划验证锁定预测', CURRENT_TIMESTAMP, 'LOCKED',
                       CURRENT_TIMESTAMP - INTERVAL '1 minute', CURRENT_TIMESTAMP,
                       repeat('b', 64)
                FROM matches
                WHERE lottery_match_no LIKE ? || '-%'
                """,
                batchKey,
                batchKey
        );
        jdbcTemplate.update(
                """
                INSERT INTO settlements (
                    prediction_id, market_type, settlement_version, settlement_status,
                    match_fact_id, rule_version, is_current
                )
                SELECT prediction.id, 'HAD', 1, 'HIT', fact.id, 'sporttery-v1', TRUE
                FROM predictions prediction
                JOIN match_result_facts fact ON fact.match_id = prediction.match_id
                WHERE prediction.generation_batch_id = ?
                  AND fact.is_current
                  AND fact.fact_status = 'FINAL'
                """,
                batchKey
        );
    }

    private MatchResultFact insertFact(
            long matchId,
            long rawPayloadId,
            int factVersion,
            Integer supersedesFactVersion,
            boolean isCurrent
    ) {
        MatchResultFact fact = new MatchResultFact();
        fact.setMatchId(matchId);
        fact.setFactVersion(factVersion);
        fact.setSupersedesFactVersion(supersedesFactVersion);
        fact.setFactStatus(MatchResultFactStatusEnum.FINAL);
        fact.setMatchStatus(MatchStatusEnum.FINISHED);
        fact.setHomeScore(2);
        fact.setAwayScore(1);
        fact.setRawDataPayloadId(rawPayloadId);
        fact.setProviderUpdatedAt(Instant.now());
        fact.setIsCurrent(isCurrent);
        assertThat(matchResultFactMapper.insert(fact)).isEqualTo(1);
        return fact;
    }

    private Settlement insertSettlement(
            long predictionId,
            long matchFactId,
            MarketTypeEnum marketType,
            int settlementVersion,
            Integer supersedesSettlementVersion,
            SettlementStatusEnum status,
            boolean isCurrent
    ) {
        Settlement settlement = new Settlement();
        settlement.setPredictionId(predictionId);
        settlement.setMarketType(marketType);
        settlement.setSettlementVersion(settlementVersion);
        settlement.setSupersedesSettlementVersion(supersedesSettlementVersion);
        settlement.setSettlementStatus(status);
        settlement.setMatchFactId(matchFactId);
        settlement.setRuleVersion("sporttery-v1");
        settlement.setIsCurrent(isCurrent);
        assertThat(settlementMapper.insert(settlement)).isEqualTo(1);
        return settlement;
    }

    private Prediction insertPrediction(long matchId, String suffix) {
        Prediction prediction = new Prediction();
        prediction.setMatchId(matchId);
        prediction.setModelVersion("model-t401-" + suffix);
        prediction.setFeatureVersion("feature-t401");
        prediction.setGenerationBatchId("batch-t401-" + nextKey());
        prediction.setGenerationBatchHash("a".repeat(64));
        prediction.setPredictionVersion(1);
        prediction.setHomeWinProb(new BigDecimal("0.400000"));
        prediction.setDrawProb(new BigDecimal("0.300000"));
        prediction.setAwayWinProb(new BigDecimal("0.300000"));
        prediction.setHandicapPick(HandicapPickEnum.HOME_WIN);
        prediction.setExpectedTotalGoals(new BigDecimal("2.50"));
        prediction.setConfidenceLevel(ConfidenceLevelEnum.MEDIUM);
        prediction.setAnalysisSummary("T401 迁移测试预测");
        prediction.setGeneratedAt(Instant.now());
        prediction.setPredictionStatus(PredictionStatusEnum.DRAFT);
        assertThat(predictionMapper.insert(prediction)).isEqualTo(1);
        return prediction;
    }

    private long insertMatch() {
        long key = nextKey();
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO matches (
                    lottery_match_no, lottery_date, league_name, home_team_name,
                    away_team_name, kickoff_time, match_status
                )
                VALUES (?, CURRENT_DATE, 'T401 联赛', 'T401 主队', 'T401 客队',
                        CURRENT_TIMESTAMP + INTERVAL '1 day', 'SCHEDULED')
                RETURNING id
                """,
                Long.class,
                "T401-" + key
        );
    }

    private long insertResultPayload() {
        return insertPayload("SPORTTERY_RESULT");
    }

    private long insertPayload(String dataType) {
        long key = nextKey();
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO raw_data_payloads (
                    provider_code, data_type, request_key, requested_at,
                    payload, payload_hash, parse_status
                )
                VALUES (?, ?, ?, CURRENT_TIMESTAMP, CAST(? AS JSONB), ?, 'SUCCESS')
                RETURNING id
                """,
                Long.class,
                "T401_PROVIDER_" + key,
                dataType,
                "T401-RESULT-" + key,
                "{\"match\":\"T401\"}",
                String.format("%064x", key)
        );
    }

    private String explain(String query) {
        List<String> rows = jdbcTemplate.queryForList("EXPLAIN (COSTS OFF) " + query, String.class);
        return String.join("\n", rows);
    }

    private long nextKey() {
        return KEY_SEQUENCE.incrementAndGet();
    }
}
