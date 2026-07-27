package com.jingcaicompass.system.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.admin.mapper.AdminAccountMapper;
import com.jingcaicompass.admin.service.AdminAccountBootstrapRunner;
import com.jingcaicompass.admin.service.AdminAccountCredentialValidator;
import com.jingcaicompass.admin.service.AdminAccountTokenValidator;
import com.jingcaicompass.admin.service.AdminAuthService;
import com.jingcaicompass.audit.mapper.AuditLogMapper;
import com.jingcaicompass.audit.service.AuditLogService;
import com.jingcaicompass.data.job.DataPipelineSyncJob;
import com.jingcaicompass.data.mapper.DataProviderMapper;
import com.jingcaicompass.data.mapper.DataSyncRunMapper;
import com.jingcaicompass.data.mapper.RawDataPayloadMapper;
import com.jingcaicompass.data.service.DataPipelineService;
import com.jingcaicompass.data.service.DataProviderService;
import com.jingcaicompass.history.mapper.HistoryQueryMapper;
import com.jingcaicompass.history.service.HistoryQueryService;
import com.jingcaicompass.match.job.SportteryPoolSyncJob;
import com.jingcaicompass.match.mapper.MatchResultFactMapper;
import com.jingcaicompass.match.mapper.LeagueAliasMapper;
import com.jingcaicompass.match.mapper.LeagueMapper;
import com.jingcaicompass.match.mapper.MatchMapper;
import com.jingcaicompass.match.mapper.MatchSourceMappingMapper;
import com.jingcaicompass.match.mapper.ProviderLeagueMappingMapper;
import com.jingcaicompass.match.mapper.ProviderTeamMappingMapper;
import com.jingcaicompass.match.mapper.SportteryPoolSnapshotMapper;
import com.jingcaicompass.match.mapper.TeamAliasMapper;
import com.jingcaicompass.match.mapper.TeamMapper;
import com.jingcaicompass.match.service.MatchNormalizationBackfillService;
import com.jingcaicompass.match.service.MatchResultSyncService;
import com.jingcaicompass.match.service.SportteryPoolPayloadMapper;
import com.jingcaicompass.match.service.SportteryProvider;
import com.jingcaicompass.odds.client.AsianOddsProviderProperties;
import com.jingcaicompass.odds.job.AsianOddsSyncJob;
import com.jingcaicompass.odds.mapper.AsianOddsSnapshotMapper;
import com.jingcaicompass.odds.service.AsianOddsPayloadMapper;
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
import com.jingcaicompass.settlement.service.MarketSettlementCalculatorRouter;
import com.jingcaicompass.settlement.service.SettlementRecalculationService;
import com.jingcaicompass.settlement.service.SettlementService;
import com.jingcaicompass.settlement.service.SportteryHandicapSettlementCalculator;
import com.jingcaicompass.settlement.service.WinDrawLossSettlementCalculator;
import com.jingcaicompass.statistics.service.StatisticsQueryService;
import com.jingcaicompass.system.config.properties.PaginationProperties;
import com.jingcaicompass.system.config.properties.AdminSecurityProperties;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;

class PersistenceServicesAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PersistenceServicesAutoConfiguration.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(SportteryPoolPayloadMapper.class, () -> new SportteryPoolPayloadMapper(new ObjectMapper()))
            .withBean(AsianOddsPayloadMapper.class, () -> new AsianOddsPayloadMapper(new ObjectMapper()))
            .withBean(SportteryProvider.class, () -> mock(SportteryProvider.class))
            .withBean(AsianOddsProvider.class, () -> mock(AsianOddsProvider.class))
            .withBean(
                    AsianOddsProviderProperties.class,
                    () -> mock(AsianOddsProviderProperties.class)
            )
            .withBean(AdminSecurityProperties.class, PersistenceServicesAutoConfigurationTest::adminProperties)
            .withBean(AdminAccountCredentialValidator.class, AdminAccountCredentialValidator::new)
            .withBean(PasswordEncoder.class, () -> new BCryptPasswordEncoder(4))
            .withBean(JwtEncoder.class, () -> mock(JwtEncoder.class))
            .withBean(PaginationProperties.class, () -> new PaginationProperties(100))
            .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(AdminAccountMapper.class, PersistenceServicesAutoConfigurationTest::existingAdminMapper)
            .withBean(AuditLogMapper.class, () -> mock(AuditLogMapper.class))
            .withBean(DataProviderMapper.class, () -> mock(DataProviderMapper.class))
            .withBean(DataSyncRunMapper.class, () -> mock(DataSyncRunMapper.class))
            .withBean(RawDataPayloadMapper.class, () -> mock(RawDataPayloadMapper.class))
            .withBean(LeagueMapper.class, () -> mock(LeagueMapper.class))
            .withBean(LeagueAliasMapper.class, () -> mock(LeagueAliasMapper.class))
            .withBean(ProviderLeagueMappingMapper.class, () -> mock(ProviderLeagueMappingMapper.class))
            .withBean(TeamMapper.class, () -> mock(TeamMapper.class))
            .withBean(TeamAliasMapper.class, () -> mock(TeamAliasMapper.class))
            .withBean(ProviderTeamMappingMapper.class, () -> mock(ProviderTeamMappingMapper.class))
            .withBean(MatchMapper.class, () -> mock(MatchMapper.class))
            .withBean(MatchResultFactMapper.class, () -> mock(MatchResultFactMapper.class))
            .withBean(MatchSourceMappingMapper.class, () -> mock(MatchSourceMappingMapper.class))
            .withBean(SportteryPoolSnapshotMapper.class, () -> mock(SportteryPoolSnapshotMapper.class))
            .withBean(AsianOddsSnapshotMapper.class, () -> mock(AsianOddsSnapshotMapper.class))
            .withBean(PredictionMapper.class, () -> mock(PredictionMapper.class))
            .withBean(PredictionSnapshotMapper.class, () -> mock(PredictionSnapshotMapper.class))
            .withBean(SettlementMapper.class, () -> mock(SettlementMapper.class))
            .withBean(HistoryQueryMapper.class, () -> mock(HistoryQueryMapper.class))
            .withBean(
                    MarketSettlementCalculatorRouter.class,
                    () -> new MarketSettlementCalculatorRouter(List.of(
                            new WinDrawLossSettlementCalculator(),
                            new SportteryHandicapSettlementCalculator()
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
                    assertThat(context).hasSingleBean(MatchNormalizationBackfillService.class);
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
                    assertThat(context).hasSingleBean(AdminAuthService.class);
                    assertThat(context).hasSingleBean(AdminAccountTokenValidator.class);
                    assertThat(context).hasSingleBean(AdminAccountBootstrapRunner.class);
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
}
