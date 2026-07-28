package com.jingcaicompass.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jingcaicompass.match.entity.MatchEntity;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import com.jingcaicompass.match.mapper.MatchMapper;
import com.jingcaicompass.prediction.dto.PredictionDetailQueryDto;
import com.jingcaicompass.prediction.entity.Prediction;
import com.jingcaicompass.prediction.enums.ConfidenceLevelEnum;
import com.jingcaicompass.prediction.enums.HandicapPickEnum;
import com.jingcaicompass.prediction.enums.PredictionStatusEnum;
import com.jingcaicompass.prediction.mapper.PredictionMapper;
import com.jingcaicompass.prediction.service.PredictionContentHasher;
import com.jingcaicompass.prediction.service.PublicPredictionQueryService;
import com.jingcaicompass.snapshot.dto.PredictionSnapshotResultDto;
import com.jingcaicompass.snapshot.service.PredictionSnapshotService;
import com.jingcaicompass.system.exception.BusinessException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.sql.Connection;
import java.util.Comparator;
import java.util.UUID;
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

/** PostgreSQL 16 验证公开预测当前选择、版本链和快照精确关联。 */
@Testcontainers
@ActiveProfiles("integration")
@SpringBootTest
class PublicPredictionQueryApplicationIT {

    private static final String POSTGRES_IMAGE = "postgres:16-alpine";
    private static final long ID_BASE = 3_506_000L;
    private static final long MATCH_ID = ID_BASE + 100_000L;
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2036, 5, 6);
    private static final Path SNAPSHOT_ROOT = createSnapshotRoot();

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE)
            .withDatabaseName("jingcai_public_prediction")
            .withUsername("jingcai_test")
            .withPassword("jingcai_test");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
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
    private MatchMapper matchMapper;

    @Autowired
    private PredictionMapper predictionMapper;

    @Autowired
    private PredictionContentHasher predictionContentHasher;

    @Autowired
    private PredictionSnapshotService predictionSnapshotService;

    @Autowired
    private PublicPredictionQueryService publicPredictionQueryService;

    @BeforeEach
    void cleanFixture() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseMajorVersion()).isEqualTo(16);
        }
        jdbcTemplate.update("DELETE FROM prediction_snapshots WHERE snapshot_date = ?", BUSINESS_DATE);
        jdbcTemplate.update("DELETE FROM predictions WHERE id BETWEEN ? AND ?", ID_BASE, ID_BASE + 999);
        jdbcTemplate.update("DELETE FROM matches WHERE id = ?", MATCH_ID);
        clearSnapshotRoot();
    }

    @AfterAll
    static void removeSnapshotRoot() throws Exception {
        deleteRecursively(SNAPSHOT_ROOT);
    }

    @Test
    void returnsMultipleCurrentModelsPublicHistoryAndOnlyExactVerifiedSnapshot() throws Exception {
        insertMatch();
        insertPrediction(ID_BASE + 1, "model-alpha", 1, PredictionStatusEnum.PUBLISHED);
        insertPrediction(ID_BASE + 2, "model-alpha", 2, PredictionStatusEnum.LOCKED);
        insertPrediction(ID_BASE + 3, "model-beta", 1, PredictionStatusEnum.PUBLISHED);
        insertPrediction(ID_BASE + 4, "model-draft", 1, PredictionStatusEnum.DRAFT);

        PredictionSnapshotResultDto snapshot = predictionSnapshotService.publish(BUSINESS_DATE);
        var detail = publicPredictionQueryService.detail(new PredictionDetailQueryDto(MATCH_ID));

        assertThat(detail.modelPredictions()).hasSize(2);
        assertThat(detail.modelPredictions().get(0)).satisfies(model -> {
            assertThat(model.modelVersion()).isEqualTo("model-alpha");
            assertThat(model.currentPrediction().predictionId()).isEqualTo(ID_BASE + 2);
            assertThat(model.currentPrediction().replacesPredictionId()).isEqualTo(ID_BASE + 1);
            assertThat(model.currentPrediction().snapshot().snapshotId()).isEqualTo(snapshot.snapshotId());
            assertThat(model.historicalPredictions()).singleElement()
                    .satisfies(history -> assertThat(history.predictionId()).isEqualTo(ID_BASE + 1));
        });
        assertThat(detail.modelPredictions().get(1)).satisfies(model -> {
            assertThat(model.modelVersion()).isEqualTo("model-beta");
            assertThat(model.currentPrediction().predictionId()).isEqualTo(ID_BASE + 3);
            assertThat(model.currentPrediction().snapshot().snapshotId()).isEqualTo(snapshot.snapshotId());
        });
        assertThat(detail.modelPredictions()).extracting(model -> model.modelVersion())
                .doesNotContain("model-draft");

        Files.writeString(Path.of(URI.create(snapshot.fileUrl())), "corrupted", StandardCharsets.UTF_8);
        var damagedDetail = publicPredictionQueryService.detail(new PredictionDetailQueryDto(MATCH_ID));
        assertThat(damagedDetail.modelPredictions()).allSatisfy(model ->
                assertThat(model.currentPrediction().snapshot()).isNull());
        assertThat(publicPredictionQueryService.verifySnapshot(snapshot.snapshotId()).verified()).isFalse();
        assertThatThrownBy(() -> publicPredictionQueryService.openSnapshot(snapshot.snapshotId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).errorCode().code())
                        .isEqualTo("PREDICTION_SNAPSHOT_UNAVAILABLE"));
    }

    private void insertMatch() {
        MatchEntity match = new MatchEntity();
        match.setId(MATCH_ID);
        match.setLotteryMatchNo("T506-001");
        match.setLotteryDate(BUSINESS_DATE);
        match.setLeagueName("T506 联赛");
        match.setHomeTeamName("T506 主队");
        match.setAwayTeamName("T506 客队");
        match.setKickoffTime(Instant.parse("2036-05-06T12:00:00Z"));
        match.setMatchStatus(MatchStatusEnum.SCHEDULED);
        assertThat(matchMapper.insert(match)).isEqualTo(1);
    }

    private void insertPrediction(
            long predictionId,
            String modelVersion,
            int predictionVersion,
            PredictionStatusEnum status
    ) {
        Prediction prediction = new Prediction();
        prediction.setId(predictionId);
        prediction.setMatchId(MATCH_ID);
        prediction.setModelVersion(modelVersion);
        prediction.setFeatureVersion("feature-t506");
        prediction.setGenerationBatchId("t506-batch-" + predictionId);
        prediction.setGenerationBatchHash("a".repeat(64));
        prediction.setPredictionVersion(predictionVersion);
        prediction.setHomeWinProb(new BigDecimal("0.450000"));
        prediction.setDrawProb(new BigDecimal("0.300000"));
        prediction.setAwayWinProb(new BigDecimal("0.250000"));
        prediction.setHandicapPick(HandicapPickEnum.HOME_WIN);
        prediction.setExpectedTotalGoals(new BigDecimal("2.50"));
        prediction.setConfidenceLevel(ConfidenceLevelEnum.HIGH);
        prediction.setAnalysisSummary("T506 公开分析 " + predictionId);
        prediction.setGeneratedAt(Instant.parse("2036-05-01T00:00:00.123456Z"));
        prediction.setPredictionStatus(status);
        if (status != PredictionStatusEnum.DRAFT) {
            Instant publishTime = Instant.parse("2036-05-01T01:00:00.123456Z");
            Instant lockTime = Instant.parse("2036-05-06T12:00:00.000000Z");
            prediction.setPublishTime(publishTime);
            prediction.setLockTime(lockTime);
            prediction.setPredictionHash(predictionContentHasher.sha256Hex(prediction, publishTime, lockTime));
        }
        assertThat(predictionMapper.insert(prediction)).isEqualTo(1);
    }

    private static Path createSnapshotRoot() {
        try {
            return Files.createTempDirectory("jingcai-t506-" + UUID.randomUUID());
        } catch (Exception exception) {
            throw new IllegalStateException("failed to create T506 snapshot root", exception);
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
                    .forEach(PublicPredictionQueryApplicationIT::deleteQuietly);
        }
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(PublicPredictionQueryApplicationIT::deleteQuietly);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to clean T506 snapshot path", exception);
        }
    }
}
