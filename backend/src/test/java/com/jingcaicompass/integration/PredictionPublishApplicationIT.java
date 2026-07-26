package com.jingcaicompass.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jingcaicompass.prediction.dto.PredictionPublishDto;
import com.jingcaicompass.prediction.entity.Prediction;
import com.jingcaicompass.prediction.enums.PredictionStatusEnum;
import com.jingcaicompass.prediction.mapper.PredictionMapper;
import com.jingcaicompass.prediction.service.PredictionPublishService;
import com.jingcaicompass.prediction.vo.PredictionPublishResultVo;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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

/** PostgreSQL 16 验证预测连续发布、并发幂等和审计事务回滚。 */
@Testcontainers
@ActiveProfiles("integration")
@SpringBootTest
@Import(PredictionPublishApplicationIT.FixedClockConfiguration.class)
class PredictionPublishApplicationIT {

    private static final String POSTGRES_IMAGE = "postgres:16-alpine";
    private static final Instant FIXED_NOW = Instant.parse("2026-07-26T08:00:00Z");
    private static final Instant KICKOFF = Instant.parse("2026-07-26T12:00:00Z");
    private static final long MATCH_ID = 3_030_001L;
    private static final long FIRST_PREDICTION_ID = 3_031_001L;
    private static final long SECOND_PREDICTION_ID = 3_031_002L;
    private static final long ROLLBACK_MATCH_ID = 3_030_011L;
    private static final long ROLLBACK_PREDICTION_ID = 3_031_011L;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(POSTGRES_IMAGE)
                    .withDatabaseName("jingcai_prediction_publish")
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
    private PredictionPublishService predictionPublishService;

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

        // 2) 清理本类固定测试键，避免测试顺序或失败重跑影响结果
        jdbcTemplate.update(
                "DELETE FROM audit_logs WHERE target_type = 'PREDICTION' "
                        + "AND target_id IN (?, ?, ?)",
                String.valueOf(FIRST_PREDICTION_ID),
                String.valueOf(SECOND_PREDICTION_ID),
                String.valueOf(ROLLBACK_PREDICTION_ID)
        );
        jdbcTemplate.update(
                "DELETE FROM predictions WHERE id IN (?, ?, ?)",
                FIRST_PREDICTION_ID,
                SECOND_PREDICTION_ID,
                ROLLBACK_PREDICTION_ID
        );
        jdbcTemplate.update(
                "DELETE FROM matches WHERE id IN (?, ?)",
                MATCH_ID,
                ROLLBACK_MATCH_ID
        );
    }

    @Test
    void serializesConcurrentPublishAndPreservesVersionHistory() throws Exception {
        // 1) 准备未来比赛和 T302 已分配的 V1 草稿
        insertMatch(MATCH_ID, "T303-001");
        insertDraft(FIRST_PREDICTION_ID, MATCH_ID, 1, "t303-batch-v1");

        // 2) 两个线程同时发布同一草稿，只允许一次实际更新和审计
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PredictionPublishResultVo> first = executor.submit(
                    () -> publishAfterBarrier(FIRST_PREDICTION_ID, ready, start)
            );
            Future<PredictionPublishResultVo> second = executor.submit(
                    () -> publishAfterBarrier(FIRST_PREDICTION_ID, ready, start)
            );
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<PredictionPublishResultVo> results = List.of(
                    first.get(20, TimeUnit.SECONDS),
                    second.get(20, TimeUnit.SECONDS)
            );
            assertThat(results).extracting(PredictionPublishResultVo::predictionHash)
                    .containsOnly(results.getFirst().predictionHash());
            assertThat(results).extracting(PredictionPublishResultVo::reused)
                    .containsExactlyInAnyOrder(false, true);
        } finally {
            executor.shutdownNow();
        }

        Integer firstAuditCount = publishAuditCount(FIRST_PREDICTION_ID);
        assertThat(firstAuditCount).isEqualTo(1);
        Prediction firstPublished = predictionMapper.selectById(FIRST_PREDICTION_ID);
        assertThat(firstPublished.getPredictionStatus()).isEqualTo(PredictionStatusEnum.PUBLISHED);
        assertThat(firstPublished.getPublishTime()).isEqualTo(FIXED_NOW);
        assertThat(firstPublished.getLockTime()).isEqualTo(KICKOFF);
        assertThat(firstPublished.getPredictionHash()).matches("^[0-9a-f]{64}$");

        // 3) 导入的下一草稿版本可发布，V1 内容和哈希保持不变
        String firstHash = firstPublished.getPredictionHash();
        insertDraft(SECOND_PREDICTION_ID, MATCH_ID, 2, "t303-batch-v2");
        PredictionPublishResultVo secondVersion = predictionPublishService.publish(
                new PredictionPublishDto(SECOND_PREDICTION_ID),
                "integration-admin"
        );

        assertThat(secondVersion.predictionVersion()).isEqualTo(2);
        assertThat(secondVersion.reused()).isFalse();
        assertThat(secondVersion.predictionHash()).isNotEqualTo(firstHash);
        Prediction unchangedFirst = predictionMapper.selectById(FIRST_PREDICTION_ID);
        assertThat(unchangedFirst.getPredictionVersion()).isEqualTo(1);
        assertThat(unchangedFirst.getPredictionHash()).isEqualTo(firstHash);
        assertThat(publishAuditCount(SECOND_PREDICTION_ID)).isEqualTo(1);
    }

    @Test
    void rollsBackPublishedStateWhenAuditInsertFails() {
        // 1) 使用未来比赛和合法草稿进入发布更新
        insertMatch(ROLLBACK_MATCH_ID, "T303-ROLLBACK-001");
        insertDraft(
                ROLLBACK_PREDICTION_ID,
                ROLLBACK_MATCH_ID,
                1,
                "t303-rollback-batch"
        );

        // 2) 超长操作者触发 audit_logs 长度约束，预测更新必须随事务回滚
        assertThatThrownBy(() -> predictionPublishService.publish(
                new PredictionPublishDto(ROLLBACK_PREDICTION_ID),
                "x".repeat(65)
        )).isInstanceOf(DataIntegrityViolationException.class);

        Prediction rolledBack = predictionMapper.selectById(ROLLBACK_PREDICTION_ID);
        assertThat(rolledBack.getPredictionStatus()).isEqualTo(PredictionStatusEnum.DRAFT);
        assertThat(rolledBack.getPublishTime()).isNull();
        assertThat(rolledBack.getLockTime()).isNull();
        assertThat(rolledBack.getPredictionHash()).isNull();
        assertThat(publishAuditCount(ROLLBACK_PREDICTION_ID)).isZero();
    }

    private PredictionPublishResultVo publishAfterBarrier(
            long predictionId,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("publish concurrency barrier timed out");
        }
        return predictionPublishService.publish(
                new PredictionPublishDto(predictionId),
                "integration-admin"
        );
    }

    private Integer publishAuditCount(long predictionId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_logs
                WHERE target_type = 'PREDICTION'
                  AND target_id = ?
                  AND action_type = 'PUBLISH'
                """,
                Integer.class,
                String.valueOf(predictionId)
        );
    }

    private void insertMatch(long id, String lotteryMatchNo) {
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
                VALUES (?, ?, DATE '2026-07-26', 'T303 联赛', 'T303 主队', 'T303 客队',
                        CAST(? AS TIMESTAMPTZ), 'SCHEDULED')
                """,
                id,
                lotteryMatchNo,
                KICKOFF
        );
    }

    private void insertDraft(
            long predictionId,
            long matchId,
            int predictionVersion,
            String generationBatchId
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO predictions (
                    id,
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
                VALUES (?, ?, 'model-v1', 'feature-v1', ?, ?, ?, 0.45, 0.30, 0.25,
                        'HOME_WIN', 2.50, 'HIGH', ?, CAST(? AS TIMESTAMPTZ), 'DRAFT')
                """,
                predictionId,
                matchId,
                generationBatchId,
                "a".repeat(64),
                predictionVersion,
                "T303 V" + predictionVersion + " 发布集成验证",
                FIXED_NOW.minusSeconds(300)
        );
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        @Bean
        Clock fixedPredictionImportClock() {
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        }
    }
}
