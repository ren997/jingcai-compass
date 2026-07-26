package com.jingcaicompass.system.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.audit.mapper.AuditLogMapper;
import com.jingcaicompass.audit.service.AuditLogService;
import com.jingcaicompass.data.job.DataPipelineSyncJob;
import com.jingcaicompass.data.mapper.DataProviderMapper;
import com.jingcaicompass.data.mapper.DataSyncRunMapper;
import com.jingcaicompass.data.mapper.RawDataPayloadMapper;
import com.jingcaicompass.data.service.DataPipelineService;
import com.jingcaicompass.data.service.DataProviderService;
import com.jingcaicompass.match.job.SportteryPoolSyncJob;
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
import com.jingcaicompass.match.service.SportteryPoolPayloadMapper;
import com.jingcaicompass.match.service.SportteryProvider;
import com.jingcaicompass.odds.client.AsianOddsProviderProperties;
import com.jingcaicompass.odds.job.AsianOddsSyncJob;
import com.jingcaicompass.odds.mapper.AsianOddsSnapshotMapper;
import com.jingcaicompass.odds.service.AsianOddsPayloadMapper;
import com.jingcaicompass.odds.service.AsianOddsProvider;
import com.jingcaicompass.prediction.mapper.PredictionMapper;
import com.jingcaicompass.prediction.service.PredictionImportFileParser;
import com.jingcaicompass.prediction.service.PredictionImportService;
import com.jingcaicompass.system.config.properties.PaginationProperties;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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
            .withBean(PaginationProperties.class, () -> new PaginationProperties(100))
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
            .withBean(MatchSourceMappingMapper.class, () -> mock(MatchSourceMappingMapper.class))
            .withBean(SportteryPoolSnapshotMapper.class, () -> mock(SportteryPoolSnapshotMapper.class))
            .withBean(AsianOddsSnapshotMapper.class, () -> mock(AsianOddsSnapshotMapper.class))
            .withBean(PredictionMapper.class, () -> mock(PredictionMapper.class));

    @Test
    void doesNotRegisterPersistenceServicesWithoutDataSource() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(DataPipelineService.class);
            assertThat(context).doesNotHaveBean(DataProviderService.class);
            assertThat(context).doesNotHaveBean(PredictionImportService.class);
        });
    }

    @Test
    void registersPersistenceServiceChainAfterDataSourceExists() {
        contextRunner
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(DataPipelineService.class);
                    assertThat(context).hasSingleBean(MatchNormalizationBackfillService.class);
                    assertThat(context).hasSingleBean(AuditLogService.class);
                    assertThat(context).hasSingleBean(PredictionImportFileParser.class);
                    assertThat(context).hasSingleBean(PredictionImportService.class);
                    assertThat(context).doesNotHaveBean(DataPipelineSyncJob.class);
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
}
