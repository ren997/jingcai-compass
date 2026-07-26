package com.jingcaicompass.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jingcaicompass.prediction.dto.PredictionLockResultDto;
import com.jingcaicompass.prediction.dto.PredictionPublishDto;
import com.jingcaicompass.prediction.service.PredictionLockService;
import com.jingcaicompass.prediction.service.PredictionPublishService;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.ArrayList;
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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL 16 验证到期锁定、不可变触发器、并发抢占和单条事务隔离。 */
@Testcontainers
@ActiveProfiles("integration")
@SpringBootTest
class PredictionLockApplicationIT {

    private static final String POSTGRES_IMAGE = "postgres:16-alpine";
    private static final long ID_BASE = 3_040_000L;
    private static final long MATCH_OFFSET = 100_000L;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(POSTGRES_IMAGE)
                    .withDatabaseName("jingcai_prediction_lock")
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
    private PredictionLockService predictionLockService;

    @Autowired
    private PredictionPublishService predictionPublishService;

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

        // 2) 清理本类故障注入对象和固定测试键，保证失败重跑互不影响
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_t304_reject_audit ON audit_logs");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS reject_t304_lock_audit()");
        jdbcTemplate.update(
                "DELETE FROM audit_logs WHERE target_type = 'PREDICTION' "
                        + "AND CAST(target_id AS BIGINT) BETWEEN ? AND ?",
                ID_BASE,
                ID_BASE + 999
        );
        jdbcTemplate.update(
                "DELETE FROM predictions WHERE id BETWEEN ? AND ?",
                ID_BASE,
                ID_BASE + 999
        );
        jdbcTemplate.update(
                "DELETE FROM matches WHERE id BETWEEN ? AND ?",
                ID_BASE + MATCH_OFFSET,
                ID_BASE + MATCH_OFFSET + 999
        );
    }

    @Test
    void locksOnlyDuePredictionsAndDatabaseTriggerProtectsPublishedContent() {
        long dueId = ID_BASE + 1;
        long futureId = ID_BASE + 2;
        insertPublished(dueId, "- INTERVAL '1 minute'");
        insertPublished(futureId, "+ INTERVAL '10 minutes'");

        // 1) 数据库时间已到的预测进入 LOCKED，未来记录保持 PUBLISHED
        PredictionLockResultDto first = predictionLockService.lockDuePredictions(100);
        assertThat(first.lockedPredictionIds()).containsExactly(dueId);
        assertThat(first.failedCount()).isZero();
        assertThat(statusOf(dueId)).isEqualTo("LOCKED");
        assertThat(statusOf(futureId)).isEqualTo("PUBLISHED");
        assertThat(lockAuditCount(dueId)).isEqualTo(1);

        // 2) 重复任务不重复更新或审计
        PredictionLockResultDto repeated = predictionLockService.lockDuePredictions(100);
        assertThat(repeated.lockedCount()).isZero();
        assertThat(repeated.failedCount()).isZero();
        assertThat(lockAuditCount(dueId)).isEqualTo(1);

        // 3) LOCKED 和 PUBLISHED 的核心内容均受数据库触发器保护
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE predictions SET analysis_summary = 'tampered' WHERE id = ?",
                dueId
        )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE predictions SET home_win_prob = 0.46, away_win_prob = 0.24 WHERE id = ?",
                futureId
        )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE predictions SET prediction_status = 'PUBLISHED' WHERE id = ?",
                dueId
        )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE predictions SET prediction_status = 'LOCKED' WHERE id = ?",
                futureId
        )).isInstanceOf(DataAccessException.class);
    }

    @Test
    void concurrentWorkersLockAndAuditEachPredictionExactlyOnce() throws Exception {
        List<Long> predictionIds = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            long predictionId = ID_BASE + 100 + index;
            predictionIds.add(predictionId);
            insertPublished(predictionId, "- INTERVAL '1 minute'");
        }

        // 1) 两个任务实例同时抢占同一批到期预测
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PredictionLockResultDto> first =
                    executor.submit(() -> lockAfterBarrier(20, ready, start));
            Future<PredictionLockResultDto> second =
                    executor.submit(() -> lockAfterBarrier(20, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            PredictionLockResultDto firstResult = first.get(30, TimeUnit.SECONDS);
            PredictionLockResultDto secondResult = second.get(30, TimeUnit.SECONDS);
            assertThat(firstResult.lockedCount() + secondResult.lockedCount()).isEqualTo(12);
            assertThat(firstResult.failedCount() + secondResult.failedCount()).isZero();
        } finally {
            executor.shutdownNow();
        }

        // 2) 所有记录只锁定一次且每条只有一条 LOCK 审计
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM predictions "
                        + "WHERE id BETWEEN ? AND ? AND prediction_status = 'LOCKED'",
                Integer.class,
                ID_BASE + 100,
                ID_BASE + 111
        )).isEqualTo(12);
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM (
                    SELECT target_id
                    FROM audit_logs
                    WHERE target_type = 'PREDICTION'
                      AND action_type = 'LOCK'
                      AND CAST(target_id AS BIGINT) BETWEEN ? AND ?
                    GROUP BY target_id
                    HAVING COUNT(*) = 1
                ) audited_once
                """,
                Integer.class,
                ID_BASE + 100,
                ID_BASE + 111
        )).isEqualTo(12);
    }

    @Test
    void auditFailureRollsBackOnlyThatPredictionAndContinuesBatch() {
        long failedId = ID_BASE + 200;
        long successfulId = ID_BASE + 201;
        insertPublished(failedId, "- INTERVAL '2 minutes'");
        insertPublished(successfulId, "- INTERVAL '1 minute'");
        installAuditFailure(failedId);

        try {
            PredictionLockResultDto result = predictionLockService.lockDuePredictions(2);

            assertThat(result.lockedPredictionIds()).containsExactly(successfulId);
            assertThat(result.failedPredictionIds()).containsExactly(failedId);
            assertThat(statusOf(failedId)).isEqualTo("PUBLISHED");
            assertThat(statusOf(successfulId)).isEqualTo("LOCKED");
            assertThat(lockAuditCount(failedId)).isZero();
            assertThat(lockAuditCount(successfulId)).isEqualTo(1);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_t304_reject_audit ON audit_logs");
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS reject_t304_lock_audit()");
        }
    }

    @Test
    void concurrentDraftPublishAndLockPreservesTheOnlyLegalPublishedState() throws Exception {
        long predictionId = ID_BASE + 300;
        insertDraftForFutureMatch(predictionId);

        // 1) DRAFT 在发布前仍可通过明确写入修改核心内容
        assertThat(jdbcTemplate.update(
                "UPDATE predictions SET analysis_summary = 'updated draft' WHERE id = ?",
                predictionId
        )).isEqualTo(1);

        // 2) 发布与锁定任务同时运行；草稿只能发布，未来锁定时间不能提前锁定
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> publish = executor.submit(() -> {
                awaitBarrier(ready, start);
                return predictionPublishService.publish(
                        new PredictionPublishDto(predictionId),
                        "integration-admin"
                );
            });
            Future<PredictionLockResultDto> lock =
                    executor.submit(() -> lockAfterBarrier(10, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            publish.get(30, TimeUnit.SECONDS);
            assertThat(lock.get(30, TimeUnit.SECONDS).lockedCount()).isZero();
        } finally {
            executor.shutdownNow();
        }

        assertThat(statusOf(predictionId)).isEqualTo("PUBLISHED");
        assertThat(lockAuditCount(predictionId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_logs
                WHERE target_type = 'PREDICTION'
                  AND target_id = ?
                  AND action_type = 'PUBLISH'
                """,
                Integer.class,
                String.valueOf(predictionId)
        )).isEqualTo(1);
    }

    @Test
    void concurrentIllegalModificationCannotBeatDueLock() throws Exception {
        long predictionId = ID_BASE + 400;
        insertPublished(predictionId, "- INTERVAL '1 minute'");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> modificationRejected = executor.submit(() -> {
                awaitBarrier(ready, start);
                try {
                    jdbcTemplate.update(
                            "UPDATE predictions SET analysis_summary = 'racing tamper' WHERE id = ?",
                            predictionId
                    );
                    return false;
                } catch (DataAccessException exception) {
                    return true;
                }
            });
            Future<PredictionLockResultDto> lock =
                    executor.submit(() -> lockAfterBarrier(10, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(modificationRejected.get(30, TimeUnit.SECONDS)).isTrue();
            assertThat(lock.get(30, TimeUnit.SECONDS).lockedPredictionIds())
                    .containsExactly(predictionId);
        } finally {
            executor.shutdownNow();
        }

        assertThat(statusOf(predictionId)).isEqualTo("LOCKED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT analysis_summary FROM predictions WHERE id = ?",
                String.class,
                predictionId
        )).isEqualTo("T304 integration prediction");
        assertThat(lockAuditCount(predictionId)).isEqualTo(1);
    }

    private PredictionLockResultDto lockAfterBarrier(
            int batchSize,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("prediction lock concurrency barrier timed out");
        }
        return predictionLockService.lockDuePredictions(batchSize);
    }

    private void awaitBarrier(
            CountDownLatch ready,
            CountDownLatch start
    ) {
        ready.countDown();
        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("prediction concurrency barrier timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("prediction concurrency barrier interrupted", exception);
        }
    }

    private void insertPublished(long predictionId, String lockTimeExpression) {
        long matchId = predictionId + MATCH_OFFSET;
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
                VALUES (?, ?, CURRENT_DATE, 'T304 联赛', 'T304 主队', 'T304 客队',
                        CURRENT_TIMESTAMP %s, 'LOCKED')
                """.formatted(lockTimeExpression),
                matchId,
                "T304-" + predictionId
        );
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
                    prediction_status,
                    publish_time,
                    lock_time,
                    prediction_hash
                )
                VALUES (?, ?, 'model-t304', 'feature-t304', ?, ?, 1,
                        0.45, 0.30, 0.25, 'HOME_WIN', 2.50, 'HIGH',
                        'T304 integration prediction', CURRENT_TIMESTAMP - INTERVAL '3 hours',
                        'PUBLISHED', CURRENT_TIMESTAMP - INTERVAL '2 hours',
                        CURRENT_TIMESTAMP %s, ?)
                """.formatted(lockTimeExpression),
                predictionId,
                matchId,
                "t304-batch-" + predictionId,
                "a".repeat(64),
                "b".repeat(64)
        );
    }

    private String statusOf(long predictionId) {
        return jdbcTemplate.queryForObject(
                "SELECT prediction_status FROM predictions WHERE id = ?",
                String.class,
                predictionId
        );
    }

    private void insertDraftForFutureMatch(long predictionId) {
        long matchId = predictionId + MATCH_OFFSET;
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
                VALUES (?, ?, CURRENT_DATE, 'T304 联赛', 'T304 主队', 'T304 客队',
                        CURRENT_TIMESTAMP + INTERVAL '10 minutes', 'SCHEDULED')
                """,
                matchId,
                "T304-" + predictionId
        );
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
                VALUES (?, ?, 'model-t304', 'feature-t304', ?, ?, 1,
                        0.45, 0.30, 0.25, 'HOME_WIN', 2.50, 'HIGH',
                        'T304 draft prediction', CURRENT_TIMESTAMP - INTERVAL '1 minute', 'DRAFT')
                """,
                predictionId,
                matchId,
                "t304-batch-" + predictionId,
                "a".repeat(64)
        );
    }

    private Integer lockAuditCount(long predictionId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_logs
                WHERE target_type = 'PREDICTION'
                  AND target_id = ?
                  AND action_type = 'LOCK'
                """,
                Integer.class,
                String.valueOf(predictionId)
        );
    }

    private void installAuditFailure(long predictionId) {
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION reject_t304_lock_audit()
                RETURNS TRIGGER
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    IF NEW.action_type = 'LOCK' AND NEW.target_id = '%d' THEN
                        RAISE EXCEPTION 'injected T304 audit failure';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """.formatted(predictionId));
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_t304_reject_audit
                    BEFORE INSERT ON audit_logs
                    FOR EACH ROW
                    EXECUTE FUNCTION reject_t304_lock_audit()
                """);
    }
}
