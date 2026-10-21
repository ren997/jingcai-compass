package com.jingcaicompass.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.jingcaicompass.admin.dto.AdminSettlementStatusListQueryDto;
import com.jingcaicompass.admin.enums.AdminSettlementDiagnosticEnum;
import com.jingcaicompass.admin.service.AdminPredictionStatusQueryService;
import com.jingcaicompass.admin.vo.AdminPredictionStatusItemVo;
import com.jingcaicompass.prediction.enums.PredictionTypeEnum;
import com.jingcaicompass.settlement.enums.MarketTypeEnum;
import com.jingcaicompass.settlement.enums.SettlementStatusEnum;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Timestamp;
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

/**
 * T613 后台结算状态在真实 PostgreSQL 16 上的行为。
 *
 * <p>为什么必须跑真库：诊断谓词里同时出现四条 {@code LEFT JOIN}、三项 {@code IS NULL} 判定
 * 与 {@code match_fact_id <> fact.id} 的 NULL 传播，注解文本断言查不出语法错误与逻辑偏差；
 * 而「待人工处理角标」与列表/总数共用谓词这件事，只有真库才能证明三者一致。
 */
@Testcontainers
@ActiveProfiles("integration")
@SpringBootTest
class AdminPredictionStatusApplicationIT {

    private static final String POSTGRES_IMAGE = "postgres:16-alpine";
    private static final AtomicLong KEY_SEQUENCE = new AtomicLong(6_130_000L);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE)
            .withDatabaseName("jingcai_admin_status")
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
    private AdminPredictionStatusQueryService adminPredictionStatusQueryService;

    @BeforeEach
    void truncatesOperationalTables() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertThat(metadata.getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(metadata.getDatabaseMajorVersion()).isEqualTo(16);
        }
        jdbcTemplate.execute("""
                TRUNCATE TABLE audit_logs, settlements, match_result_facts, raw_data_payloads,
                predictions, asian_odds_snapshots, matches, leagues RESTART IDENTITY CASCADE
                """);
    }

    /**
     * 亚盘缺一侧方向结算时，只报缺失的那一个市场，且不能出现任何 HAD 诊断。
     *
     * <p>修复前这条预测会 100% 命中 {@code SETTLEMENT_MISSING_HAD}，
     * 即使让球市场的结算已经落库。
     */
    @Test
    void asianPredictionWithOneSettledMarketOnlyReportsTheMissingOne() {
        long leagueId = insertLeague("T613 后台联赛");
        long matchId = insertMatch(leagueId, LocalDate.of(2026, 7, 29), "asian-partial");
        long factId = insertFinalFact(matchId, 2, 1);
        long predictionId = insertAsianPrediction(
                matchId,
                "HOME_COVER",
                "OVER",
                insertAsianSnapshot(matchId)
        );
        insertAsianSettlement(predictionId, "ASIAN_HANDICAP", "HIT", factId, "0.9200");

        var page = adminPredictionStatusQueryService.settlements(
                new AdminSettlementStatusListQueryDto(null, null, null, 1, 20));

        AdminPredictionStatusItemVo item = page.records().stream()
                .filter(record -> record.predictionId().equals(predictionId))
                .findFirst()
                .orElseThrow();
        assertThat(item.predictionType()).isEqualTo(PredictionTypeEnum.ASIAN);
        assertThat(item.settlementMarkets())
                .extracting(market -> market.marketType())
                .containsExactly(MarketTypeEnum.ASIAN_HANDICAP, MarketTypeEnum.ASIAN_TOTAL_GOALS);
        assertThat(item.settlementMarkets().get(0).currentStatus()).isEqualTo(SettlementStatusEnum.HIT);
        assertThat(item.settlementMarkets().get(0).profit()).isEqualByComparingTo("0.9200");
        assertThat(item.settlementMarkets().get(1).currentSettlementPersisted()).isFalse();

        assertThat(item.settlementDiagnostics())
                .singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo(
                            AdminSettlementDiagnosticEnum.SETTLEMENT_MISSING.getCode());
                    assertThat(diagnostic.marketType()).isEqualTo(MarketTypeEnum.ASIAN_TOTAL_GOALS);
                });
        assertThat(page.manualAttentionCount()).isEqualTo(1);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.records()).hasSize(1);
    }

    /**
     * 走盘与半赢都是终态，不能被算成「待人工处理」。
     *
     * <p>如果 SQL 只认 HIT/MISS/VOID，走盘场次会永久停留在这个角标里，永远不会消失。
     */
    @Test
    void asianWalkOverAndHalfSettlementsAreTerminalAndLeaveTheAttentionQueue() {
        long leagueId = insertLeague("T613 后台终态联赛");
        long matchId = insertMatch(leagueId, LocalDate.of(2026, 7, 29), "asian-terminal");
        long factId = insertFinalFact(matchId, 2, 1);
        long predictionId = insertAsianPrediction(
                matchId,
                "HOME_COVER",
                "UNDER",
                insertAsianSnapshot(matchId)
        );
        insertAsianSettlement(predictionId, "ASIAN_HANDICAP", "PUSH", factId, "0.0000");
        insertAsianSettlement(predictionId, "ASIAN_TOTAL_GOALS", "HALF_WIN", factId, "0.4100");

        var page = adminPredictionStatusQueryService.settlements(
                new AdminSettlementStatusListQueryDto(null, null, null, 1, 20));

        assertThat(page.records()).isEmpty();
        assertThat(page.total()).isZero();
        assertThat(page.manualAttentionCount()).isZero();
    }

    /**
     * 体彩预测仍然要求 HAD 与 HHAD 都存在，新增市场枚举不得放松既有口径。
     *
     * <p>同时验证列表、总数与角标三者用同一段谓词：任一处的谓词不同都会在这里暴露。
     */
    @Test
    void sportteryPredictionsStillRequireBothHadAndHhad() {
        long leagueId = insertLeague("T613 后台体彩联赛");
        long missingMatchId = insertMatch(leagueId, LocalDate.of(2026, 7, 29), "sporttery-missing");
        long missingFactId = insertFinalFact(missingMatchId, 1, 0);
        long missingPredictionId = insertSportteryPrediction(missingMatchId);
        insertSportterySettlement(missingPredictionId, "HAD", "HIT", missingFactId);

        long completeMatchId = insertMatch(leagueId, LocalDate.of(2026, 7, 29), "sporttery-complete");
        long completeFactId = insertFinalFact(completeMatchId, 1, 0);
        long completePredictionId = insertSportteryPrediction(completeMatchId);
        insertSportterySettlement(completePredictionId, "HAD", "HIT", completeFactId);
        insertSportterySettlement(completePredictionId, "HHAD", "MISS", completeFactId);

        var page = adminPredictionStatusQueryService.settlements(
                new AdminSettlementStatusListQueryDto(null, null, null, 1, 20));

        assertThat(page.records()).extracting(record -> record.predictionId())
                .containsExactly(missingPredictionId);
        assertThat(page.records()).singleElement().satisfies(item -> {
            assertThat(item.predictionType()).isEqualTo(PredictionTypeEnum.SPORTTERY);
            assertThat(item.settlementMarkets())
                    .extracting(market -> market.marketType())
                    .containsExactly(MarketTypeEnum.HAD, MarketTypeEnum.HHAD);
            assertThat(item.settlementDiagnostics())
                    .singleElement()
                    .satisfies(diagnostic -> {
                        assertThat(diagnostic.code()).isEqualTo(
                                AdminSettlementDiagnosticEnum.SETTLEMENT_MISSING.getCode());
                        assertThat(diagnostic.marketType()).isEqualTo(MarketTypeEnum.HHAD);
                    });
            // 体彩没有冻结水位，收益列必须为空而不是 0。
            assertThat(item.settlementMarkets().get(0).profit()).isNull();
        });
        // 列表、总数、角标必须一致：三者共用同一段谓词。
        assertThat(page.total()).isEqualTo(page.records().size());
        assertThat(page.manualAttentionCount()).isEqualTo(page.total());
    }

    private long insertLeague(String name) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO leagues (name_zh) VALUES (?) RETURNING id",
                Long.class,
                name + "-" + nextKey());
    }

    private long insertMatch(long leagueId, LocalDate lotteryDate, String suffix) {
        long key = nextKey();
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO matches (
                    lottery_match_no, lottery_date, league_id, league_name, home_team_name,
                    away_team_name, kickoff_time, match_status
                )
                VALUES (?, ?, ?, 'T613 联赛', 'T613 主队', 'T613 客队', ?, 'SCHEDULED')
                RETURNING id
                """,
                Long.class,
                "T613-" + suffix + "-" + key,
                lotteryDate,
                leagueId,
                Timestamp.from(Instant.parse("2026-07-29T12:00:00Z").plusSeconds(key % 10_000)));
    }

    private long insertFinalFact(long matchId, Integer homeScore, Integer awayScore) {
        long rawPayloadId = jdbcTemplate.queryForObject(
                """
                INSERT INTO raw_data_payloads (
                    provider_code, data_type, request_key, requested_at, payload, payload_hash, parse_status
                )
                VALUES ('T613_IT', 'SPORTTERY_RESULT', ?, CURRENT_TIMESTAMP, '{}'::jsonb, ?, 'SUCCESS')
                RETURNING id
                """,
                Long.class,
                "T613-result-" + nextKey(),
                String.format("%064x", nextKey()));
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO match_result_facts (
                    match_id, fact_version, fact_status, match_status, home_score, away_score,
                    raw_data_payload_id, provider_updated_at, is_current
                )
                VALUES (?, 1, 'FINAL', 'FINISHED', ?, ?, ?, CURRENT_TIMESTAMP, TRUE)
                RETURNING id
                """,
                Long.class,
                matchId,
                homeScore,
                awayScore,
                rawPayloadId);
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
                "c".repeat(64));
    }

    /**
     * 一条已锁定的亚盘预测；两个方向都给，以便分别验证「有方向的才要求结算」。
     *
     * <p>六个体彩核心列必须为 NULL（V21 {@code ck_predictions_sporttery_core_by_type}），
     * 十列概率必须成对出现（V24），偏移量 {@code prediction_hash} 需为 64 位 hex。
     */
    private long insertAsianPrediction(
            long matchId,
            String handicapPick,
            String totalGoalsPick,
            long snapshotId
    ) {
        long key = nextKey();
        Instant publishTime = Instant.parse("2026-07-29T10:00:00Z");
        return jdbcTemplate.queryForObject(
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
                        'T613 后台状态测试预测', ?, 'LOCKED', ?, ?, ?,
                        'ASIAN', ?,
                        ?, 'MEDIUM', ?, 'MEDIUM',
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
                snapshotId,
                handicapPick,
                totalGoalsPick);
    }

    private long insertSportteryPrediction(long matchId) {
        long key = nextKey();
        Instant publishTime = Instant.parse("2026-07-29T10:00:00Z");
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO predictions (
                    match_id, model_version, feature_version, generation_batch_id, generation_batch_hash,
                    prediction_version, home_win_prob, draw_prob, away_win_prob, handicap_pick,
                    expected_total_goals, confidence_level, analysis_summary, generated_at,
                    prediction_status, publish_time, lock_time, prediction_hash
                )
                VALUES (?, 't507-model', 't507-feature', ?, ?, 1, 0.500000, 0.250000, 0.250000, 'HOME_WIN',
                        2.50, 'MEDIUM', 'T613 后台体彩测试预测', ?, 'LOCKED', ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                matchId,
                "T613-sporttery-batch-" + key,
                "a".repeat(64),
                Timestamp.from(publishTime.minusSeconds(60)),
                Timestamp.from(publishTime),
                Timestamp.from(publishTime.plusSeconds(60)),
                "d".repeat(64));
    }

    private void insertAsianSettlement(long predictionId, String marketType, String status, long factId, String profit) {
        jdbcTemplate.update(
                """
                INSERT INTO settlements (
                    prediction_id, market_type, settlement_version, settlement_status,
                    match_fact_id, rule_version, is_current, profit
                )
                VALUES (?, ?, 1, ?, ?, 't613-v1', TRUE, ?::numeric)
                """,
                predictionId,
                marketType,
                status,
                factId,
                profit);
    }

    private void insertSportterySettlement(long predictionId, String marketType, String status, long factId) {
        jdbcTemplate.update(
                """
                INSERT INTO settlements (
                    prediction_id, market_type, settlement_version, settlement_status,
                    match_fact_id, rule_version, is_current
                )
                VALUES (?, ?, 1, ?, ?, 't403-v1', TRUE)
                """,
                predictionId,
                marketType,
                status,
                factId);
    }

    private long nextKey() {
        return KEY_SEQUENCE.incrementAndGet();
    }
}
