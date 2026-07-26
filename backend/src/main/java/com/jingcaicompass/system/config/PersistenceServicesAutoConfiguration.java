package com.jingcaicompass.system.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.admin.mapper.AdminAccountMapper;
import com.jingcaicompass.admin.service.AdminAccountBootstrapRunner;
import com.jingcaicompass.admin.service.AdminAccountCredentialValidator;
import com.jingcaicompass.admin.service.AdminAccountTokenValidator;
import com.jingcaicompass.admin.service.AdminAuthService;
import com.jingcaicompass.admin.service.AdminAuthServiceImpl;
import com.jingcaicompass.admin.service.AdminJwtService;
import com.jingcaicompass.admin.service.AdminLoginAttemptWriter;
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
import com.jingcaicompass.prediction.job.PredictionLockJob;
import com.jingcaicompass.prediction.service.PredictionContentHasher;
import com.jingcaicompass.prediction.service.PredictionImportFileParser;
import com.jingcaicompass.prediction.service.PredictionImportFileParserImpl;
import com.jingcaicompass.prediction.service.PredictionImportService;
import com.jingcaicompass.prediction.service.PredictionImportServiceImpl;
import com.jingcaicompass.prediction.service.PredictionImportWriter;
import com.jingcaicompass.prediction.service.PredictionLockMetrics;
import com.jingcaicompass.prediction.service.PredictionLockService;
import com.jingcaicompass.prediction.service.PredictionLockServiceImpl;
import com.jingcaicompass.prediction.service.PredictionLockWorker;
import com.jingcaicompass.prediction.service.PredictionPublishService;
import com.jingcaicompass.prediction.service.PredictionPublishServiceImpl;
import com.jingcaicompass.snapshot.job.SnapshotPublishJob;
import com.jingcaicompass.snapshot.mapper.PredictionSnapshotMapper;
import com.jingcaicompass.snapshot.service.PredictionSnapshotService;
import com.jingcaicompass.snapshot.service.PredictionSnapshotServiceImpl;
import com.jingcaicompass.snapshot.service.SnapshotManifestGenerator;
import com.jingcaicompass.snapshot.storage.SnapshotStorage;
import com.jingcaicompass.system.config.properties.PaginationProperties;
import com.jingcaicompass.system.config.properties.AdminSecurityProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;

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
    @ConditionalOnMissingBean
    AdminAccountTokenValidator adminAccountTokenValidator(AdminAccountMapper adminAccountMapper) {
        return new AdminAccountTokenValidator(adminAccountMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    AdminJwtService adminJwtService(
            JwtEncoder jwtEncoder,
            AdminSecurityProperties adminSecurityProperties,
            Clock predictionImportClock
    ) {
        return new AdminJwtService(jwtEncoder, adminSecurityProperties, predictionImportClock);
    }

    @Bean
    @ConditionalOnMissingBean
    AdminLoginAttemptWriter adminLoginAttemptWriter(
            AdminAccountMapper adminAccountMapper,
            AuditLogService auditLogService,
            AdminSecurityProperties adminSecurityProperties,
            Clock predictionImportClock
    ) {
        return new AdminLoginAttemptWriter(
                adminAccountMapper,
                auditLogService,
                adminSecurityProperties,
                predictionImportClock
        );
    }

    @Bean
    @ConditionalOnMissingBean(AdminAuthService.class)
    AdminAuthService adminAuthService(
            AdminAccountMapper adminAccountMapper,
            AdminAccountCredentialValidator credentialValidator,
            AdminLoginAttemptWriter loginAttemptWriter,
            AdminJwtService jwtService,
            PasswordEncoder passwordEncoder,
            Clock predictionImportClock
    ) {
        return new AdminAuthServiceImpl(
                adminAccountMapper,
                credentialValidator,
                loginAttemptWriter,
                jwtService,
                passwordEncoder,
                predictionImportClock
        );
    }

    @Bean
    @ConditionalOnMissingBean
    AdminAccountBootstrapRunner adminAccountBootstrapRunner(
            AdminAccountMapper adminAccountMapper,
            AdminAccountCredentialValidator credentialValidator,
            PasswordEncoder passwordEncoder,
            AdminSecurityProperties adminSecurityProperties,
            AuditLogService auditLogService,
            Clock predictionImportClock
    ) {
        return new AdminAccountBootstrapRunner(
                adminAccountMapper,
                credentialValidator,
                passwordEncoder,
                adminSecurityProperties,
                auditLogService,
                predictionImportClock
        );
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
    @ConditionalOnMissingBean
    PredictionContentHasher predictionContentHasher(ObjectMapper objectMapper) {
        return new PredictionContentHasher(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(PredictionPublishService.class)
    PredictionPublishService predictionPublishService(
            PredictionMapper predictionMapper,
            MatchMapper matchMapper,
            PredictionContentHasher predictionContentHasher,
            AuditLogService auditLogService,
            Clock predictionImportClock
    ) {
        return new PredictionPublishServiceImpl(
                predictionMapper,
                matchMapper,
                predictionContentHasher,
                auditLogService,
                predictionImportClock
        );
    }

    @Bean
    @ConditionalOnMissingBean
    SnapshotManifestGenerator snapshotManifestGenerator(
            ObjectMapper objectMapper,
            PredictionContentHasher predictionContentHasher
    ) {
        return new SnapshotManifestGenerator(objectMapper, predictionContentHasher);
    }

    @Bean
    @ConditionalOnMissingBean(PredictionSnapshotService.class)
    PredictionSnapshotService predictionSnapshotService(
            PredictionMapper predictionMapper,
            PredictionSnapshotMapper predictionSnapshotMapper,
            SnapshotManifestGenerator snapshotManifestGenerator,
            SnapshotStorage snapshotStorage,
            JdbcTemplate jdbcTemplate
    ) {
        return new PredictionSnapshotServiceImpl(
                predictionMapper,
                predictionSnapshotMapper,
                snapshotManifestGenerator,
                snapshotStorage,
                jdbcTemplate
        );
    }

    @Bean
    @ConditionalOnMissingBean
    PredictionLockMetrics predictionLockMetrics(MeterRegistry meterRegistry) {
        return new PredictionLockMetrics(meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    PredictionLockWorker predictionLockWorker(
            PredictionMapper predictionMapper,
            AuditLogService auditLogService
    ) {
        return new PredictionLockWorker(predictionMapper, auditLogService);
    }

    @Bean
    @ConditionalOnMissingBean(PredictionLockService.class)
    PredictionLockService predictionLockService(
            PredictionLockWorker predictionLockWorker,
            PredictionLockMetrics predictionLockMetrics
    ) {
        return new PredictionLockServiceImpl(predictionLockWorker, predictionLockMetrics);
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

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "app.tasks",
            name = {"enabled", "prediction-lock.enabled"},
            havingValue = "true"
    )
    PredictionLockJob predictionLockJob(PredictionLockService predictionLockService) {
        return new PredictionLockJob(predictionLockService);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "app.tasks",
            name = {"enabled", "snapshot-publish.enabled"},
            havingValue = "true"
    )
    SnapshotPublishJob snapshotPublishJob(
            PredictionSnapshotService predictionSnapshotService,
            Clock predictionImportClock
    ) {
        return new SnapshotPublishJob(predictionSnapshotService, predictionImportClock);
    }
}
