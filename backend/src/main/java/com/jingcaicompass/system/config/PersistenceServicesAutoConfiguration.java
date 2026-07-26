package com.jingcaicompass.system.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.audit.mapper.AuditLogMapper;
import com.jingcaicompass.audit.service.AuditLogService;
import com.jingcaicompass.audit.service.AuditLogServiceImpl;
import com.jingcaicompass.data.job.DataPipelineSyncJob;
import com.jingcaicompass.data.service.DataPipelineService;
import com.jingcaicompass.data.service.DataPipelineServiceImpl;
import com.jingcaicompass.data.service.DataProviderService;
import com.jingcaicompass.data.service.DataProviderServiceImpl;
import com.jingcaicompass.data.service.DataSyncRunService;
import com.jingcaicompass.data.service.DataSyncRunServiceImpl;
import com.jingcaicompass.data.service.ProviderSyncTemplate;
import com.jingcaicompass.data.service.RawDataPayloadService;
import com.jingcaicompass.data.service.RawDataPayloadServiceImpl;
import com.jingcaicompass.data.mapper.DataSyncRunMapper;
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
import com.jingcaicompass.match.service.LeagueNormalizationService;
import com.jingcaicompass.match.service.LeagueNormalizationServiceImpl;
import com.jingcaicompass.match.service.MatchMappingReviewService;
import com.jingcaicompass.match.service.MatchMappingReviewServiceImpl;
import com.jingcaicompass.match.service.MatchMappingService;
import com.jingcaicompass.match.service.MatchMappingServiceImpl;
import com.jingcaicompass.match.service.MatchNormalizationBackfillService;
import com.jingcaicompass.match.service.MatchNormalizationBackfillServiceImpl;
import com.jingcaicompass.match.service.MatchNormalizationWorker;
import com.jingcaicompass.match.service.SportteryPoolMatchWriter;
import com.jingcaicompass.match.service.SportteryPoolPayloadMapper;
import com.jingcaicompass.match.service.SportteryPoolSyncService;
import com.jingcaicompass.match.service.SportteryPoolSyncServiceImpl;
import com.jingcaicompass.match.service.SportteryProvider;
import com.jingcaicompass.match.service.TeamNormalizationService;
import com.jingcaicompass.match.service.TeamNormalizationServiceImpl;
import com.jingcaicompass.odds.client.AsianOddsProviderProperties;
import com.jingcaicompass.odds.job.AsianOddsSyncJob;
import com.jingcaicompass.odds.mapper.AsianOddsSnapshotMapper;
import com.jingcaicompass.odds.service.AsianOddsPayloadMapper;
import com.jingcaicompass.odds.service.AsianOddsProvider;
import com.jingcaicompass.odds.service.AsianOddsSnapshotWriter;
import com.jingcaicompass.odds.service.AsianOddsSyncService;
import com.jingcaicompass.odds.service.AsianOddsSyncServiceImpl;
import com.jingcaicompass.prediction.mapper.PredictionMapper;
import com.jingcaicompass.prediction.service.PredictionImportFileParser;
import com.jingcaicompass.prediction.service.PredictionImportFileParserImpl;
import com.jingcaicompass.prediction.service.PredictionImportService;
import com.jingcaicompass.prediction.service.PredictionImportServiceImpl;
import com.jingcaicompass.prediction.service.PredictionImportWriter;
import com.jingcaicompass.system.config.properties.PaginationProperties;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * 在 DataSource 和 MyBatis-Plus 完成注册后装配持久化服务。
 *
 * <p>这些实现仍保留原有组件注解；自动配置只在组件扫描因 DataSource 注册时序而跳过它们时兜底，
 * 避免无数据库的快速测试创建任何持久化 Bean。</p>
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration"
})
@ConditionalOnBean(DataSource.class)
public class PersistenceServicesAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock predictionImportClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean(PredictionImportFileParser.class)
    PredictionImportFileParser predictionImportFileParser(ObjectMapper objectMapper) {
        return new PredictionImportFileParserImpl(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    PredictionImportWriter predictionImportWriter(PredictionMapper predictionMapper) {
        return new PredictionImportWriter(predictionMapper);
    }

    @Bean
    @ConditionalOnMissingBean(PredictionImportService.class)
    PredictionImportService predictionImportService(
            PredictionImportFileParser predictionImportFileParser,
            MatchMapper matchMapper,
            PredictionMapper predictionMapper,
            PredictionImportWriter predictionImportWriter,
            Clock predictionImportClock
    ) {
        return new PredictionImportServiceImpl(
                predictionImportFileParser,
                matchMapper,
                predictionMapper,
                predictionImportWriter,
                predictionImportClock
        );
    }

    @Bean
    @ConditionalOnMissingBean(DataProviderService.class)
    DataProviderService dataProviderService() {
        return new DataProviderServiceImpl();
    }

    @Bean
    @ConditionalOnMissingBean(DataSyncRunService.class)
    DataSyncRunService dataSyncRunService() {
        return new DataSyncRunServiceImpl();
    }

    @Bean
    @ConditionalOnMissingBean(RawDataPayloadService.class)
    RawDataPayloadService rawDataPayloadService(ObjectMapper objectMapper) {
        return new RawDataPayloadServiceImpl(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    ProviderSyncTemplate providerSyncTemplate(
            DataSyncRunService dataSyncRunService,
            RawDataPayloadService rawDataPayloadService
    ) {
        return new ProviderSyncTemplate(dataSyncRunService, rawDataPayloadService);
    }

    @Bean
    @ConditionalOnMissingBean
    SportteryPoolMatchWriter sportteryPoolMatchWriter(
            MatchMapper matchMapper,
            SportteryPoolSnapshotMapper sportteryPoolSnapshotMapper
    ) {
        return new SportteryPoolMatchWriter(matchMapper, sportteryPoolSnapshotMapper);
    }

    @Bean
    @ConditionalOnMissingBean(SportteryPoolSyncService.class)
    SportteryPoolSyncService sportteryPoolSyncService(
            SportteryProvider sportteryProvider,
            ProviderSyncTemplate providerSyncTemplate,
            SportteryPoolPayloadMapper sportteryPoolPayloadMapper,
            SportteryPoolMatchWriter sportteryPoolMatchWriter,
            ObjectMapper objectMapper
    ) {
        return new SportteryPoolSyncServiceImpl(
                sportteryProvider,
                providerSyncTemplate,
                sportteryPoolPayloadMapper,
                sportteryPoolMatchWriter,
                objectMapper
        );
    }

    @Bean
    @ConditionalOnMissingBean(LeagueNormalizationService.class)
    LeagueNormalizationService leagueNormalizationService(
            LeagueMapper leagueMapper,
            LeagueAliasMapper leagueAliasMapper,
            ProviderLeagueMappingMapper providerLeagueMappingMapper
    ) {
        return new LeagueNormalizationServiceImpl(
                leagueMapper,
                leagueAliasMapper,
                providerLeagueMappingMapper
        );
    }

    @Bean
    @ConditionalOnMissingBean(TeamNormalizationService.class)
    TeamNormalizationService teamNormalizationService(
            TeamMapper teamMapper,
            TeamAliasMapper teamAliasMapper,
            ProviderTeamMappingMapper providerTeamMappingMapper
    ) {
        return new TeamNormalizationServiceImpl(
                teamMapper,
                teamAliasMapper,
                providerTeamMappingMapper
        );
    }

    @Bean
    @ConditionalOnMissingBean(MatchMappingService.class)
    MatchMappingService matchMappingService(
            MatchMapper matchMapper,
            MatchSourceMappingMapper matchSourceMappingMapper
    ) {
        return new MatchMappingServiceImpl(matchMapper, matchSourceMappingMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    MatchNormalizationWorker matchNormalizationWorker(
            MatchMapper matchMapper,
            LeagueNormalizationService leagueNormalizationService,
            TeamNormalizationService teamNormalizationService
    ) {
        return new MatchNormalizationWorker(
                matchMapper,
                leagueNormalizationService,
                teamNormalizationService
        );
    }

    @Bean
    @ConditionalOnMissingBean(MatchNormalizationBackfillService.class)
    MatchNormalizationBackfillService matchNormalizationBackfillService(
            MatchMapper matchMapper,
            MatchNormalizationWorker matchNormalizationWorker,
            SportteryProvider sportteryProvider
    ) {
        return new MatchNormalizationBackfillServiceImpl(
                matchMapper,
                matchNormalizationWorker,
                sportteryProvider
        );
    }

    @Bean
    @ConditionalOnMissingBean
    AsianOddsSnapshotWriter asianOddsSnapshotWriter(AsianOddsSnapshotMapper asianOddsSnapshotMapper) {
        return new AsianOddsSnapshotWriter(asianOddsSnapshotMapper);
    }

    @Bean
    @ConditionalOnMissingBean(AsianOddsSyncService.class)
    AsianOddsSyncService asianOddsSyncService(
            AsianOddsProvider asianOddsProvider,
            ProviderSyncTemplate providerSyncTemplate,
            AsianOddsPayloadMapper asianOddsPayloadMapper,
            AsianOddsSnapshotWriter asianOddsSnapshotWriter,
            MatchMappingService matchMappingService,
            TeamNormalizationService teamNormalizationService,
            MatchMapper matchMapper,
            AsianOddsSnapshotMapper asianOddsSnapshotMapper,
            DataSyncRunMapper dataSyncRunMapper,
            AsianOddsProviderProperties asianOddsProviderProperties,
            ObjectMapper objectMapper
    ) {
        return new AsianOddsSyncServiceImpl(
                asianOddsProvider,
                providerSyncTemplate,
                asianOddsPayloadMapper,
                asianOddsSnapshotWriter,
                matchMappingService,
                teamNormalizationService,
                matchMapper,
                asianOddsSnapshotMapper,
                dataSyncRunMapper,
                asianOddsProviderProperties,
                objectMapper
        );
    }

    @Bean
    @ConditionalOnMissingBean(DataPipelineService.class)
    DataPipelineService dataPipelineService(
            SportteryPoolSyncService sportteryPoolSyncService,
            MatchNormalizationBackfillService matchNormalizationBackfillService,
            AsianOddsSyncService asianOddsSyncService,
            AsianOddsProvider asianOddsProvider,
            MatchMapper matchMapper,
            MatchSourceMappingMapper matchSourceMappingMapper,
            AsianOddsSnapshotMapper asianOddsSnapshotMapper
    ) {
        return new DataPipelineServiceImpl(
                sportteryPoolSyncService,
                matchNormalizationBackfillService,
                asianOddsSyncService,
                asianOddsProvider,
                matchMapper,
                matchSourceMappingMapper,
                asianOddsSnapshotMapper
        );
    }

    @Bean
    @ConditionalOnMissingBean(AuditLogService.class)
    AuditLogService auditLogService(AuditLogMapper auditLogMapper) {
        return new AuditLogServiceImpl(auditLogMapper);
    }

    @Bean
    @ConditionalOnMissingBean(MatchMappingReviewService.class)
    MatchMappingReviewService matchMappingReviewService(
            MatchSourceMappingMapper matchSourceMappingMapper,
            MatchMapper matchMapper,
            AuditLogService auditLogService,
            PaginationProperties paginationProperties
    ) {
        return new MatchMappingReviewServiceImpl(
                matchSourceMappingMapper,
                matchMapper,
                auditLogService,
                paginationProperties
        );
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "app.tasks",
            name = {"enabled", "sporttery-pool.enabled"},
            havingValue = "true"
    )
    SportteryPoolSyncJob sportteryPoolSyncJob(SportteryPoolSyncService sportteryPoolSyncService) {
        return new SportteryPoolSyncJob(sportteryPoolSyncService);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "app.tasks",
            name = {"enabled", "asian-odds.enabled"},
            havingValue = "true"
    )
    AsianOddsSyncJob asianOddsSyncJob(AsianOddsSyncService asianOddsSyncService) {
        return new AsianOddsSyncJob(asianOddsSyncService);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "app.tasks",
            name = {"enabled", "data-pipeline.enabled"},
            havingValue = "true"
    )
    DataPipelineSyncJob dataPipelineSyncJob(DataPipelineService dataPipelineService) {
        return new DataPipelineSyncJob(dataPipelineService);
    }
}
