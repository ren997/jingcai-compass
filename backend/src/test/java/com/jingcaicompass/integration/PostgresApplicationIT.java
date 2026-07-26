package com.jingcaicompass.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jingcaicompass.prediction.entity.Prediction;
import com.jingcaicompass.prediction.enums.ConfidenceLevelEnum;
import com.jingcaicompass.prediction.enums.HandicapPickEnum;
import com.jingcaicompass.prediction.enums.PredictionStatusEnum;
import com.jingcaicompass.prediction.mapper.PredictionMapper;
import com.jingcaicompass.snapshot.entity.PredictionSnapshot;
import com.jingcaicompass.snapshot.enums.PredictionSnapshotStatusEnum;
import com.jingcaicompass.snapshot.mapper.PredictionSnapshotMapper;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL 16 空库集成验证：完整启动持久化上下文，并验证 V1～V8 与数据库原生行为。
 */
@Testcontainers
@ActiveProfiles("integration")
@SpringBootTest
class PostgresApplicationIT {

    private static final String POSTGRES_IMAGE = "postgres:16-alpine";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(POSTGRES_IMAGE)
                    .withDatabaseName("jingcai_integration")
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
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private PredictionMapper predictionMapper;

    @Autowired
    private PredictionSnapshotMapper predictionSnapshotMapper;

    @BeforeEach
    void verifiesIsolatedContainerDataSource() throws Exception {
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
    void appliesAllMigrationsAndLoadsExpectedSchema() {
        MigrationInfo[] applied = Arrays.stream(flyway.info().applied())
                .filter(info -> info.getVersion() != null)
                .toArray(MigrationInfo[]::new);

        assertThat(applied).hasSize(8);
        assertThat(applied[applied.length - 1].getVersion().getVersion()).isEqualTo("8");
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.migrate().migrationsExecuted).isZero();

        List<String> tables = jdbcTemplate.queryForList(
                """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                """,
                String.class
        );
        assertThat(tables).contains(
                "data_providers",
                "raw_data_payloads",
                "data_sync_runs",
                "leagues",
                "teams",
                "matches",
                "provider_league_mappings",
                "provider_team_mappings",
                "match_source_mappings",
                "sporttery_pool_snapshots",
                "asian_odds_snapshots",
                "league_aliases",
                "team_aliases",
                "audit_logs",
                "predictions",
                "prediction_snapshots",
                "admin_accounts"
        );

        List<String> mappingColumns = jdbcTemplate.queryForList(
                """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'match_source_mappings'
                """,
                String.class
        );
        assertThat(mappingColumns).contains("mapping_explanation", "mapping_candidates");
    }

    @Test
    void enforcesJsonbUniqueAndCheckConstraints() {
        String providerCode = "T006_JSONB";
        String payloadHash = "a".repeat(64);
        jdbcTemplate.update(
                """
                INSERT INTO raw_data_payloads (
                    provider_code,
                    data_type,
                    request_key,
                    requested_at,
                    payload,
                    payload_hash,
                    parse_status
                )
                VALUES (?, 'OTHER', 't006-jsonb', CURRENT_TIMESTAMP, CAST(? AS JSONB), ?, 'SUCCESS')
                """,
                providerCode,
                "{\"source\":\"t006\",\"valid\":true}",
                payloadHash
        );

        String source = jdbcTemplate.queryForObject(
                """
                SELECT payload ->> 'source'
                FROM raw_data_payloads
                WHERE provider_code = ?
                """,
                String.class,
                providerCode
        );
        assertThat(source).isEqualTo("t006");

        jdbcTemplate.update(
                """
                INSERT INTO data_providers (provider_code, provider_name, category)
                VALUES ('T006_UNIQUE', 'T006 provider', 'OTHER')
                """
        );
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO data_providers (provider_code, provider_name, category)
                VALUES ('T006_UNIQUE', 'Duplicate provider', 'OTHER')
                """
        )).isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO data_providers (provider_code, provider_name, category)
                VALUES ('T006_INVALID_CATEGORY', 'Invalid provider', 'INVALID')
                """
        )).isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO matches (
                    lottery_match_no,
                    lottery_date,
                    league_name,
                    home_team_name,
                    away_team_name,
                    kickoff_time,
                    match_status,
                    home_score
                )
                VALUES (
                    'T006-NEGATIVE',
                    DATE '2026-07-25',
                    'T006 League',
                    'T006 Home',
                    'T006 Away',
                    TIMESTAMPTZ '2026-07-25 12:00:00+08',
                    'FINISHED',
                    -1
                )
                """
        )).isInstanceOf(DataAccessException.class);
    }

    @Test
    void preservesTimestamptzAndRollsBackFailedTransactions() {
        OffsetDateTime expected = OffsetDateTime.parse("2026-07-25T12:34:56.123456+08:00");
        OffsetDateTime actual = jdbcTemplate.queryForObject(
                "SELECT CAST(? AS TIMESTAMPTZ)",
                (resultSet, rowNumber) -> resultSet.getObject(1, OffsetDateTime.class),
                expected
        );
        assertThat(actual).isNotNull();
        assertThat(actual.toInstant()).isEqualTo(expected.toInstant());

        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update(
                    """
                    INSERT INTO data_providers (provider_code, provider_name, category)
                    VALUES ('T006_ROLLBACK', 'Rollback provider', 'OTHER')
                    """
            );
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM data_providers WHERE provider_code = 'T006_ROLLBACK'",
                Integer.class
        );
        assertThat(count).isZero();
    }

    @Test
    void persistsPredictionAndSnapshotEnumsThroughMybatis() {
        Long matchId = insertMatch("T301-MAPPER");
        Prediction prediction = new Prediction();
        prediction.setMatchId(matchId);
        prediction.setModelVersion("model-mapper-v1");
        prediction.setFeatureVersion("feature-mapper-v1");
        prediction.setGenerationBatchId("batch-mapper-v1");
        prediction.setGenerationBatchHash("a".repeat(64));
        prediction.setPredictionVersion(1);
        prediction.setHomeWinProb(new BigDecimal("0.400000"));
        prediction.setDrawProb(new BigDecimal("0.300000"));
        prediction.setAwayWinProb(new BigDecimal("0.300000"));
        prediction.setHandicapPick(HandicapPickEnum.HOME_WIN);
        prediction.setExpectedTotalGoals(new BigDecimal("2.50"));
        prediction.setConfidenceLevel(ConfidenceLevelEnum.HIGH);
        prediction.setAnalysisSummary("T301 Mapper 枚举往返验证");
        prediction.setGeneratedAt(Instant.parse("2026-07-26T01:00:00Z"));
        prediction.setPredictionStatus(PredictionStatusEnum.DRAFT);

        assertThat(predictionMapper.insert(prediction)).isEqualTo(1);
        Prediction savedPrediction = predictionMapper.selectById(prediction.getId());
        assertThat(savedPrediction.getPredictionStatus()).isEqualTo(PredictionStatusEnum.DRAFT);
        assertThat(savedPrediction.getHandicapPick()).isEqualTo(HandicapPickEnum.HOME_WIN);
        assertThat(savedPrediction.getConfidenceLevel()).isEqualTo(ConfidenceLevelEnum.HIGH);
        assertThat(savedPrediction.getHomeWinProb()).isEqualByComparingTo("0.400000");

        PredictionSnapshot snapshot = new PredictionSnapshot();
        snapshot.setSnapshotDate(LocalDate.of(2026, 8, 1));
        snapshot.setSnapshotVersion(1);
        snapshot.setSnapshotStatus(PredictionSnapshotStatusEnum.PENDING);

        assertThat(predictionSnapshotMapper.insert(snapshot)).isEqualTo(1);
        PredictionSnapshot savedSnapshot = predictionSnapshotMapper.selectById(snapshot.getId());
        assertThat(savedSnapshot.getSnapshotStatus())
                .isEqualTo(PredictionSnapshotStatusEnum.PENDING);
    }

    @Test
    void enforcesPredictionProbabilityVersionStatusAndHashConstraints() {
        Long matchId = insertMatch("T301-PREDICTION-CONSTRAINT");

        insertDraftPrediction(matchId, "model-constraint-v1", "batch-lower-bound", 1,
                "0.000000", "0.000000", "1.000000");
        insertDraftPrediction(matchId, "model-constraint-v1", "batch-lower-tolerance", 2,
                "0.333333", "0.333333", "0.333333");
        insertDraftPrediction(matchId, "model-constraint-v1", "batch-upper-tolerance", 3,
                "0.333334", "0.333334", "0.333333");

        Integer historyCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM predictions
                WHERE match_id = ?
                  AND model_version = 'model-constraint-v1'
                """,
                Integer.class,
                matchId
        );
        assertThat(historyCount).isEqualTo(3);

        assertThatThrownBy(() -> insertDraftPrediction(
                matchId,
                "model-constraint-v1",
                "batch-out-of-range",
                4,
                "-0.000001",
                "0.500000",
                "0.500001"
        )).isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> insertDraftPrediction(
                matchId,
                "model-constraint-v1",
                "batch-invalid-sum",
                5,
                "0.333332",
                "0.333332",
                "0.333332"
        )).isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> insertDraftPrediction(
                matchId,
                "model-constraint-v1",
                "batch-invalid-version",
                0,
                "0.400000",
                "0.300000",
                "0.300000"
        )).isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> insertDraftPrediction(
                matchId,
                "model-constraint-v1",
                "batch-duplicate-version",
                1,
                "0.400000",
                "0.300000",
                "0.300000"
        )).isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> insertDraftPrediction(
                matchId,
                "model-constraint-v1",
                "batch-lower-bound",
                6,
                "0.400000",
                "0.300000",
                "0.300000"
        )).isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO predictions (
                    match_id,
                    model_version,
                    feature_version,
                    generation_batch_id,
                    generation_batch_hash,
                    prediction_version,
                    home_win_prob,
                    draw_prob,
                    away_win_prob,
                    handicap_pick,
                    expected_total_goals,
                    confidence_level,
                    analysis_summary,
                    generated_at,
                    prediction_status
                )
                VALUES (?, 'model-invalid-status', 'feature-v1', 'batch-invalid-status', ?,
                        1, 0.4, 0.3, 0.3, 'DRAW', 2.25, 'MEDIUM', 'invalid status',
                        TIMESTAMPTZ '2026-07-26 01:00:00+00', 'INVALID')
                """,
                matchId,
                "b".repeat(64)
        )).isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> insertPublishedPrediction(
                matchId,
                "model-invalid-hash",
                "batch-invalid-hash",
                "invalid",
                "2026-07-26 02:00:00+00",
                "2026-07-26 03:00:00+00"
        )).isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> insertPublishedPrediction(
                matchId,
                "model-invalid-time",
                "batch-invalid-time",
                "c".repeat(64),
                "2026-07-26 04:00:00+00",
                "2026-07-26 03:00:00+00"
        )).isInstanceOf(DataAccessException.class);
    }

    @Test
    void enforcesPredictionSnapshotVersionAndLifecycleConstraints() {
        jdbcTemplate.update(
                """
                INSERT INTO prediction_snapshots (
                    snapshot_date,
                    snapshot_version,
                    snapshot_status
                )
                VALUES (DATE '2026-07-26', 1, 'PENDING')
                """
        );
        jdbcTemplate.update(
                """
                INSERT INTO prediction_snapshots (
                    snapshot_date,
                    snapshot_version,
                    snapshot_status,
                    snapshot_hash,
                    storage_type,
                    object_key,
                    file_url,
                    content_type,
                    content_length,
                    published_at
                )
                VALUES (
                    DATE '2026-07-26',
                    2,
                    'PUBLISHED',
                    ?,
                    'LOCAL',
                    '2026-07-26/v2.json',
                    'file:///runtime/snapshots/2026-07-26/v2.json',
                    'application/json',
                    128,
                    TIMESTAMPTZ '2026-07-26 05:00:00+00'
                )
                """,
                "d".repeat(64)
        );
        jdbcTemplate.update(
                """
                INSERT INTO prediction_snapshots (
                    snapshot_date,
                    snapshot_version,
                    snapshot_status,
                    failure_reason
                )
                VALUES (DATE '2026-07-26', 3, 'FAILED', 'storage unavailable')
                """
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO prediction_snapshots (
                    snapshot_date,
                    snapshot_version,
                    snapshot_status
                )
                VALUES (DATE '2026-07-26', 2, 'PENDING')
                """
        )).isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO prediction_snapshots (
                    snapshot_date,
                    snapshot_version,
                    snapshot_status
                )
                VALUES (DATE '2026-07-27', 0, 'PENDING')
                """
        )).isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO prediction_snapshots (
                    snapshot_date,
                    snapshot_version,
                    snapshot_status
                )
                VALUES (DATE '2026-07-27', 1, 'UNKNOWN')
                """
        )).isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO prediction_snapshots (
                    snapshot_date,
                    snapshot_version,
                    snapshot_status,
                    snapshot_hash,
                    published_at
                )
                VALUES (
                    DATE '2026-07-27',
                    2,
                    'PUBLISHED',
                    ?,
                    TIMESTAMPTZ '2026-07-26 05:00:00+00'
                )
                """,
                "e".repeat(64)
        )).isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO prediction_snapshots (
                    snapshot_date,
                    snapshot_version,
                    snapshot_status,
                    published_at,
                    failure_reason
                )
                VALUES (
                    DATE '2026-07-27',
                    3,
                    'FAILED',
                    TIMESTAMPTZ '2026-07-26 05:00:00+00',
                    'must not be published'
                )
                """
        )).isInstanceOf(DataAccessException.class);
    }

    private Long insertMatch(String lotteryMatchNo) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO matches (
                    lottery_match_no,
                    lottery_date,
                    league_name,
                    home_team_name,
                    away_team_name,
                    kickoff_time,
                    match_status
                )
                VALUES (
                    ?,
                    DATE '2026-07-26',
                    'T301 League',
                    'T301 Home',
                    'T301 Away',
                    TIMESTAMPTZ '2026-07-27 12:00:00+08',
                    'SCHEDULED'
                )
                RETURNING id
                """,
                Long.class,
                lotteryMatchNo
        );
    }

    private void insertDraftPrediction(
            Long matchId,
            String modelVersion,
            String generationBatchId,
            int predictionVersion,
            String homeWinProb,
            String drawProb,
            String awayWinProb
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO predictions (
                    match_id,
                    model_version,
                    feature_version,
                    generation_batch_id,
                    generation_batch_hash,
                    prediction_version,
                    home_win_prob,
                    draw_prob,
                    away_win_prob,
                    handicap_pick,
                    expected_total_goals,
                    confidence_level,
                    analysis_summary,
                    generated_at,
                    prediction_status
                )
                VALUES (?, ?, 'feature-v1', ?, ?, ?, ?, ?, ?, 'HOME_WIN', 2.50, 'HIGH',
                        'T301 constraint verification', TIMESTAMPTZ '2026-07-26 01:00:00+00',
                        'DRAFT')
                """,
                matchId,
                modelVersion,
                generationBatchId,
                "a".repeat(64),
                predictionVersion,
                new BigDecimal(homeWinProb),
                new BigDecimal(drawProb),
                new BigDecimal(awayWinProb)
        );
    }

    private void insertPublishedPrediction(
            Long matchId,
            String modelVersion,
            String generationBatchId,
            String predictionHash,
            String publishTime,
            String lockTime
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO predictions (
                    match_id,
                    model_version,
                    feature_version,
                    generation_batch_id,
                    generation_batch_hash,
                    prediction_version,
                    home_win_prob,
                    draw_prob,
                    away_win_prob,
                    handicap_pick,
                    expected_total_goals,
                    confidence_level,
                    analysis_summary,
                    generated_at,
                    prediction_status,
                    publish_time,
                    lock_time,
                    prediction_hash
                )
                VALUES (?, ?, 'feature-v1', ?, ?, 1, 0.4, 0.3, 0.3, 'DRAW', 2.25, 'MEDIUM',
                        'T301 published constraint verification',
                        TIMESTAMPTZ '2026-07-26 01:00:00+00',
                        'PUBLISHED',
                        CAST(? AS TIMESTAMPTZ),
                        CAST(? AS TIMESTAMPTZ),
                        ?)
                """,
                matchId,
                modelVersion,
                generationBatchId,
                "f".repeat(64),
                publishTime,
                lockTime,
                predictionHash
        );
    }
}
