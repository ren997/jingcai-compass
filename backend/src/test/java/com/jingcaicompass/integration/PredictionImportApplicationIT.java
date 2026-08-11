package com.jingcaicompass.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jingcaicompass.prediction.dto.PredictionImportResultDto;
import com.jingcaicompass.prediction.dto.PredictionLockResultDto;
import com.jingcaicompass.prediction.dto.PredictionPublishDto;
import com.jingcaicompass.prediction.entity.Prediction;
import com.jingcaicompass.prediction.enums.BaselinePredictionSkipReasonEnum;
import com.jingcaicompass.prediction.enums.AsianHandicapPickEnum;
import com.jingcaicompass.prediction.enums.ConfidenceLevelEnum;
import com.jingcaicompass.prediction.enums.HandicapPickEnum;
import com.jingcaicompass.prediction.enums.PredictionStatusEnum;
import com.jingcaicompass.prediction.enums.TotalGoalsPickEnum;
import com.jingcaicompass.prediction.mapper.PredictionMapper;
import com.jingcaicompass.prediction.service.BaselinePredictionGenerationService;
import com.jingcaicompass.prediction.service.PredictionImportService;
import com.jingcaicompass.prediction.service.PredictionImportWriter;
import com.jingcaicompass.prediction.service.PredictionLockService;
import com.jingcaicompass.prediction.service.PredictionPublishService;
import com.jingcaicompass.prediction.vo.BaselinePredictionGenerationVo;
import com.jingcaicompass.prediction.vo.PredictionPublishResultVo;
import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T302 PostgreSQL 集成验证：使用固定离线样例检查批次幂等、枚举持久化和事务回滚。
 */
@Testcontainers
@ActiveProfiles("integration")
@SpringBootTest
@Import(PredictionImportApplicationIT.FixedClockConfiguration.class)
class PredictionImportApplicationIT {

    private static final String POSTGRES_IMAGE = "postgres:16-alpine";
    private static final Instant FIXED_NOW = Instant.parse("2026-07-26T08:00:00Z");
    private static final long FIRST_MATCH_ID = 3_020_001L;
    private static final long SECOND_MATCH_ID = 3_020_002L;
    private static final long ROLLBACK_FIRST_MATCH_ID = 3_020_011L;
    private static final long ROLLBACK_SECOND_MATCH_ID = 3_020_012L;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(POSTGRES_IMAGE)
                    .withDatabaseName("jingcai_prediction_import")
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
    private PredictionImportService predictionImportService;

    @Autowired
    private PredictionImportWriter predictionImportWriter;

    @Autowired
    private BaselinePredictionGenerationService baselinePredictionGenerationService;

    @Autowired
    private PredictionPublishService predictionPublishService;

    @Autowired
    private PredictionLockService predictionLockService;

    @Autowired
    private PredictionMapper predictionMapper;

    @BeforeEach
    void prepareIsolatedDatabase() throws Exception {
        // 1) 保护性确认当前连接只指向本测试启动的 PostgreSQL 16 容器
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

        // 2) 清理本类固定测试键，确保测试顺序和失败重跑不影响断言
        jdbcTemplate.update(
                "DELETE FROM predictions WHERE match_id IN (?, ?, ?, ?)",
                FIRST_MATCH_ID,
                SECOND_MATCH_ID,
                ROLLBACK_FIRST_MATCH_ID,
                ROLLBACK_SECOND_MATCH_ID
        );
        jdbcTemplate.update(
                "DELETE FROM sporttery_pool_snapshots WHERE match_id IN (?, ?, ?, ?)",
                FIRST_MATCH_ID,
                SECOND_MATCH_ID,
                ROLLBACK_FIRST_MATCH_ID,
                ROLLBACK_SECOND_MATCH_ID
        );
        jdbcTemplate.update(
                "DELETE FROM asian_odds_snapshots WHERE match_id IN (?, ?, ?, ?)",
                FIRST_MATCH_ID,
                SECOND_MATCH_ID,
                ROLLBACK_FIRST_MATCH_ID,
                ROLLBACK_SECOND_MATCH_ID
        );
        jdbcTemplate.update(
                "DELETE FROM match_source_mappings WHERE match_id IN (?, ?, ?, ?)",
                FIRST_MATCH_ID,
                SECOND_MATCH_ID,
                ROLLBACK_FIRST_MATCH_ID,
                ROLLBACK_SECOND_MATCH_ID
        );
        jdbcTemplate.update(
                "DELETE FROM matches WHERE id IN (?, ?, ?, ?)",
                FIRST_MATCH_ID,
                SECOND_MATCH_ID,
                ROLLBACK_FIRST_MATCH_ID,
                ROLLBACK_SECOND_MATCH_ID
        );
    }

    @Test
    void importsFixedSampleTwiceWithoutDuplicatingPredictions() throws Exception {
        // 1) 建立样例引用的未来比赛，导入过程不访问任何 Provider
        insertFutureMatch(FIRST_MATCH_ID, "T302-001");
        insertFutureMatch(SECOND_MATCH_ID, "T302-002");
        byte[] sample = sampleBytes();

        // 2) 首次导入新增两条 DRAFT，第二次原样重放复用同一组主键
        PredictionImportResultDto first = predictionImportService.importFile(sample);
        PredictionImportResultDto second = predictionImportService.importFile(sample);

        assertThat(first.generationBatchHash()).isEqualTo(sha256(sample));
        assertThat(first.totalCount()).isEqualTo(2);
        assertThat(first.insertedCount()).isEqualTo(2);
        assertThat(first.reusedCount()).isZero();
        assertThat(second.insertedCount()).isZero();
        assertThat(second.reusedCount()).isEqualTo(2);
        assertThat(second.predictionIds()).containsExactlyElementsOf(first.predictionIds());

        // 3) 从真实 PostgreSQL 读回版本、枚举、数值精度与草稿生命周期字段
        List<Prediction> saved = predictionMapper.selectList(new LambdaQueryWrapper<Prediction>()
                .eq(Prediction::getGenerationBatchId, first.generationBatchId())
                .orderByAsc(Prediction::getMatchId));
        assertThat(saved).hasSize(2);
        assertThat(saved).extracting(Prediction::getPredictionVersion).containsOnly(1);
        assertThat(saved).extracting(Prediction::getPredictionStatus)
                .containsOnly(PredictionStatusEnum.DRAFT);
        assertThat(saved).extracting(Prediction::getHandicapPick)
                .containsExactly(HandicapPickEnum.HOME_WIN, HandicapPickEnum.AWAY_WIN);
        assertThat(saved).extracting(Prediction::getConfidenceLevel)
                .containsExactly(ConfidenceLevelEnum.HIGH, ConfidenceLevelEnum.MEDIUM);
        assertThat(saved).allSatisfy(prediction -> {
            assertThat(prediction.getGenerationBatchHash()).isEqualTo(first.generationBatchHash());
            assertThat(prediction.getHomeWinProb().scale()).isEqualTo(6);
            assertThat(prediction.getExpectedTotalGoals().scale()).isEqualTo(2);
            assertThat(prediction.getPublishTime()).isNull();
            assertThat(prediction.getLockTime()).isNull();
            assertThat(prediction.getPredictionHash()).isNull();
        });
    }

    @Test
    void generatesBaselineOnlyForConfirmedMappedMarketsAndReusesSameDraftBatch() {
        // 1) 同一业务日准备两场完整盘口，只有一场拥有已确认外部赛事映射
        prepareBaselineGenerationFixtures();

        // 2) 首次运行只为已确认比赛导入 DRAFT；未确认比赛保持可解释跳过
        BaselinePredictionGenerationVo first = baselinePredictionGenerationService.generateAndImport(
                LocalDate.of(2026, 7, 27),
                "t306-it"
        );

        assertThat(first.candidateCount()).isEqualTo(2);
        assertThat(first.generatedCount()).isEqualTo(1);
        assertThat(first.insertedCount()).isEqualTo(1);
        assertThat(first.reusedCount()).isZero();
        assertThat(first.skippedByReason()).containsEntry(
                BaselinePredictionSkipReasonEnum.MISSING_CONFIRMED_ASIAN_MARKET,
                1
        );
        Prediction saved = predictionMapper.selectOne(new LambdaQueryWrapper<Prediction>()
                .eq(Prediction::getGenerationBatchId, first.generationBatchId()));
        assertThat(saved.getMatchId()).isEqualTo(FIRST_MATCH_ID);
        assertThat(saved.getPredictionStatus()).isEqualTo(PredictionStatusEnum.DRAFT);
        assertThat(saved.getModelVersion()).isEqualTo("t306-odds-baseline-v2");
        assertThat(saved.getFeatureVersion()).isEqualTo("t306-sporttery-asian-v2");
        assertThat(saved.getAsianOddsSnapshotId()).isNotNull();
        assertThat(saved.getAsianHandicapPick()).isEqualTo(AsianHandicapPickEnum.HOME_COVER);
        assertThat(saved.getTotalGoalsPick()).isEqualTo(TotalGoalsPickEnum.OVER);
        assertThat(saved.getHomeWinProb().add(saved.getDrawProb()).add(saved.getAwayWinProb()))
                .isEqualByComparingTo("1.000000");

        // 3) 输入快照未变化时重跑复用同一批次，不产生第二条预测版本
        BaselinePredictionGenerationVo second = baselinePredictionGenerationService.generateAndImport(
                LocalDate.of(2026, 7, 27),
                "t306-it"
        );
        assertThat(second.generationBatchId()).isEqualTo(first.generationBatchId());
        assertThat(second.generationBatchHash()).isEqualTo(first.generationBatchHash());
        assertThat(second.insertedCount()).isZero();
        assertThat(second.reusedCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM predictions WHERE generation_batch_id = ?",
                Integer.class,
                first.generationBatchId()
        )).isEqualTo(1);

        // 4) 生成器只留下草稿；仍须由既有发布、到期锁定流程推进生命周期
        PredictionPublishResultVo published = predictionPublishService.publish(
                new PredictionPublishDto(saved.getId()),
                "t306-it"
        );
        assertThat(published.predictionStatus()).isEqualTo(PredictionStatusEnum.PUBLISHED);
        assertThat(published.predictionHash()).hasSize(64);

        PredictionLockResultDto locked = predictionLockService.lockDuePredictions(10);
        assertThat(locked.lockedCount()).isEqualTo(1);
        assertThat(locked.failedCount()).isZero();
        assertThat(locked.lockedPredictionIds()).containsExactly(saved.getId());

        Prediction lockedPrediction = predictionMapper.selectById(saved.getId());
        assertThat(lockedPrediction.getPredictionStatus()).isEqualTo(PredictionStatusEnum.LOCKED);
        assertThat(lockedPrediction.getPublishTime()).isNotNull();
        assertThat(lockedPrediction.getLockTime()).isNotNull();
        assertThat(lockedPrediction.getPredictionHash()).isEqualTo(published.predictionHash());
    }

    @Test
    void concurrentBaselineGenerationsReuseOneStableDraftBatch() throws Exception {
        // 1) 两个管理请求同时生成同一业务日，输入特征和批次标识完全一致
        prepareBaselineGenerationFixtures();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<BaselinePredictionGenerationVo> first = executor.submit(
                    () -> generateAfterBarrier(ready, start)
            );
            Future<BaselinePredictionGenerationVo> second = executor.submit(
                    () -> generateAfterBarrier(ready, start)
            );
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            BaselinePredictionGenerationVo firstResult = first.get(30, TimeUnit.SECONDS);
            BaselinePredictionGenerationVo secondResult = second.get(30, TimeUnit.SECONDS);

            // 2) 事务级批次锁使后到请求复用首批结果，绝不增加第二条预测版本
            assertThat(firstResult.generationBatchId()).isEqualTo(secondResult.generationBatchId());
            assertThat(firstResult.generationBatchHash()).isEqualTo(secondResult.generationBatchHash());
            assertThat(firstResult.insertedCount() + secondResult.insertedCount()).isEqualTo(1);
            assertThat(firstResult.reusedCount() + secondResult.reusedCount()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM predictions WHERE match_id = ?",
                Integer.class,
                FIRST_MATCH_ID
        )).isEqualTo(1);
    }

    @Test
    void rollsBackWholeWriterBatchWhenSecondPredictionViolatesConstraint() {
        // 1) 准备两个合法外键目标，首条预测合法、第二条概率和违反 V7 约束
        insertFutureMatch(ROLLBACK_FIRST_MATCH_ID, "T302-ROLLBACK-001");
        insertFutureMatch(ROLLBACK_SECOND_MATCH_ID, "T302-ROLLBACK-002");
        Prediction first = draftPrediction(ROLLBACK_FIRST_MATCH_ID, "rollback-model-v1", "0.4");
        Prediction invalidSecond = draftPrediction(
                ROLLBACK_SECOND_MATCH_ID,
                "rollback-model-v1",
                "0.2"
        );

        // 2) 第二条写入失败后，事务必须同时撤销已经执行的第一条 INSERT
        assertThatThrownBy(() -> predictionImportWriter.writeAll(List.of(first, invalidSecond)))
                .isInstanceOf(DataIntegrityViolationException.class);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM predictions WHERE generation_batch_id = 't302-rollback-batch'",
                Integer.class
        );
        assertThat(count).isZero();
    }

    private void insertFutureMatch(long id, String lotteryMatchNo) {
        jdbcTemplate.update(
                """
                INSERT INTO matches (
                    id,
                    lottery_match_no,
                    lottery_date,
                    league_name,
                    home_team_name,
                    away_team_name,
                    kickoff_time,
                    match_status
                )
                VALUES (?, ?, DATE '2026-07-27', 'T302 联赛', 'T302 主队', 'T302 客队',
                        TIMESTAMPTZ '2026-07-27 12:00:00+08', 'SCHEDULED')
                """,
                id,
                lotteryMatchNo
        );
    }

    private Prediction draftPrediction(long matchId, String modelVersion, String homeProbability) {
        Prediction prediction = new Prediction();
        prediction.setMatchId(matchId);
        prediction.setModelVersion(modelVersion);
        prediction.setFeatureVersion("rollback-feature-v1");
        prediction.setGenerationBatchId("t302-rollback-batch");
        prediction.setGenerationBatchHash("c".repeat(64));
        prediction.setPredictionVersion(1);
        prediction.setHomeWinProb(new BigDecimal(homeProbability));
        prediction.setDrawProb(new BigDecimal("0.3"));
        prediction.setAwayWinProb(new BigDecimal("0.3"));
        prediction.setHandicapPick(HandicapPickEnum.DRAW);
        prediction.setExpectedTotalGoals(new BigDecimal("2.25"));
        prediction.setConfidenceLevel(ConfidenceLevelEnum.LOW);
        prediction.setAnalysisSummary("事务回滚验证");
        prediction.setGeneratedAt(Instant.parse("2026-07-26T01:00:00Z"));
        prediction.setPredictionStatus(PredictionStatusEnum.DRAFT);
        return prediction;
    }

    private void prepareBaselineGenerationFixtures() {
        insertFutureMatch(FIRST_MATCH_ID, "T306-001");
        insertFutureMatch(SECOND_MATCH_ID, "T306-002");
        insertBaselineMarkets(FIRST_MATCH_ID, "a", "b");
        insertBaselineMarkets(SECOND_MATCH_ID, "c", "d");
        jdbcTemplate.update(
                """
                INSERT INTO match_source_mappings (
                    match_id, provider_code, external_match_id, mapping_status,
                    mapping_confidence, mapping_method, confirmed_by
                ) VALUES (?, 'STUB', 't306-confirmed-001', 'MANUAL_CONFIRMED',
                          1.0000, 'MANUAL_REVIEW', 't306-it')
                """,
                FIRST_MATCH_ID
        );
    }

    private BaselinePredictionGenerationVo generateAfterBarrier(
            CountDownLatch ready,
            CountDownLatch start
    ) {
        ready.countDown();
        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent generation start timed out");
            }
            return baselinePredictionGenerationService.generateAndImport(
                    LocalDate.of(2026, 7, 27),
                    "t306-concurrent-it"
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("concurrent generation was interrupted", exception);
        }
    }

    private void insertBaselineMarkets(long matchId, String sportteryHash, String asianHash) {
        jdbcTemplate.update(
                """
                INSERT INTO sporttery_pool_snapshots (
                    match_id, lottery_match_no, lottery_date, official_handicap,
                    had_home_sp, had_draw_sp, had_away_sp,
                    hhad_home_sp, hhad_draw_sp, hhad_away_sp,
                    captured_at, raw_payload_hash
                ) VALUES (?, ?, DATE '2026-07-27', -1.00,
                          2.00, 4.00, 4.00,
                          1.70, 3.20, 5.00,
                          TIMESTAMPTZ '2026-07-26 09:00:00+08', ?)
                """,
                matchId,
                "T306-" + matchId,
                sportteryHash.repeat(64)
        );
        jdbcTemplate.update(
                """
                INSERT INTO asian_odds_snapshots (
                    match_id, provider_code, bookmaker_code,
                    handicap_line, home_odds, away_odds,
                    total_line, over_odds, under_odds,
                    snapshot_type, captured_at, raw_payload_hash
                ) VALUES (?, 'STUB', 't306-bookmaker',
                          -0.50, 1.90, 1.90,
                          2.50, 1.80, 2.00,
                          'FIRST_SEEN', TIMESTAMPTZ '2026-07-26 10:00:00+08', ?)
                """,
                matchId,
                asianHash.repeat(64)
        );
    }

    private byte[] sampleBytes() throws IOException {
        try (var input = getClass().getResourceAsStream(
                "/prediction/prediction-import-valid.json"
        )) {
            if (input == null) {
                throw new IOException("prediction import sample is missing");
            }
            return input.readAllBytes();
        }
    }

    private String sha256(byte[] content) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        @Bean
        Clock fixedPredictionImportClock() {
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        }
    }
}
