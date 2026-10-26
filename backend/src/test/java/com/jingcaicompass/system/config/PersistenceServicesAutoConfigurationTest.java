package com.jingcaicompass.system.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.admin.mapper.AdminAccountMapper;
import com.jingcaicompass.admin.mapper.AdminDraftPredictionMapper;
import com.jingcaicompass.admin.mapper.AdminPredictionStatusMapper;
import com.jingcaicompass.admin.service.AdminAccountBootstrapRunner;
import com.jingcaicompass.admin.service.AdminAccountCredentialValidator;
import com.jingcaicompass.admin.service.AdminAccountTokenValidator;
import com.jingcaicompass.admin.service.AdminAuthService;
import com.jingcaicompass.admin.service.AdminDraftPredictionQueryService;
import com.jingcaicompass.admin.service.AdminPredictionStatusQueryService;
import com.jingcaicompass.admin.service.AdminSyncRunQueryService;
import com.jingcaicompass.audit.mapper.AuditLogMapper;
import com.jingcaicompass.audit.service.AuditLogService;
import com.jingcaicompass.backtest.BacktestProperties;
import com.jingcaicompass.backtest.mapper.BacktestCandidateMapper;
import com.jingcaicompass.backtest.mapper.BacktestCaseMapper;
import com.jingcaicompass.backtest.mapper.BacktestMetricMapper;
import com.jingcaicompass.backtest.mapper.BacktestRunMapper;
import com.jingcaicompass.backtest.mapper.OddsHistoryArchiveMapper;
import com.jingcaicompass.data.job.DataPipelineSyncJob;
import com.jingcaicompass.data.mapper.DataProviderMapper;
import com.jingcaicompass.data.mapper.DataSyncRunMapper;
import com.jingcaicompass.data.mapper.DataSyncRunPayloadMapper;
import com.jingcaicompass.data.mapper.RawDataPayloadMapper;
import com.jingcaicompass.data.service.DataPipelineService;
import com.jingcaicompass.data.service.DataProviderService;
import com.jingcaicompass.history.mapper.HistoryQueryMapper;
import com.jingcaicompass.history.service.HistoryQueryService;
import com.jingcaicompass.home.mapper.HomeSummaryMapper;
import com.jingcaicompass.home.service.HomeSummaryQueryService;
import com.jingcaicompass.match.job.SportteryPoolSyncJob;
import com.jingcaicompass.match.mapper.MatchResultFactMapper;
import com.jingcaicompass.match.mapper.LeagueMapper;
import com.jingcaicompass.match.mapper.MatchMapper;
import com.jingcaicompass.match.mapper.SportteryPoolSnapshotMapper;
import com.jingcaicompass.match.mapper.TeamMapper;
import com.jingcaicompass.match.service.MatchResultSyncService;
import com.jingcaicompass.match.service.SportteryPoolPayloadMapper;
import com.jingcaicompass.match.service.SportteryProvider;
import com.jingcaicompass.match.client.SportteryProviderProperties;
import com.jingcaicompass.odds.client.AsianModelProperties;
import com.jingcaicompass.odds.client.AsianOddsAggregationProperties;
import com.jingcaicompass.odds.client.AsianOddsProviderProperties;
import com.jingcaicompass.odds.job.AsianOddsSyncJob;
import com.jingcaicompass.odds.mapper.AsianOddsSnapshotMapper;
import com.jingcaicompass.odds.service.AsianOddsPayloadMapper;
import com.jingcaicompass.odds.service.NovaApiOddsPayloadMapper;
import com.jingcaicompass.odds.service.AsianOddsProvider;
import com.jingcaicompass.prediction.job.PredictionLockJob;
import com.jingcaicompass.prediction.mapper.PredictionMapper;
import com.jingcaicompass.prediction.service.PredictionImportFileParser;
import com.jingcaicompass.prediction.service.PredictionImportService;
import com.jingcaicompass.prediction.service.PredictionLockService;
import com.jingcaicompass.prediction.service.PredictionPublishService;
import com.jingcaicompass.snapshot.job.SnapshotPublishJob;
import com.jingcaicompass.snapshot.mapper.PredictionSnapshotMapper;
import com.jingcaicompass.snapshot.service.PredictionSnapshotService;
import com.jingcaicompass.snapshot.storage.SnapshotStorage;
import com.jingcaicompass.settlement.mapper.SettlementMapper;
import com.jingcaicompass.settlement.service.AsianHandicapSettlementCalculator;
import com.jingcaicompass.settlement.service.AsianTotalGoalsSettlementCalculator;
import com.jingcaicompass.settlement.service.MarketSettlementCalculatorRouter;
import com.jingcaicompass.settlement.service.SettlementRecalculationService;
import com.jingcaicompass.settlement.service.SettlementService;
import com.jingcaicompass.settlement.service.SportteryHandicapSettlementCalculator;
import com.jingcaicompass.settlement.service.WinDrawLossSettlementCalculator;
import com.jingcaicompass.statistics.service.StatisticsQueryService;
import com.jingcaicompass.system.config.properties.PaginationProperties;
import com.jingcaicompass.system.config.properties.SyncTaskProperties;
import com.jingcaicompass.system.config.properties.AdminSecurityProperties;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;

class PersistenceServicesAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PersistenceServicesAutoConfiguration.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(SportteryPoolPayloadMapper.class, () -> new SportteryPoolPayloadMapper(new ObjectMapper()))
            .withBean(AsianOddsPayloadMapper.class, () -> new AsianOddsPayloadMapper(new ObjectMapper(), new NovaApiOddsPayloadMapper()))
            .withBean(SportteryProvider.class, () -> mock(SportteryProvider.class))
            .withBean(SportteryProviderProperties.class, () -> mock(SportteryProviderProperties.class))
            .withBean(AsianOddsProvider.class, () -> mock(AsianOddsProvider.class))
            .withBean(
                    AsianOddsProviderProperties.class,
                    PersistenceServicesAutoConfigurationTest::asianOddsProviderProperties
            )
            .withBean(SyncTaskProperties.class, PersistenceServicesAutoConfigurationTest::taskProperties)
            .withBean(AdminSecurityProperties.class, PersistenceServicesAutoConfigurationTest::adminProperties)
            .withBean(AdminAccountCredentialValidator.class, AdminAccountCredentialValidator::new)
            .withBean(PasswordEncoder.class, () -> new BCryptPasswordEncoder(4))
            .withBean(JwtEncoder.class, () -> mock(JwtEncoder.class))
            .withBean(PaginationProperties.class, () -> new PaginationProperties(100))
            .withBean(AsianOddsAggregationProperties.class, AsianOddsAggregationProperties::defaults)
            .withBean(AsianModelProperties.class, AsianModelProperties::defaults)
            .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(AdminAccountMapper.class, PersistenceServicesAutoConfigurationTest::existingAdminMapper)
            .withBean(AdminDraftPredictionMapper.class, () -> mock(AdminDraftPredictionMapper.class))
            .withBean(AdminPredictionStatusMapper.class, () -> mock(AdminPredictionStatusMapper.class))
            .withBean(AuditLogMapper.class, () -> mock(AuditLogMapper.class))
            .withBean(DataProviderMapper.class, () -> mock(DataProviderMapper.class))
            .withBean(DataSyncRunMapper.class, () -> mock(DataSyncRunMapper.class))
            .withBean(DataSyncRunPayloadMapper.class, () -> mock(DataSyncRunPayloadMapper.class))
            .withBean(RawDataPayloadMapper.class, () -> mock(RawDataPayloadMapper.class))
            .withBean(LeagueMapper.class, () -> mock(LeagueMapper.class))
            .withBean(TeamMapper.class, () -> mock(TeamMapper.class))
            .withBean(MatchMapper.class, () -> mock(MatchMapper.class))
            .withBean(MatchResultFactMapper.class, () -> mock(MatchResultFactMapper.class))
            .withBean(BacktestRunMapper.class, () -> mock(BacktestRunMapper.class))
            .withBean(BacktestCaseMapper.class, () -> mock(BacktestCaseMapper.class))
            .withBean(BacktestMetricMapper.class, () -> mock(BacktestMetricMapper.class))
            .withBean(BacktestCandidateMapper.class, () -> mock(BacktestCandidateMapper.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            // 回测的属性组必须显式给：它是 @ConfigurationProperties，在这个 runner 里不会自动绑定。
            .withBean(BacktestProperties.class, BacktestProperties::defaults)
            .withBean(SportteryPoolSnapshotMapper.class, () -> mock(SportteryPoolSnapshotMapper.class))
            .withBean(AsianOddsSnapshotMapper.class, () -> mock(AsianOddsSnapshotMapper.class))
            .withBean(OddsHistoryArchiveMapper.class, () -> mock(OddsHistoryArchiveMapper.class))
            .withBean(PredictionMapper.class, () -> mock(PredictionMapper.class))
            .withBean(PredictionSnapshotMapper.class, () -> mock(PredictionSnapshotMapper.class))
            .withBean(SettlementMapper.class, () -> mock(SettlementMapper.class))
            .withBean(HistoryQueryMapper.class, () -> mock(HistoryQueryMapper.class))
            .withBean(HomeSummaryMapper.class, () -> mock(HomeSummaryMapper.class))
            .withBean(
                    MarketSettlementCalculatorRouter.class,
                    () -> new MarketSettlementCalculatorRouter(List.of(
                            new WinDrawLossSettlementCalculator(),
                            new SportteryHandicapSettlementCalculator(),
                            new AsianHandicapSettlementCalculator(),
                            new AsianTotalGoalsSettlementCalculator()
                    ))
            )
            .withBean(SnapshotStorage.class, () -> mock(SnapshotStorage.class))
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class));

    @Test
    void doesNotRegisterPersistenceServicesWithoutDataSource() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(DataPipelineService.class);
            assertThat(context).doesNotHaveBean(DataProviderService.class);
            assertThat(context).doesNotHaveBean(PredictionImportService.class);
            assertThat(context).doesNotHaveBean(PredictionPublishService.class);
            assertThat(context).doesNotHaveBean(PredictionLockService.class);
            assertThat(context).doesNotHaveBean(PredictionSnapshotService.class);
            assertThat(context).doesNotHaveBean(AdminAuthService.class);
        });
    }

    @Test
    void registersPersistenceServiceChainAfterDataSourceExists() {
        contextRunner
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(DataPipelineService.class);
                    assertThat(context).hasSingleBean(MatchResultSyncService.class);
                    assertThat(context).hasSingleBean(AuditLogService.class);
                    assertThat(context).hasSingleBean(PredictionImportFileParser.class);
                    assertThat(context).hasSingleBean(PredictionImportService.class);
                    assertThat(context).hasSingleBean(PredictionPublishService.class);
                    assertThat(context).hasSingleBean(PredictionLockService.class);
                    assertThat(context).hasSingleBean(PredictionSnapshotService.class);
                    assertThat(context).hasSingleBean(SettlementService.class);
                    assertThat(context).hasSingleBean(SettlementRecalculationService.class);
                    assertThat(context).hasSingleBean(HistoryQueryService.class);
                    assertThat(context).hasSingleBean(StatisticsQueryService.class);
                    assertThat(context).hasSingleBean(HomeSummaryQueryService.class);
                    assertThat(context).hasSingleBean(AdminAuthService.class);
                    assertThat(context).hasSingleBean(AdminAccountTokenValidator.class);
                    assertThat(context).hasSingleBean(AdminAccountBootstrapRunner.class);
                    assertThat(context).hasSingleBean(AdminDraftPredictionQueryService.class);
                    assertThat(context).hasSingleBean(AdminPredictionStatusQueryService.class);
                    assertThat(context).hasSingleBean(AdminSyncRunQueryService.class);
                    assertThat(context).doesNotHaveBean(DataPipelineSyncJob.class);
                    assertThat(context).doesNotHaveBean(PredictionLockJob.class);
                    assertThat(context).doesNotHaveBean(SnapshotPublishJob.class);
                });
    }

    @Test
    void registersOnlyEnabledPipelineJob() {
        contextRunner
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withPropertyValues(
                        "app.tasks.enabled=true",
                        "app.tasks.data-pipeline.enabled=true"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(DataPipelineSyncJob.class);
                    assertThat(context).doesNotHaveBean(SportteryPoolSyncJob.class);
                    assertThat(context).doesNotHaveBean(AsianOddsSyncJob.class);
                });
    }

    @Test
    void registersEnabledPredictionLockJobIndependently() {
        contextRunner
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withPropertyValues(
                        "app.tasks.enabled=true",
                        "app.tasks.prediction-lock.enabled=true"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(PredictionLockJob.class);
                    assertThat(context).doesNotHaveBean(DataPipelineSyncJob.class);
                });
    }

    @Test
    void registersEnabledSnapshotJobIndependently() {
        contextRunner
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withPropertyValues(
                        "app.tasks.enabled=true",
                        "app.tasks.snapshot-publish.enabled=true"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(SnapshotPublishJob.class);
                    assertThat(context).doesNotHaveBean(DataPipelineSyncJob.class);
                    assertThat(context).doesNotHaveBean(PredictionLockJob.class);
                });
    }

    private static AdminSecurityProperties adminProperties() {
        return new AdminSecurityProperties(
                new AdminSecurityProperties.JwtProperties(
                        "unused",
                        "issuer",
                        "audience",
                        Duration.ofMinutes(30)
                ),
                new AdminSecurityProperties.LoginProperties(5, Duration.ofMinutes(15)),
                new AdminSecurityProperties.BootstrapProperties("", "")
        );
    }

    private static AdminAccountMapper existingAdminMapper() {
        AdminAccountMapper mapper = mock(AdminAccountMapper.class);
        when(mapper.selectCount(any())).thenReturn(1L);
        return mapper;
    }

    /**
     * 用真实配置对象而不是 mock：{@code ActiveAsianOddsProvider} 需要
     * {@code provider().getCode()}，mock 出来的 record 会返回 null 并在建 bean 时 NPE。
     */
    private static AsianOddsProviderProperties asianOddsProviderProperties() {
        return new AsianOddsProviderProperties(
                com.jingcaicompass.odds.enums.AsianOddsProviderTypeEnum.STUB,
                0
        );
    }

    /**
     * 自动发布编排器需要任务配置里的 {@code regenerate-interval} 与 {@code operator}。
     * 用真实对象而不是 mock：mock 的 {@code predictionAutoPublish()} 返回 null，
     * 建 bean 时会直接 NPE。
     */
    private static SyncTaskProperties taskProperties() {
        Duration delay = Duration.ofMinutes(1);
        return new SyncTaskProperties(
                false,
                new SyncTaskProperties.SportteryPoolTaskProperties(false, delay, Duration.ZERO),
                new SyncTaskProperties.MatchResultTaskProperties(false, delay, Duration.ZERO, 7),
                new SyncTaskProperties.AsianOddsTaskProperties(false, delay, Duration.ZERO),
                new SyncTaskProperties.DataPipelineTaskProperties(false, delay, Duration.ZERO),
                new SyncTaskProperties.PredictionLockTaskProperties(false, Duration.ofSeconds(30), Duration.ZERO, 1),
                new SyncTaskProperties.SnapshotPublishTaskProperties(false, delay, Duration.ZERO),
                new SyncTaskProperties.SettlementTaskProperties(false, delay, Duration.ZERO, 1),
                new SyncTaskProperties.PredictionAutoPublishTaskProperties(
                        false,
                        Duration.ofMinutes(15),
                        Duration.ZERO,
                        Duration.ofHours(3),
                        "system:auto-publish"
                )
        );
    }
}
