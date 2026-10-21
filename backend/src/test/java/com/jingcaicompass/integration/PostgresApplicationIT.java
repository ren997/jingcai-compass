package com.jingcaicompass.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jingcaicompass.prediction.entity.Prediction;
import com.jingcaicompass.prediction.enums.AsianHandicapPickEnum;
import com.jingcaicompass.prediction.enums.ConfidenceLevelEnum;
import com.jingcaicompass.prediction.enums.HandicapPickEnum;
import com.jingcaicompass.prediction.enums.PredictionStatusEnum;
import com.jingcaicompass.prediction.enums.PredictionTypeEnum;
import com.jingcaicompass.prediction.enums.TotalGoalsPickEnum;
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
 * PostgreSQL 16 空库集成验证：完整启动持久化上下文，并验证 V1～V25 与数据库原生行为。
 */
@Testcontainers
@ActiveProfiles("integration")
@SpringBootTest
class PostgresApplicationIT {

    private static final String POSTGRES_IMAGE = "postgres:16-alpine";

    /**
     * V24 的十列模型输出。逐列断言发布后不可变——漏掉任何一列，就等于允许事后改写
     * 已发布预测背后的概率，而 ROI 与 Brier 正是拿这些概率计账的。
     */
    private static final List<String> ASIAN_MODEL_OUTPUT_COLUMNS = List.of(
            "lambda_home", "lambda_away",
            "model_cover_prob", "market_cover_prob", "edge_cover",
            "model_over_prob", "market_over_prob", "edge_over",
            "handicap_dispersion", "totals_dispersion"
    );

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

        assertThat(applied).hasSize(25);
        assertThat(applied[applied.length - 1].getVersion().getVersion()).isEqualTo("25");
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
                "data_sync_run_payloads",
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
                "admin_accounts",
                "match_result_facts",
                "settlements"
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
        assertThat(mappingColumns).contains("mapping_explanation", "mapping_candidates", "external_kickoff_time");

        List<String> providerLeagueMappingColumns = jdbcTemplate.queryForList(
                """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'provider_league_mappings'
                """,
                String.class
        );
        List<String> providerTeamMappingColumns = jdbcTemplate.queryForList(
                """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'provider_team_mappings'
                """,
                String.class
        );
        assertThat(providerLeagueMappingColumns).contains(
                "external_display_name", "external_normalized_key", "external_scope");
        assertThat(providerTeamMappingColumns).contains(
                "external_display_name", "external_normalized_key", "external_scope");

        List<String> predictionColumns = jdbcTemplate.queryForList(
                """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'predictions'
                """,
                String.class
        );
        assertThat(predictionColumns).contains(
                "prediction_type", "asian_odds_snapshot_id", "asian_handicap_pick", "total_goals_pick",
                "asian_handicap_confidence_level", "total_goals_confidence_level",
                "lambda_home", "lambda_away",
                "model_cover_prob", "market_cover_prob", "edge_cover",
                "model_over_prob", "market_over_prob", "edge_over",
                "handicap_dispersion", "totals_dispersion",
                "publish_attempt_count", "last_publish_error"
        );

        // V25：单源改造依赖的三处结构。缺任何一处，阶段 1/2/4 都会在真库上才暴露。
        assertThat(columnsOf("matches")).contains("external_match_id");
        assertThat(columnsOf("match_result_facts")).contains("score_scope");
        assertThat(columnsOf("asian_odds_snapshots")).contains("total_line");
    }

    private List<String> columnsOf(String tableName) {
        return jdbcTemplate.queryForList(
                """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = ?
                """,
                String.class,
                tableName
        );
    }

    /**
     * V25 的三条结构承诺，逐条验证——它们都属于「错了不报错、只会长期静默跑偏」的类型。
     *
     * <ol>
     *   <li>{@code matches.external_match_id} 是<b>部分</b>唯一索引：多行 NULL 必须共存
     *       （仓库内上百处测试直接插入 matches 且不带该列），非空值必须唯一。
     *       若误建成普通唯一索引，PostgreSQL 允许 NULL 并存，行为碰巧一致，
     *       但换成 NOT NULL 就会让整批存量测试同时变红。</li>
     *   <li>{@code publish_attempt_count} / {@code last_publish_error} 在 PUBLISHED 之后
     *       <b>必须仍可更新</b>。它们是发布动作的副产品，不是预测内容。若被误加入
     *       {@code protect_published_prediction()} 的 ROW 列表，第一次自动发布就会失败，
     *       而且要到阶段 3 才会暴露。</li>
     *   <li>{@code score_scope} 必须拒绝加时与点球口径——亚盘结算只认 90 分钟，
     *       靠「源里恰好没有加时比分」来保证是概率，不是约束。</li>
     * </ol>
     */
    @Test
    void enforcesNovaSingleSourceStructures() {
        long matchId = jdbcTemplate.queryForObject(
                """
                INSERT INTO matches (
                    lottery_match_no, lottery_date, league_name, home_team_name, away_team_name,
                    kickoff_time, match_status, external_match_id
                )
                VALUES ('T613-NOVA-001', DATE '2036-02-01', 'V25 League', 'V25 Home', 'V25 Away',
                        TIMESTAMPTZ '2036-02-01 12:00:00+08', 'SCHEDULED', '2042108')
                RETURNING id
                """,
                Long.class
        );

        // 1a) 同一个 external_match_id 不能出现第二次。
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO matches (
                    lottery_match_no, lottery_date, league_name, home_team_name, away_team_name,
                    kickoff_time, match_status, external_match_id
                )
                VALUES ('T613-NOVA-002', DATE '2036-02-01', 'V25 League', 'V25 Home 2', 'V25 Away 2',
                        TIMESTAMPTZ '2036-02-01 13:00:00+08', 'SCHEDULED', '2042108')
                """))
                .isInstanceOf(DataAccessException.class);

        // 1b) 不带 external_match_id 的存量写法必须还能插入（部分唯一索引允许 NULL 并存）。
        jdbcTemplate.update(
                """
                INSERT INTO matches (
                    lottery_match_no, lottery_date, league_name, home_team_name, away_team_name,
                    kickoff_time, match_status
                )
                VALUES ('T613-NOVA-003', DATE '2036-02-01', 'V25 League', 'V25 Home 3', 'V25 Away 3',
                        TIMESTAMPTZ '2036-02-01 14:00:00+08', 'SCHEDULED')
                """);

        // 2) 发布诊断列在 PUBLISHED 之后必须可写。
        insertPublishedPrediction(
                matchId,
                "t613-v25-model",
                "t613-v25-batch",
                "c".repeat(64),
                "2036-02-01 10:00:00+08",
                "2036-02-01 11:00:00+08"
        );
        int updated = jdbcTemplate.update(
                """
                UPDATE predictions
                SET publish_attempt_count = publish_attempt_count + 1,
                    last_publish_error = 'sample diagnostic'
                WHERE match_id = ?
                """,
                matchId
        );
        assertThat(updated).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT publish_attempt_count FROM predictions WHERE match_id = ?",
                Integer.class,
                matchId
        )).isEqualTo(1);

        // 3) score_scope 拒绝非 90 分钟口径。赛果事实需要合法的 raw payload，
        //    这里只验证 CHECK 约束本身，用 VOID 事实（不要求比分）走最小路径。
        long payloadId = jdbcTemplate.queryForObject(
                """
                INSERT INTO raw_data_payloads (
                    provider_code, data_type, request_key, requested_at, payload, payload_hash, parse_status
                )
                VALUES ('NOVA_API', 'SPORTTERY_RESULT', 'v25-score-scope', TIMESTAMPTZ '2036-02-01 12:00:00+08',
                        '{}'::jsonb, ?, 'SUCCESS')
                RETURNING id
                """,
                Long.class,
                "d".repeat(64)
        );
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO match_result_facts (
                    match_id, fact_version, fact_status, match_status,
                    raw_data_payload_id, provider_updated_at, score_scope
                )
                VALUES (?, 1, 'VOID', 'CANCELLED', ?, TIMESTAMPTZ '2036-02-01 12:00:00+08', 'INCLUDING_EXTRA_TIME')
                """,
                matchId,
                payloadId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_match_result_facts_score_scope");

        // 3b) 同一份非 SPORTTERY_RESULT 来源的载荷仍然必须被拒（V18 的口径未被放宽）。
        //     用 fact_version = 1：上一条已被 CHECK 拒绝、没有落库，版本链仍是空的。
        long wrongPayloadId = jdbcTemplate.queryForObject(
                """
                INSERT INTO raw_data_payloads (
                    provider_code, data_type, request_key, requested_at, payload, payload_hash, parse_status
                )
                VALUES ('NOVA_API', 'ASIAN_ODDS', 'v25-wrong-source', TIMESTAMPTZ '2036-02-01 12:00:00+08',
                        '{}'::jsonb, ?, 'SUCCESS')
                RETURNING id
                """,
                Long.class,
                "e".repeat(64)
        );
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO match_result_facts (
                    match_id, fact_version, fact_status, match_status,
                    raw_data_payload_id, provider_updated_at
                )
                VALUES (?, 1, 'VOID', 'CANCELLED', ?, TIMESTAMPTZ '2036-02-01 13:00:00+08')
                """,
                matchId,
                wrongPayloadId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_match_result_facts_source_payload");
    }

    @Test
    void enforcesAsianPredictionCompletenessConfidenceAndPublishedImmutability() {
        long firstMatchId = jdbcTemplate.queryForObject(
                """
                INSERT INTO matches (
                    lottery_match_no, lottery_date, league_name, home_team_name, away_team_name,
                    kickoff_time, match_status
                )
                VALUES ('T610-001', DATE '2036-01-01', 'T610 League', 'T610 Home', 'T610 Away',
                        TIMESTAMPTZ '2036-01-01 12:00:00+08', 'SCHEDULED')
                RETURNING id
                """,
                Long.class
        );
        long asianSnapshotId = jdbcTemplate.queryForObject(
                """
                INSERT INTO asian_odds_snapshots (
                    match_id, provider_code, bookmaker_code, handicap_line, home_odds, away_odds,
                    total_line, over_odds, under_odds, snapshot_type, captured_at, raw_payload_hash
                )
                VALUES (?, 'T610_ASIAN', 'T610_BOOK', -0.50, 1.85, 1.95, 2.50, 1.82, 2.02,
                        'PRE_KICKOFF', TIMESTAMPTZ '2036-01-01 10:00:00+08', ?)
                RETURNING id
                """,
                Long.class,
                firstMatchId,
                "b".repeat(64)
        );

        long predictionId = jdbcTemplate.queryForObject(
                """
                INSERT INTO predictions (
                    match_id, model_version, feature_version, generation_batch_id, generation_batch_hash,
                    prediction_version, prediction_type, asian_odds_snapshot_id, asian_handicap_pick,
                    total_goals_pick, asian_handicap_confidence_level, total_goals_confidence_level,
                    analysis_summary, generated_at
                )
                VALUES (?, 't306-asian-baseline-v3', 't306-asian-mainline-v3', 'T610-BATCH', ?, 1,
                        'ASIAN', ?, 'HOME_COVER', 'UNDER', 'HIGH', 'MEDIUM',
                        'T610 亚盘可解释基线。', TIMESTAMPTZ '2036-01-01 10:01:00+08')
                RETURNING id
                """,
                Long.class,
                firstMatchId,
                "c".repeat(64),
                asianSnapshotId
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO predictions (
                    match_id, model_version, feature_version, generation_batch_id, generation_batch_hash,
                    prediction_version, prediction_type, asian_odds_snapshot_id, asian_handicap_pick,
                    asian_handicap_confidence_level, analysis_summary, generated_at
                )
                VALUES (?, 'model-low', 'feature-low', 'T610-LOW', ?, 1,
                        'ASIAN', ?, 'HOME_COVER', 'LOW',
                        '低置信亚盘预测。', TIMESTAMPTZ '2036-01-01 10:01:00+08')
                """,
                firstMatchId,
                "d".repeat(64),
                asianSnapshotId
        )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO predictions (
                    match_id, model_version, feature_version, generation_batch_id, generation_batch_hash,
                    prediction_version, prediction_type, asian_odds_snapshot_id, asian_handicap_pick,
                    analysis_summary, generated_at
                )
                VALUES (?, 'model-missing-confidence', 'feature-missing-confidence', 'T610-MISSING', ?, 1,
                        'ASIAN', ?, 'HOME_COVER',
                        '缺置信度的亚盘预测。', TIMESTAMPTZ '2036-01-01 10:01:00+08')
                """,
                firstMatchId,
                "e".repeat(64),
                asianSnapshotId
        )).isInstanceOf(DataAccessException.class);

        jdbcTemplate.update(
                """
                UPDATE predictions
                SET prediction_status = 'PUBLISHED',
                    publish_time = TIMESTAMPTZ '2036-01-01 10:02:00+08',
                    lock_time = TIMESTAMPTZ '2036-01-01 11:00:00+08',
                    prediction_hash = ?
                WHERE id = ?
                """,
                "f".repeat(64),
                predictionId
        );
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE predictions SET asian_handicap_confidence_level = 'MEDIUM' WHERE id = ?",
                predictionId
        )).isInstanceOf(DataAccessException.class);
    }

    @Test
    void storesAsianModelOutputAndProtectsEveryColumnAfterPublish() {
        long matchId = jdbcTemplate.queryForObject(
                """
                INSERT INTO matches (
                    lottery_match_no, lottery_date, league_name, home_team_name, away_team_name,
                    kickoff_time, match_status
                )
                VALUES ('T613-E01', DATE '2036-02-01', 'T613 League', 'T613 Home', 'T613 Away',
                        TIMESTAMPTZ '2036-02-01 12:00:00+08', 'SCHEDULED')
                RETURNING id
                """,
                Long.class
        );
        long asianSnapshotId = jdbcTemplate.queryForObject(
                """
                INSERT INTO asian_odds_snapshots (
                    match_id, provider_code, bookmaker_code, handicap_line, home_odds, away_odds,
                    total_line, over_odds, under_odds, snapshot_type, captured_at, raw_payload_hash
                )
                VALUES (?, 'T613_E_ASIAN', 'T613_E_BOOK', -0.25, 1.90, 1.90, 2.75, 1.95, 1.85,
                        'PRE_KICKOFF', TIMESTAMPTZ '2036-02-01 10:00:00+08', ?)
                RETURNING id
                """,
                Long.class,
                matchId,
                "1".repeat(64)
        );
        long predictionId = jdbcTemplate.queryForObject(
                """
                INSERT INTO predictions (
                    match_id, model_version, feature_version, generation_batch_id, generation_batch_hash,
                    prediction_version, prediction_type, asian_odds_snapshot_id, asian_handicap_pick,
                    total_goals_pick, asian_handicap_confidence_level, total_goals_confidence_level,
                    analysis_summary, generated_at,
                    lambda_home, lambda_away,
                    model_cover_prob, market_cover_prob, edge_cover,
                    model_over_prob, market_over_prob, edge_over,
                    handicap_dispersion, totals_dispersion
                )
                VALUES (?, 't306-asian-baseline-v6', 't306-asian-mainline-v6', 'T613-E-BATCH', ?, 1,
                        'ASIAN', ?, 'HOME_COVER', 'OVER', 'HIGH', 'MEDIUM',
                        'T613 阶段 E 亚盘概率落库。', TIMESTAMPTZ '2036-02-01 10:01:00+08',
                        1.250000, 0.950000,
                        0.512345, 0.512345, 0.000000,
                        0.487500, 0.450000, 0.037500,
                        0.011800, 0.010500)
                RETURNING id
                """,
                Long.class,
                matchId,
                "2".repeat(64),
                asianSnapshotId
        );

        // 携带模型输出正常发布，不能被两处 ROW 比较误伤。
        jdbcTemplate.update(
                """
                UPDATE predictions
                SET prediction_status = 'PUBLISHED',
                    publish_time = TIMESTAMPTZ '2036-02-01 10:02:00+08',
                    lock_time = TIMESTAMPTZ '2036-02-01 11:00:00+08',
                    prediction_hash = ?
                WHERE id = ?
                """,
                "3".repeat(64),
                predictionId
        );
        assertThat(jdbcTemplate.queryForObject(
                "SELECT lambda_home FROM predictions WHERE id = ?",
                BigDecimal.class,
                predictionId
        )).isEqualByComparingTo("1.250000");

        for (String column : ASIAN_MODEL_OUTPUT_COLUMNS) {
            assertThatThrownBy(() -> jdbcTemplate.update(
                    "UPDATE predictions SET " + column + " = 0.500000 WHERE id = ?",
                    predictionId
            ))
                    .as("published column %s must be immutable", column)
                    .isInstanceOf(DataAccessException.class);
        }
    }

    @Test
    void rejectsAsianModelOutputViolatingRangePairAndTypeRules() {
        long matchId = jdbcTemplate.queryForObject(
                """
                INSERT INTO matches (
                    lottery_match_no, lottery_date, league_name, home_team_name, away_team_name,
                    kickoff_time, match_status
                )
                VALUES ('T613-E02', DATE '2036-02-02', 'T613 League', 'T613 Home', 'T613 Away',
                        TIMESTAMPTZ '2036-02-02 12:00:00+08', 'SCHEDULED')
                RETURNING id
                """,
                Long.class
        );
        long asianSnapshotId = jdbcTemplate.queryForObject(
                """
                INSERT INTO asian_odds_snapshots (
                    match_id, provider_code, bookmaker_code, handicap_line, home_odds, away_odds,
                    total_line, over_odds, under_odds, snapshot_type, captured_at, raw_payload_hash
                )
                VALUES (?, 'T613_E_ASIAN', 'T613_E_BOOK', -0.25, 1.90, 1.90, 2.75, 1.95, 1.85,
                        'PRE_KICKOFF', TIMESTAMPTZ '2036-02-02 10:00:00+08', ?)
                RETURNING id
                """,
                Long.class,
                matchId,
                "4".repeat(64)
        );

        // 半套 λ：两列必须同生同灭，否则反解结果无法复现。
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO predictions (
                    match_id, model_version, feature_version, generation_batch_id, generation_batch_hash,
                    prediction_version, prediction_type, asian_odds_snapshot_id, asian_handicap_pick,
                    total_goals_pick, asian_handicap_confidence_level, total_goals_confidence_level,
                    analysis_summary, generated_at, lambda_home
                )
                VALUES (?, 't306-asian-baseline-v6', 't306-asian-mainline-v6', 'T613-E-BATCH', ?, 1,
                        'ASIAN', ?, 'HOME_COVER', 'OVER', 'HIGH', 'MEDIUM',
                        '半套 λ。', TIMESTAMPTZ '2036-02-02 10:01:00+08', 1.250000)
                """,
                matchId, "5".repeat(64), asianSnapshotId
        )).isInstanceOf(DataAccessException.class);

        // 大小球只写概率不写离散度：会让 Brier 拿没有分歧证据的方向计账。
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO predictions (
                    match_id, model_version, feature_version, generation_batch_id, generation_batch_hash,
                    prediction_version, prediction_type, asian_odds_snapshot_id, asian_handicap_pick,
                    total_goals_pick, asian_handicap_confidence_level, total_goals_confidence_level,
                    analysis_summary, generated_at, model_over_prob, market_over_prob, edge_over
                )
                VALUES (?, 't306-asian-baseline-v6', 't306-asian-mainline-v6', 'T613-E-BATCH', ?, 1,
                        'ASIAN', ?, 'HOME_COVER', 'OVER', 'HIGH', 'MEDIUM',
                        '缺离散度。', TIMESTAMPTZ '2036-02-02 10:01:00+08', 0.487500, 0.450000, 0.037500)
                """,
                matchId, "6".repeat(64), asianSnapshotId
        )).isInstanceOf(DataAccessException.class);

        // 概率越出 0～1。
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO predictions (
                    match_id, model_version, feature_version, generation_batch_id, generation_batch_hash,
                    prediction_version, prediction_type, asian_odds_snapshot_id, asian_handicap_pick,
                    total_goals_pick, asian_handicap_confidence_level, total_goals_confidence_level,
                    analysis_summary, generated_at,
                    model_cover_prob, market_cover_prob, edge_cover
                )
                VALUES (?, 't306-asian-baseline-v6', 't306-asian-mainline-v6', 'T613-E-BATCH', ?, 1,
                        'ASIAN', ?, 'HOME_COVER', 'OVER', 'HIGH', 'MEDIUM',
                        '概率越界。', TIMESTAMPTZ '2036-02-02 10:01:00+08',
                        1.500000, 0.512345, 0.987655)
                """,
                matchId, "7".repeat(64), asianSnapshotId
        )).isInstanceOf(DataAccessException.class);

        // 竞彩行携带亚盘概率：会让遗留行污染亚盘统计。
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO predictions (
                    match_id, model_version, feature_version, generation_batch_id, generation_batch_hash,
                    prediction_version, prediction_type, home_win_prob, draw_prob, away_win_prob,
                    handicap_pick, expected_total_goals, confidence_level,
                    analysis_summary, generated_at, model_cover_prob
                )
                VALUES (?, 't306-sporttery', 't306-sporttery', 'T613-E-BATCH', ?, 1,
                        'SPORTTERY', 0.450000, 0.270000, 0.280000, 'HOME_COVER', 2.50, 'HIGH',
                        '竞彩携带亚盘概率。', TIMESTAMPTZ '2036-02-02 10:01:00+08', 0.512345)
                """,
                matchId, "8".repeat(64)
        )).isInstanceOf(DataAccessException.class);
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
        prediction.setPredictionType(PredictionTypeEnum.SPORTTERY);
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

        Prediction asianPrediction = new Prediction();
        asianPrediction.setMatchId(matchId);
        asianPrediction.setModelVersion("asian-mapper-v3");
        asianPrediction.setFeatureVersion("asian-feature-v3");
        asianPrediction.setGenerationBatchId("asian-batch-mapper-v3");
        asianPrediction.setGenerationBatchHash("b".repeat(64));
        asianPrediction.setPredictionVersion(1);
        asianPrediction.setPredictionType(PredictionTypeEnum.ASIAN);
        asianPrediction.setAsianOddsSnapshotId(insertAsianSnapshot(matchId, "a"));
        asianPrediction.setAsianHandicapPick(AsianHandicapPickEnum.HOME_COVER);
        asianPrediction.setAsianHandicapConfidenceLevel(ConfidenceLevelEnum.HIGH);
        asianPrediction.setTotalGoalsPick(TotalGoalsPickEnum.OVER);
        asianPrediction.setTotalGoalsConfidenceLevel(ConfidenceLevelEnum.MEDIUM);
        asianPrediction.setAnalysisSummary("T610 亚盘 Mapper 枚举往返验证");
        asianPrediction.setGeneratedAt(Instant.parse("2026-07-26T01:00:00Z"));
        asianPrediction.setPredictionStatus(PredictionStatusEnum.DRAFT);

        assertThat(predictionMapper.insert(asianPrediction)).isEqualTo(1);
        Prediction savedAsianPrediction = predictionMapper.selectById(asianPrediction.getId());
        assertThat(savedAsianPrediction.getPredictionType()).isEqualTo(PredictionTypeEnum.ASIAN);
        assertThat(savedAsianPrediction.getHomeWinProb()).isNull();
        assertThat(savedAsianPrediction.getAsianHandicapConfidenceLevel()).isEqualTo(ConfidenceLevelEnum.HIGH);
        assertThat(savedAsianPrediction.getTotalGoalsConfidenceLevel()).isEqualTo(ConfidenceLevelEnum.MEDIUM);

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

    private Long insertAsianSnapshot(Long matchId, String rawHashSeed) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO asian_odds_snapshots (
                    match_id, provider_code, bookmaker_code, handicap_line, home_odds, away_odds,
                    total_line, over_odds, under_odds, snapshot_type, captured_at, raw_payload_hash
                )
                VALUES (?, 'T610_MAPPER', 'T610_MAPPER_BOOK', -0.50, 1.85, 1.95, 2.50, 1.82, 2.02,
                        'PRE_KICKOFF', TIMESTAMPTZ '2026-07-26 01:00:00+00', ?)
                RETURNING id
                """,
                Long.class,
                matchId,
                rawHashSeed.substring(0, 1).repeat(64)
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
