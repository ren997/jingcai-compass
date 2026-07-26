package com.jingcaicompass.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jingcaicompass.prediction.entity.Prediction;
import com.jingcaicompass.prediction.enums.ConfidenceLevelEnum;
import com.jingcaicompass.prediction.enums.HandicapPickEnum;
import com.jingcaicompass.prediction.enums.PredictionStatusEnum;
import com.jingcaicompass.prediction.mapper.PredictionMapper;
import com.jingcaicompass.prediction.service.PredictionContentHasher;
import com.jingcaicompass.snapshot.dto.PredictionSnapshotResultDto;
import com.jingcaicompass.snapshot.enums.PredictionSnapshotStatusEnum;
import com.jingcaicompass.snapshot.service.PredictionSnapshotService;
import com.jingcaicompass.snapshot.service.SnapshotManifestGenerator;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
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

/** PostgreSQL 16 与本地文件系统联合验证快照版本、幂等、并发和失败恢复。 */
@Testcontainers
@ActiveProfiles("integration")
@SpringBootTest
class PredictionSnapshotApplicationIT {

    private static final String POSTGRES_IMAGE = "postgres:16-alpine";
    private static final long ID_BASE = 3_050_000L;
    private static final long MATCH_OFFSET = 100_000L;
    private static final Path SNAPSHOT_ROOT = createSnapshotRoot();

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(POSTGRES_IMAGE)
                    .withDatabaseName("jingcai_prediction_snapshot")
                    .withUsername("jingcai_test")
                    .withPassword("jingcai_test");

    @DynamicPropertySource
    static void registerPostgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.snapshot.storage.type", () -> "local");
        registry.add("app.snapshot.storage.path", SNAPSHOT_ROOT::toString);
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PredictionMapper predictionMapper;

    @Autowired
    private PredictionContentHasher predictionContentHasher;

    @Autowired
    private SnapshotManifestGenerator manifestGenerator;

    @Autowired
    private PredictionSnapshotService snapshotService;

    @BeforeEach
    void prepareIsolatedDatabaseAndStorage() throws Exception {
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

        // 2) 清理本类固定键、故障注入函数和临时文件，保证失败重跑互不影响
        jdbcTemplate.execute(
                "DROP TRIGGER IF EXISTS trg_t305_reject_snapshot_publish "
                        + "ON prediction_snapshots"
        );
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS reject_t305_snapshot_publish()");
        jdbcTemplate.update(
                "DELETE FROM prediction_snapshots "
                        + "WHERE snapshot_date BETWEEN DATE '2035-01-01' AND DATE '2035-01-31'"
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
        clearSnapshotRoot();
    }

    @AfterAll
    static void cleanSnapshotRoot() throws Exception {
        deleteRecursively(SNAPSHOT_ROOT);
    }

    @Test
    void publishesCurrentVersionsAndReusesIdenticalManifest() throws Exception {
        LocalDate businessDate = LocalDate.of(2035, 1, 5);
        long matchId = ID_BASE + MATCH_OFFSET + 1;
        insertMatch(matchId, businessDate);
        insertPrediction(ID_BASE + 1, matchId, "model-main", 1, PredictionStatusEnum.PUBLISHED);
        insertPrediction(ID_BASE + 2, matchId, "model-main", 2, PredictionStatusEnum.PUBLISHED);
        insertPrediction(ID_BASE + 3, matchId, "model-alt", 1, PredictionStatusEnum.LOCKED);
        insertPrediction(ID_BASE + 4, matchId, "model-draft", 1, PredictionStatusEnum.DRAFT);

        // 1) 首次发布只收录每个比赛和模型的最高公开版本
        PredictionSnapshotResultDto first = snapshotService.publish(businessDate);
        assertThat(first.snapshotStatus()).isEqualTo(PredictionSnapshotStatusEnum.PUBLISHED);
        assertThat(first.snapshotVersion()).isEqualTo(1);
        assertThat(first.predictionCount()).isEqualTo(2);
        assertThat(first.reused()).isFalse();
        String manifest = Files.readString(
                Path.of(URI.create(first.fileUrl())),
                StandardCharsets.UTF_8
        );
        assertThat(manifest)
                .contains("\"predictionId\":" + (ID_BASE + 2))
                .contains("\"predictionId\":" + (ID_BASE + 3))
                .doesNotContain("\"predictionId\":" + (ID_BASE + 1))
                .doesNotContain("\"predictionId\":" + (ID_BASE + 4));
        assertThat(snapshotHashFromDatabase(first.snapshotId())).isEqualTo(first.snapshotHash());
        assertThat(sha256(Files.readAllBytes(Path.of(URI.create(first.fileUrl())))))
                .isEqualTo(first.snapshotHash());

        // 2) 相同事实重复执行校验原文件后复用，不创建新版本
        PredictionSnapshotResultDto repeated = snapshotService.publish(businessDate);
        assertThat(repeated.snapshotId()).isEqualTo(first.snapshotId());
        assertThat(repeated.snapshotVersion()).isEqualTo(1);
        assertThat(repeated.reused()).isTrue();
        assertThat(snapshotCount(businessDate)).isEqualTo(1);
    }

    @Test
    void changedFactCreatesNextVersionAndDamagedFileCreatesRepairVersion() throws Exception {
        LocalDate businessDate = LocalDate.of(2035, 1, 6);
        long matchId = ID_BASE + MATCH_OFFSET + 10;
        insertMatch(matchId, businessDate);
        insertPrediction(ID_BASE + 10, matchId, "model-main", 1, PredictionStatusEnum.PUBLISHED);

        PredictionSnapshotResultDto first = snapshotService.publish(businessDate);
        insertPrediction(ID_BASE + 11, matchId, "model-main", 2, PredictionStatusEnum.PUBLISHED);
        PredictionSnapshotResultDto changed = snapshotService.publish(businessDate);

        assertThat(changed.snapshotVersion()).isEqualTo(2);
        assertThat(changed.snapshotHash()).isNotEqualTo(first.snapshotHash());
        assertThat(changed.reused()).isFalse();

        // 已发布文件损坏时不覆盖原对象，而是以相同事实发布修复版本
        Files.writeString(
                Path.of(URI.create(changed.fileUrl())),
                "corrupted",
                StandardCharsets.UTF_8
        );
        PredictionSnapshotResultDto repaired = snapshotService.publish(businessDate);
        assertThat(repaired.snapshotVersion()).isEqualTo(3);
        assertThat(repaired.snapshotHash()).isEqualTo(changed.snapshotHash());
        assertThat(repaired.objectKey()).isNotEqualTo(changed.objectKey());
        assertThat(repaired.reused()).isFalse();
        assertThat(snapshotCount(businessDate)).isEqualTo(3);
    }

    @Test
    void concurrentPublishersCreateOnlyOneSuccessfulVersion() throws Exception {
        LocalDate businessDate = LocalDate.of(2035, 1, 7);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PredictionSnapshotResultDto> first =
                    executor.submit(() -> publishAfterBarrier(businessDate, ready, start));
            Future<PredictionSnapshotResultDto> second =
                    executor.submit(() -> publishAfterBarrier(businessDate, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<PredictionSnapshotResultDto> results = List.of(
                    first.get(30, TimeUnit.SECONDS),
                    second.get(30, TimeUnit.SECONDS)
            );
            assertThat(results).extracting(PredictionSnapshotResultDto::snapshotId)
                    .containsOnly(results.getFirst().snapshotId());
            assertThat(results).extracting(PredictionSnapshotResultDto::reused)
                    .containsExactlyInAnyOrder(false, true);
        } finally {
            executor.shutdownNow();
        }
        assertThat(snapshotCount(businessDate)).isEqualTo(1);
    }

    @Test
    void damagedOrphanMarksFailedVersionAndNextRunPublishes() throws Exception {
        LocalDate businessDate = LocalDate.of(2035, 1, 8);
        var emptyManifest = manifestGenerator.generate(businessDate, List.of());
        String objectKey = "prediction-snapshots/"
                + businessDate
                + "/v000001-"
                + emptyManifest.sha256()
                + ".json";
        Path damagedTarget = SNAPSHOT_ROOT.resolve(objectKey);
        Files.createDirectories(damagedTarget.getParent());
        Files.writeString(damagedTarget, "damaged orphan", StandardCharsets.UTF_8);

        PredictionSnapshotResultDto failed = snapshotService.publish(businessDate);
        assertThat(failed.snapshotStatus()).isEqualTo(PredictionSnapshotStatusEnum.FAILED);
        assertThat(failed.snapshotVersion()).isEqualTo(1);
        assertThat(failed.failureReason()).contains("different or damaged");
        assertThat(Files.readString(damagedTarget)).isEqualTo("damaged orphan");

        PredictionSnapshotResultDto retry = snapshotService.publish(businessDate);
        assertThat(retry.snapshotStatus()).isEqualTo(PredictionSnapshotStatusEnum.PUBLISHED);
        assertThat(retry.snapshotVersion()).isEqualTo(2);
        assertThat(snapshotStatusCount(businessDate, "FAILED")).isEqualTo(1);
        assertThat(snapshotStatusCount(businessDate, "PUBLISHED")).isEqualTo(1);
    }

    @Test
    void databaseFinalizeFailureRollsBackMetadataAndRetryReusesOrphanFile() {
        LocalDate businessDate = LocalDate.of(2035, 1, 9);
        installFinalizeFailure();

        assertThatThrownBy(() -> snapshotService.publish(businessDate))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("injected T305 snapshot finalize failure");
        assertThat(snapshotCount(businessDate)).isZero();

        jdbcTemplate.execute(
                "DROP TRIGGER IF EXISTS trg_t305_reject_snapshot_publish "
                        + "ON prediction_snapshots"
        );
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS reject_t305_snapshot_publish()");

        PredictionSnapshotResultDto retry = snapshotService.publish(businessDate);
        assertThat(retry.snapshotStatus()).isEqualTo(PredictionSnapshotStatusEnum.PUBLISHED);
        assertThat(retry.snapshotVersion()).isEqualTo(1);
        assertThat(snapshotCount(businessDate)).isEqualTo(1);
    }

    private PredictionSnapshotResultDto publishAfterBarrier(
            LocalDate businessDate,
            CountDownLatch ready,
            CountDownLatch start
    ) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("snapshot concurrency barrier timed out");
        }
        return snapshotService.publish(businessDate);
    }

    private void insertMatch(long matchId, LocalDate businessDate) {
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
                VALUES (?, ?, ?, 'T305 联赛', 'T305 主队', 'T305 客队',
                        TIMESTAMPTZ '2035-01-31 10:00:00+08', 'SCHEDULED')
                """,
                matchId,
                "T305-" + matchId,
                businessDate
        );
    }

    private void insertPrediction(
            long predictionId,
            long matchId,
            String modelVersion,
            int predictionVersion,
            PredictionStatusEnum status
    ) {
        Prediction prediction = new Prediction();
        prediction.setId(predictionId);
        prediction.setMatchId(matchId);
        prediction.setModelVersion(modelVersion);
        prediction.setFeatureVersion("feature-t305");
        prediction.setGenerationBatchId("t305-batch-" + predictionId);
        prediction.setGenerationBatchHash("a".repeat(64));
        prediction.setPredictionVersion(predictionVersion);
        prediction.setHomeWinProb(new BigDecimal("0.450000"));
        prediction.setDrawProb(new BigDecimal("0.300000"));
        prediction.setAwayWinProb(new BigDecimal("0.250000"));
        prediction.setHandicapPick(HandicapPickEnum.HOME_WIN);
        prediction.setExpectedTotalGoals(new BigDecimal("2.50"));
        prediction.setConfidenceLevel(ConfidenceLevelEnum.HIGH);
        prediction.setAnalysisSummary("T305 集成预测 " + predictionId);
        prediction.setGeneratedAt(Instant.parse("2035-01-01T00:00:00.123456Z"));
        prediction.setPredictionStatus(status);
        if (status != PredictionStatusEnum.DRAFT) {
            Instant publishTime = Instant.parse("2035-01-01T01:00:00.123456Z");
            Instant lockTime = Instant.parse("2035-01-31T02:00:00.000000Z");
            prediction.setPublishTime(publishTime);
            prediction.setLockTime(lockTime);
            prediction.setPredictionHash(
                    predictionContentHasher.sha256Hex(prediction, publishTime, lockTime)
            );
        }
        assertThat(predictionMapper.insert(prediction)).isEqualTo(1);
    }

    private int snapshotCount(LocalDate businessDate) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM prediction_snapshots WHERE snapshot_date = ?",
                Integer.class,
                businessDate
        );
    }

    private int snapshotStatusCount(LocalDate businessDate, String status) {
        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM prediction_snapshots
                WHERE snapshot_date = ?
                  AND snapshot_status = ?
                """,
                Integer.class,
                businessDate,
                status
        );
    }

    private String snapshotHashFromDatabase(long snapshotId) {
        return jdbcTemplate.queryForObject(
                "SELECT snapshot_hash FROM prediction_snapshots WHERE id = ?",
                String.class,
                snapshotId
        );
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("failed to hash T305 snapshot file", exception);
        }
    }

    private void installFinalizeFailure() {
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION reject_t305_snapshot_publish()
                RETURNS TRIGGER
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    IF NEW.snapshot_status = 'PUBLISHED' THEN
                        RAISE EXCEPTION 'injected T305 snapshot finalize failure';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_t305_reject_snapshot_publish
                    BEFORE UPDATE ON prediction_snapshots
                    FOR EACH ROW
                    EXECUTE FUNCTION reject_t305_snapshot_publish()
                """);
    }

    private static Path createSnapshotRoot() {
        try {
            return Files.createTempDirectory("jingcai-t305-" + UUID.randomUUID());
        } catch (Exception exception) {
            throw new IllegalStateException("failed to create T305 snapshot directory", exception);
        }
    }

    private static void clearSnapshotRoot() throws Exception {
        if (!Files.exists(SNAPSHOT_ROOT)) {
            Files.createDirectories(SNAPSHOT_ROOT);
            return;
        }
        try (var paths = Files.walk(SNAPSHOT_ROOT)) {
            paths.sorted(Comparator.reverseOrder())
                    .filter(path -> !path.equals(SNAPSHOT_ROOT))
                    .forEach(PredictionSnapshotApplicationIT::deleteQuietly);
        }
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(PredictionSnapshotApplicationIT::deleteQuietly);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to clean T305 snapshot path: " + path, exception);
        }
    }
}
