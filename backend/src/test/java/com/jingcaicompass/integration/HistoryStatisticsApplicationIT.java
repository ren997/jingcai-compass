package com.jingcaicompass.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.jingcaicompass.history.dto.HistoryListQueryDto;
import com.jingcaicompass.history.service.HistoryQueryService;
import com.jingcaicompass.history.service.HistoryQueryServiceImpl;
import com.jingcaicompass.prediction.enums.PredictionTypeEnum;
import com.jingcaicompass.settlement.enums.MarketTypeEnum;
import com.jingcaicompass.settlement.enums.SettlementStatusEnum;
import com.jingcaicompass.statistics.dto.StatisticsSummaryQueryDto;
import com.jingcaicompass.statistics.enums.ProbabilityScoringModeEnum;
import com.jingcaicompass.statistics.service.StatisticsQueryService;
import com.jingcaicompass.statistics.service.StatisticsQueryServiceImpl;
import com.jingcaicompass.statistics.vo.MarketHitRateVo;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
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

/** T507 PostgreSQL 16 验证公开历史链、当前统计口径和 V12 查询计划。 */
@Testcontainers
@ActiveProfiles("integration")
@SpringBootTest
class HistoryStatisticsApplicationIT {

    private static final String POSTGRES_IMAGE = "postgres:16-alpine";
    private static final AtomicLong KEY_SEQUENCE = new AtomicLong(5_070_000L);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE)
            .withDatabaseName("jingcai_history_statistics")
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
    private HistoryQueryService historyQueryService;

    @Autowired
    private StatisticsQueryService statisticsQueryService;

    @BeforeEach
    void verifiesIsolatedPostgresContainer() throws Exception {
        assertThat(historyQueryService).isInstanceOf(HistoryQueryServiceImpl.class);
        assertThat(statisticsQueryService).isInstanceOf(StatisticsQueryServiceImpl.class);
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
                TRUNCATE TABLE audit_logs, settlements, match_result_facts, raw_data_payloads,
                predictions, matches, leagues RESTART IDENTITY CASCADE
                """);
    }

    @Test
    void reconstructsVersionedHistoryKeepsPendingAndUsesOnlyCurrentVersionsForStatistics() {
        long premierLeague = insertLeague("T507 英超");
        long laLiga = insertLeague("T507 西甲");
        RevisionFixture revision = insertRevisedFinalFixture(premierLeague);
        long pendingPredictionId = insertPendingFixture(premierLeague);
        insertVoidFixture(laLiga);
        insertPublishedFixture(premierLeague);

        var corrected = historyQueryService.list(new HistoryListQueryDto(
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31),
                premierLeague,
                "t507-model",
                true,
                null,
                Set.of(SettlementStatusEnum.MISS),
                1,
                20
        ));

        assertThat(corrected.total()).isOne();
        var record = corrected.records().getFirst();
        assertThat(record.predictionId()).isEqualTo(revision.predictionId());
        assertThat(record.resultFacts()).hasSize(2);
        assertThat(record.resultFacts().stream().filter(item -> item.current()).map(item -> item.factVersion()))
                .containsExactly(2);
        assertThat(record.settlementMarkets().getFirst().versions()).hasSize(2);
        assertThat(record.settlementMarkets().getFirst().currentStatus()).isEqualTo(SettlementStatusEnum.MISS);
        assertThat(record.recalculatedAfterFactCorrection()).isTrue();

        var pending = historyQueryService.list(new HistoryListQueryDto(
                null,
                null,
                premierLeague,
                "t507-model",
                true,
                null,
                Set.of(SettlementStatusEnum.PENDING),
                1,
                20
        ));
        assertThat(pending.records()).extracting(item -> item.predictionId()).containsExactly(pendingPredictionId);
        assertThat(pending.records().getFirst().settlementMarkets())
                .allSatisfy(item -> assertThat(item.currentStatus()).isEqualTo(SettlementStatusEnum.PENDING));

        var summary = statisticsQueryService.summary(new StatisticsSummaryQueryDto(
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31),
                null,
                "t507-model"
        ));
        var metrics = summary.requestedWindow().metrics();
        assertThat(metrics.lockedPredictionCount()).isEqualTo(3);
        assertThat(metrics.finalFactCount()).isOne();
        assertThat(metrics.pendingFactCount()).isOne();
        assertThat(metrics.voidFactCount()).isOne();
        assertThat(metrics.probabilityMetrics().scoringMode())
                .isEqualTo(ProbabilityScoringModeEnum.THREE_WAY);
        assertThat(metrics.probabilityMetrics().sampleSize()).isOne();
        assertThat(metrics.probabilityMetrics().brierScore()).isEqualByComparingTo("0.875000");
        assertThat(metrics.probabilityMetrics().logLoss()).isEqualByComparingTo("1.386294");
        // 体彩请求只返回 HAD/HHAD 两个市场，顺序固定。
        assertThat(metrics.markets()).extracting(MarketHitRateVo::marketType)
                .containsExactly(MarketTypeEnum.HAD, MarketTypeEnum.HHAD);
        assertThat(metrics.markets().get(0).hitCount()).isZero();
        assertThat(metrics.markets().get(0).missCount()).isOne();
        assertThat(metrics.markets().get(0).pendingCount()).isOne();
        assertThat(metrics.markets().get(0).voidCount()).isOne();
        assertThat(metrics.markets().get(1).hitCount()).isOne();
        assertThat(summary.byLeague()).hasSize(2);
    }

    /**
     * 亚盘锁定预测进入统计时既不 500，也不与体彩混算。
     *
     * <p>这是阶段 E 的核心回归：此前 {@code recordMarket} 对每条记录无条件要求 HAD/HHAD，
     * 亚盘记录必抛 {@code missing HAD history}，统计页与首页同时 500。
     */
    @Test
    void settlesAsianPredictionsIntoPairedBrierAndRoiWithoutMixingSportteryDenominators() {
        long leagueId = insertLeague("T613 亚盘联赛");
        insertSportteryLockedFixture(leagueId);
        long asianPredictionId = insertAsianFixture(leagueId);

        var sporttery = statisticsQueryService.summary(new StatisticsSummaryQueryDto(
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31),
                null,
                null,
                PredictionTypeEnum.SPORTTERY
        ));
        var asian = statisticsQueryService.summary(new StatisticsSummaryQueryDto(
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31),
                null,
                null,
                PredictionTypeEnum.ASIAN
        ));

        // 隔离：两条产品线各看各的，互不进入对方分母。
        assertThat(sporttery.appliedFilter().predictionType()).isEqualTo(PredictionTypeEnum.SPORTTERY);
        assertThat(sporttery.requestedWindow().metrics().markets())
                .extracting(MarketHitRateVo::marketType)
                .containsExactly(MarketTypeEnum.HAD, MarketTypeEnum.HHAD);
        assertThat(asian.appliedFilter().predictionType()).isEqualTo(PredictionTypeEnum.ASIAN);
        assertThat(asian.requestedWindow().metrics().lockedPredictionCount()).isOne();
        assertThat(asian.requestedWindow().metrics().markets())
                .extracting(MarketHitRateVo::marketType)
                .containsExactly(MarketTypeEnum.ASIAN_HANDICAP, MarketTypeEnum.ASIAN_TOTAL_GOALS);

        var metrics = asian.requestedWindow().metrics();
        // 让球 HOME_COVER/HIT 与大小球 UNDER/PUSH（推小球即取 1 − 大球概率）各一个样本：
        // 模型 ((0.60−1)² + (0.60−0.5)²)/2 = 0.085000，市场 0.102500。
        assertThat(metrics.probabilityMetrics().scoringMode())
                .isEqualTo(ProbabilityScoringModeEnum.ASIAN_DIRECTION);
        assertThat(metrics.probabilityMetrics().sampleSize()).isEqualTo(2);
        assertThat(metrics.probabilityMetrics().brierScore()).isEqualByComparingTo("0.085000");
        assertThat(metrics.probabilityMetrics().marketBrierScore()).isEqualByComparingTo("0.102500");
        assertThat(metrics.probabilityMetrics().brierAdvantage()).isEqualByComparingTo("0.017500");
        assertThat(metrics.probabilityMetrics().modelOutperformsMarket()).isTrue();

        // 收益：HIT +0.92，PUSH 0，两注本金 → roi 0.46；yield 剔除走盘（1 注承担风险）→ 0.92。
        assertThat(metrics.roi().available()).isTrue();
        assertThat(metrics.roi().sampleSize()).isEqualTo(2);
        assertThat(metrics.roi().roi()).isEqualByComparingTo("0.460000");
        assertThat(metrics.roi().yield()).isEqualByComparingTo("0.920000");
        assertThat(metrics.roi().unavailableReasons()).isEmpty();

        // 走盘不进命中率分母，半盘状态单独计数。
        var handicap = metrics.markets().get(0);
        assertThat(handicap.hitCount()).isOne();
        assertThat(handicap.settledSampleSize()).isOne();
        assertThat(handicap.hitRate()).isEqualByComparingTo("1.000000");
        var totals = metrics.markets().get(1);
        assertThat(totals.pushCount()).isOne();
        assertThat(totals.settledSampleSize()).isZero();
        assertThat(totals.hitRate()).isNull();
        assertThat(asianPredictionId).isPositive();
    }

    @Test
    void keepsPageOrderStableAndV12IndexesCoverPublicHistoryQueries() {
        long leagueId = insertLeague("T507 索引联赛");
        insertRevisedFinalFixture(leagueId);
        insertPendingFixture(leagueId);
        insertIndexDataset(leagueId);
        insertIndexNoiseDataset(insertLeague("T507 索引噪声联赛"));

        var firstPage = historyQueryService.list(new HistoryListQueryDto(
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31),
                leagueId,
                null,
                null,
                null,
                Set.of(SettlementStatusEnum.MISS),
                1,
                1
        ));
        var repeatedFirstPage = historyQueryService.list(new HistoryListQueryDto(
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31),
                leagueId,
                null,
                null,
                null,
                Set.of(SettlementStatusEnum.MISS),
                1,
                1
        ));
        var secondPage = historyQueryService.list(new HistoryListQueryDto(
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31),
                leagueId,
                null,
                null,
                null,
                Set.of(SettlementStatusEnum.MISS),
                2,
                1
        ));
        assertThat(firstPage.records().getFirst().predictionId())
                .isEqualTo(repeatedFirstPage.records().getFirst().predictionId())
                .isNotEqualTo(secondPage.records().getFirst().predictionId());

        jdbcTemplate.execute("ANALYZE matches");
        jdbcTemplate.execute("ANALYZE predictions");
        jdbcTemplate.execute("ANALYZE settlements");
        String matchPlan = explain("""
                SELECT id FROM matches
                WHERE league_id = %d
                  AND lottery_date BETWEEN DATE '2026-07-01' AND DATE '2026-07-30'
                ORDER BY lottery_date DESC, kickoff_time DESC, id DESC
                LIMIT 20
                """.formatted(leagueId));
        String predictionPlan = explain("""
                SELECT id FROM predictions
                WHERE prediction_status = 'LOCKED' AND model_version = 't507-index'
                ORDER BY match_id, publish_time DESC, id DESC
                LIMIT 20
                """);
        String settlementPlan = explain("""
                SELECT prediction_id FROM settlements
                WHERE market_type = 'HAD' AND settlement_status = 'MISS' AND is_current
                ORDER BY prediction_id
                LIMIT 20
                """);
        assertThat(matchPlan).contains("idx_matches_history_lottery_league_kickoff");
        assertThat(predictionPlan).contains("idx_predictions_history_public_model_match");
        assertThat(settlementPlan).contains("idx_settlements_current_market_status_prediction");
    }

    private RevisionFixture insertRevisedFinalFixture(long leagueId) {
        long matchId = insertMatch(leagueId, LocalDate.of(2026, 7, 24), "revision");
        long firstFactId = insertFact(matchId, 1, null, "FINAL", 1, 0, true);
        long predictionId = insertPrediction(matchId, "t507-model", "LOCKED", "revision");
        insertSettlement(predictionId, "HAD", 1, null, "HIT", firstFactId, true);
        insertSettlement(predictionId, "HHAD", 1, null, "MISS", firstFactId, true);
        jdbcTemplate.update("UPDATE settlements SET is_current = FALSE WHERE prediction_id = ? AND is_current", predictionId);
        jdbcTemplate.update("UPDATE match_result_facts SET is_current = FALSE WHERE id = ?", firstFactId);
        long secondFactId = insertFact(matchId, 2, 1, "FINAL", 0, 1, true);
        long hadReplacementId = insertSettlement(predictionId, "HAD", 2, 1, "MISS", secondFactId, true);
        insertSettlement(predictionId, "HHAD", 2, 1, "HIT", secondFactId, true);
        jdbcTemplate.update("""
                INSERT INTO audit_logs (operator_id, target_type, target_id, action_type, field_name, old_value, new_value)
                VALUES ('system:settlement-recalculation-job', 'SETTLEMENT', ?, 'SUPERSEDE',
                        'settlementRecalculation', 'reason=OFFICIAL_FACT_SUPERSEDED', 'reason=OFFICIAL_FACT_SUPERSEDED')
                """, String.valueOf(hadReplacementId));
        return new RevisionFixture(predictionId);
    }

    private long insertPendingFixture(long leagueId) {
        long matchId = insertMatch(leagueId, LocalDate.of(2026, 7, 23), "pending");
        insertFact(matchId, 1, null, "PENDING", null, null, true);
        return insertPrediction(matchId, "t507-model", "LOCKED", "pending");
    }

    private void insertVoidFixture(long leagueId) {
        long matchId = insertMatch(leagueId, LocalDate.of(2026, 7, 22), "void");
        long factId = insertFact(matchId, 1, null, "VOID", null, null, true);
        long predictionId = insertPrediction(matchId, "t507-model", "LOCKED", "void");
        insertSettlement(predictionId, "HAD", 1, null, "VOID", factId, true);
        insertSettlement(predictionId, "HHAD", 1, null, "VOID", factId, true);
    }

    private void insertPublishedFixture(long leagueId) {
        long matchId = insertMatch(leagueId, LocalDate.of(2026, 7, 21), "published");
        insertPrediction(matchId, "t507-published", "PUBLISHED", "published");
    }

    /** 一条已结算的体彩锁定预测，用于验证亚盘统计不会把它算进分母。 */
    private void insertSportteryLockedFixture(long leagueId) {
        long matchId = insertMatch(leagueId, LocalDate.of(2026, 7, 25), "sporttery-locked");
        long factId = insertFact(matchId, 1, null, "FINAL", 1, 0, true);
        long predictionId = insertPrediction(matchId, "t507-model", "LOCKED", "sporttery-locked");
        insertSettlement(predictionId, "HAD", 1, null, "HIT", factId, true);
        insertSettlement(predictionId, "HHAD", 1, null, "MISS", factId, true);
    }

    /**
     * 一条带 V24 十列概率的亚盘锁定预测：让球 HIT、大小球 PUSH。
     *
     * <p>十列必须成对出现（V24 的 {@code ck_predictions_asian_model_output_pairs}），
     * 且六个体彩核心列必须为 NULL（V21 的 {@code ck_predictions_sporttery_core_by_type}）。
     */
    private long insertAsianFixture(long leagueId) {
        long matchId = insertMatch(leagueId, LocalDate.of(2026, 7, 26), "asian");
        long factId = insertFact(matchId, 1, null, "FINAL", 2, 1, true);
        long snapshotId = insertAsianSnapshot(matchId);
        long key = nextKey();
        Instant publishTime = Instant.parse("2026-07-20T10:00:00Z");
        long predictionId = jdbcTemplate.queryForObject(
                """
                INSERT INTO predictions (
                    match_id, model_version, feature_version, generation_batch_id, generation_batch_hash,
                    prediction_version, analysis_summary, generated_at, prediction_status,
                    publish_time, lock_time, prediction_hash,
                    prediction_type, asian_odds_snapshot_id,
                    asian_handicap_pick, asian_handicap_confidence_level,
                    total_goals_pick, total_goals_confidence_level,
                    lambda_home, lambda_away, model_cover_prob, market_cover_prob, edge_cover,
                    model_over_prob, market_over_prob, edge_over, handicap_dispersion, totals_dispersion
                )
                VALUES (?, 't306-asian-baseline-v6', 't306-asian-mainline-v6', ?, ?, 1,
                        'T613 亚盘统计测试预测', ?, 'LOCKED', ?, ?, ?,
                        'ASIAN', ?,
                        'HOME_COVER', 'MEDIUM', 'UNDER', 'MEDIUM',
                        1.500000, 1.200000, 0.600000, 0.550000, 0.050000,
                        0.400000, 0.450000, -0.050000, 0.012000, 0.009000)
                RETURNING id
                """,
                Long.class,
                matchId,
                "T613-batch-" + key,
                "a".repeat(64),
                Timestamp.from(publishTime.minusSeconds(60)),
                Timestamp.from(publishTime),
                Timestamp.from(publishTime.plusSeconds(60)),
                "b".repeat(64),
                snapshotId
        );
        insertAsianSettlement(predictionId, "ASIAN_HANDICAP", "HIT", factId, new BigDecimal("0.9200"));
        insertAsianSettlement(predictionId, "ASIAN_TOTAL_GOALS", "PUSH", factId, BigDecimal.ZERO);
        return predictionId;
    }

    private long insertAsianSnapshot(long matchId) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO asian_odds_snapshots (
                    match_id, provider_code, bookmaker_code, handicap_line, home_odds, away_odds,
                    total_line, over_odds, under_odds, snapshot_type, captured_at,
                    provider_updated_at, raw_payload_hash
                )
                VALUES (?, 'T613_IT', 'pinnacle', -0.25, 1.9200, 1.9600,
                        2.75, 1.9500, 1.9500, 'PRE_KICKOFF', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?)
                RETURNING id
                """,
                Long.class,
                matchId,
                "c".repeat(64)
        );
    }

    private void insertAsianSettlement(
            long predictionId,
            String marketType,
            String status,
            long factId,
            BigDecimal profit
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO settlements (
                    prediction_id, market_type, settlement_version, settlement_status,
                    match_fact_id, rule_version, is_current, profit
                )
                VALUES (?, ?, 1, ?, ?, 't613-v1', TRUE, ?)
                """,
                predictionId,
                marketType,
                status,
                factId,
                profit
        );
    }

    private long insertLeague(String name) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO leagues (name_zh) VALUES (?) RETURNING id",
                Long.class,
                name + "-" + nextKey()
        );
    }

    private long insertMatch(long leagueId, LocalDate lotteryDate, String suffix) {
        long key = nextKey();
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO matches (
                    lottery_match_no, lottery_date, league_id, league_name, home_team_name,
                    away_team_name, kickoff_time, match_status
                )
                VALUES (?, ?, ?, 'T507 联赛', 'T507 主队', 'T507 客队', ?, 'SCHEDULED')
                RETURNING id
                """,
                Long.class,
                "T507-" + suffix + "-" + key,
                lotteryDate,
                leagueId,
                Timestamp.from(Instant.parse("2026-07-27T12:00:00Z").plusSeconds(key % 10_000))
        );
    }

    private long insertFact(
            long matchId,
            int factVersion,
            Integer supersedesFactVersion,
            String factStatus,
            Integer homeScore,
            Integer awayScore,
            boolean current
    ) {
        long rawPayloadId = jdbcTemplate.queryForObject(
                """
                INSERT INTO raw_data_payloads (
                    provider_code, data_type, request_key, requested_at, payload, payload_hash, parse_status
                )
                VALUES ('T507_IT', 'SPORTTERY_RESULT', ?, CURRENT_TIMESTAMP, '{}'::jsonb, ?, 'SUCCESS')
                RETURNING id
                """,
                Long.class,
                "T507-result-" + nextKey(),
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
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?)
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
                rawPayloadId,
                current
        );
    }

    private long insertPrediction(long matchId, String modelVersion, String status, String suffix) {
        long key = nextKey();
        Instant publishTime = Instant.parse("2026-07-20T10:00:00Z");
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO predictions (
                    match_id, model_version, feature_version, generation_batch_id, generation_batch_hash,
                    prediction_version, home_win_prob, draw_prob, away_win_prob, handicap_pick,
                    expected_total_goals, confidence_level, analysis_summary, generated_at,
                    prediction_status, publish_time, lock_time, prediction_hash
                )
                VALUES (?, ?, 't507-feature', ?, ?, 1, 0.500000, 0.250000, 0.250000, 'HOME_WIN',
                        2.50, 'MEDIUM', 'T507 公开历史测试预测', ?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                matchId,
                modelVersion,
                "T507-batch-" + suffix + "-" + key,
                "a".repeat(64),
                Timestamp.from(publishTime.minusSeconds(60)),
                status,
                Timestamp.from(publishTime),
                Timestamp.from(publishTime.plusSeconds(60)),
                "b".repeat(64)
        );
    }

    private long insertSettlement(
            long predictionId,
            String marketType,
            int settlementVersion,
            Integer supersedesSettlementVersion,
            String status,
            long factId,
            boolean current
    ) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO settlements (
                    prediction_id, market_type, settlement_version, supersedes_settlement_version,
                    settlement_status, match_fact_id, rule_version, is_current
                )
                VALUES (?, ?, ?, ?, ?, ?, 't403-v1', ?)
                RETURNING id
                """,
                Long.class,
                predictionId,
                marketType,
                settlementVersion,
                supersedesSettlementVersion,
                status,
                factId,
                current
        );
    }

    private void insertIndexDataset(long leagueId) {
        long key = nextKey();
        jdbcTemplate.update(
                """
                INSERT INTO matches (
                    lottery_match_no, lottery_date, league_id, league_name, home_team_name,
                    away_team_name, kickoff_time, match_status
                )
                SELECT ? || '-' || series, DATE '2026-07-01' + (series % 30), ?, 'T507 索引联赛', '主队', '客队',
                       TIMESTAMPTZ '2026-07-27 12:00:00+00' + series * INTERVAL '1 second', 'SCHEDULED'
                FROM generate_series(1, 300) AS series
                """,
                "T507-index-" + key,
                leagueId
        );
        jdbcTemplate.update(
                """
                INSERT INTO predictions (
                    match_id, model_version, feature_version, generation_batch_id, generation_batch_hash,
                    prediction_version, home_win_prob, draw_prob, away_win_prob, handicap_pick,
                    expected_total_goals, confidence_level, analysis_summary, generated_at,
                    prediction_status, publish_time, lock_time, prediction_hash
                )
                SELECT m.id, 't507-index', 't507-feature', 'T507-index-batch-' || m.id,
                       LPAD(m.id::TEXT, 64, 'a'), 1, 0.500000, 0.250000, 0.250000, 'HOME_WIN',
                       2.50, 'MEDIUM', 'T507 索引预测', CURRENT_TIMESTAMP, 'LOCKED',
                       CURRENT_TIMESTAMP - INTERVAL '2 minutes', CURRENT_TIMESTAMP - INTERVAL '1 minute',
                       LPAD(m.id::TEXT, 64, 'b')
                FROM matches m
                WHERE m.lottery_match_no LIKE ?
                """,
                "T507-index-" + key + "-%"
        );
        jdbcTemplate.update(
                """
                INSERT INTO raw_data_payloads (
                    provider_code, data_type, request_key, requested_at, payload, payload_hash, parse_status
                )
                SELECT 'T507_INDEX', 'SPORTTERY_RESULT', 'T507-index-result-' || m.id,
                       CURRENT_TIMESTAMP, '{}'::jsonb, LPAD(m.id::TEXT, 64, 'c'), 'SUCCESS'
                FROM matches m
                WHERE m.lottery_match_no LIKE ?
                """,
                "T507-index-" + key + "-%"
        );
        jdbcTemplate.update(
                """
                INSERT INTO match_result_facts (
                    match_id, fact_version, fact_status, match_status, home_score, away_score,
                    raw_data_payload_id, provider_updated_at, is_current
                )
                SELECT m.id, 1, 'FINAL', 'FINISHED', 1, 0, raw.id, CURRENT_TIMESTAMP, TRUE
                FROM matches m
                INNER JOIN raw_data_payloads raw ON raw.request_key = 'T507-index-result-' || m.id
                WHERE m.lottery_match_no LIKE ?
                """,
                "T507-index-" + key + "-%"
        );
        jdbcTemplate.update(
                """
                INSERT INTO settlements (
                    prediction_id, market_type, settlement_version, settlement_status, match_fact_id, rule_version, is_current
                )
                SELECT prediction.id, 'HAD', 1, 'MISS', fact.id, 't403-v1', TRUE
                FROM predictions prediction
                INNER JOIN match_result_facts fact ON fact.match_id = prediction.match_id AND fact.is_current
                WHERE prediction.model_version = 't507-index'
                """);
    }

    private void insertIndexNoiseDataset(long leagueId) {
        long key = nextKey();
        String prefix = "T507-noise-" + key;
        jdbcTemplate.update(
                """
                INSERT INTO matches (
                    lottery_match_no, lottery_date, league_id, league_name, home_team_name,
                    away_team_name, kickoff_time, match_status
                )
                SELECT ? || '-' || series, DATE '2026-07-01' + (series % 30), ?, 'T507 噪声联赛', '主队', '客队',
                       TIMESTAMPTZ '2026-07-27 12:00:00+00' + series * INTERVAL '1 second', 'SCHEDULED'
                FROM generate_series(1, 1200) AS series
                """,
                prefix,
                leagueId
        );
        jdbcTemplate.update(
                """
                INSERT INTO predictions (
                    match_id, model_version, feature_version, generation_batch_id, generation_batch_hash,
                    prediction_version, home_win_prob, draw_prob, away_win_prob, handicap_pick,
                    expected_total_goals, confidence_level, analysis_summary, generated_at,
                    prediction_status, publish_time, lock_time, prediction_hash
                )
                SELECT m.id, 't507-noise', 't507-feature', 'T507-noise-batch-' || m.id,
                       LPAD(m.id::TEXT, 64, 'd'), 1, 0.500000, 0.250000, 0.250000, 'HOME_WIN',
                       2.50, 'MEDIUM', 'T507 噪声预测', CURRENT_TIMESTAMP, 'LOCKED',
                       CURRENT_TIMESTAMP - INTERVAL '2 minutes', CURRENT_TIMESTAMP - INTERVAL '1 minute',
                       LPAD(m.id::TEXT, 64, 'e')
                FROM matches m
                WHERE m.lottery_match_no LIKE ?
                """,
                prefix + "-%"
        );
        jdbcTemplate.update(
                """
                INSERT INTO raw_data_payloads (
                    provider_code, data_type, request_key, requested_at, payload, payload_hash, parse_status
                )
                SELECT 'T507_NOISE', 'SPORTTERY_RESULT', 'T507-noise-result-' || m.id,
                       CURRENT_TIMESTAMP, '{}'::jsonb, LPAD(m.id::TEXT, 64, 'f'), 'SUCCESS'
                FROM matches m
                WHERE m.lottery_match_no LIKE ?
                """,
                prefix + "-%"
        );
        jdbcTemplate.update(
                """
                INSERT INTO match_result_facts (
                    match_id, fact_version, fact_status, match_status, home_score, away_score,
                    raw_data_payload_id, provider_updated_at, is_current
                )
                SELECT m.id, 1, 'FINAL', 'FINISHED', 1, 0, raw.id, CURRENT_TIMESTAMP, TRUE
                FROM matches m
                INNER JOIN raw_data_payloads raw ON raw.request_key = 'T507-noise-result-' || m.id
                WHERE m.lottery_match_no LIKE ?
                """,
                prefix + "-%"
        );
        jdbcTemplate.update(
                """
                INSERT INTO settlements (
                    prediction_id, market_type, settlement_version, settlement_status, match_fact_id, rule_version, is_current
                )
                SELECT prediction.id, 'HAD', 1, 'HIT', fact.id, 't403-v1', TRUE
                FROM predictions prediction
                INNER JOIN match_result_facts fact ON fact.match_id = prediction.match_id AND fact.is_current
                WHERE prediction.model_version = 't507-noise'
                """);
    }

    private String explain(String query) {
        return String.join("\n", jdbcTemplate.queryForList("EXPLAIN (COSTS OFF) " + query, String.class));
    }

    private long nextKey() {
        return KEY_SEQUENCE.incrementAndGet();
    }

    private record RevisionFixture(long predictionId) {
    }

}
