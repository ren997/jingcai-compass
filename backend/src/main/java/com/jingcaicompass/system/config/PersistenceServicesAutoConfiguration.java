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
import com.jingcaicompass.admin.service.AdminPredictionStatusQueryService;
import com.jingcaicompass.admin.service.AdminPredictionStatusQueryServiceImpl;
import com.jingcaicompass.admin.service.AdminSyncRunQueryService;
import com.jingcaicompass.admin.service.AdminSyncRunQueryServiceImpl;
import com.jingcaicompass.audit.mapper.AuditLogMapper;
import com.jingcaicompass.admin.mapper.AdminPredictionStatusMapper;
import com.jingcaicompass.audit.service.AuditLogService;
import com.jingcaicompass.audit.service.AuditLogServiceImpl;
import com.jingcaicompass.data.job.DataPipelineSyncJob;
import com.jingcaicompass.data.service.DataPipelineService;
import com.jingcaicompass.data.service.DataPipelineServiceImpl;
import com.jingcaicompass.data.service.DataProviderService;
import com.jingcaicompass.data.service.DataProviderServiceImpl;
import com.jingcaicompass.data.service.DataSyncRunService;
import com.jingcaicompass.data.service.DataSyncRunServiceImpl;
import com.jingcaicompass.data.service.DataSyncRunPayloadLinkService;
import com.jingcaicompass.data.service.DataSyncRunPayloadLinkServiceImpl;
import com.jingcaicompass.data.service.ProviderSyncTemplate;
import com.jingcaicompass.data.service.RawDataPayloadService;
import com.jingcaicompass.data.service.RawDataPayloadServiceImpl;
import com.jingcaicompass.data.mapper.DataSyncRunMapper;
import com.jingcaicompass.data.mapper.DataSyncRunPayloadMapper;
import com.jingcaicompass.data.mapper.RawDataPayloadMapper;
import com.jingcaicompass.history.mapper.HistoryQueryMapper;
import com.jingcaicompass.history.service.HistoryQueryService;
import com.jingcaicompass.history.service.HistoryQueryServiceImpl;
import com.jingcaicompass.history.service.HistoryRecordAssembler;
import com.jingcaicompass.home.mapper.HomeSummaryMapper;
import com.jingcaicompass.home.service.HomeSummaryQueryService;
import com.jingcaicompass.home.service.HomeSummaryQueryServiceImpl;
import com.jingcaicompass.match.job.SportteryPoolSyncJob;
import com.jingcaicompass.match.job.MatchResultSyncJob;
import com.jingcaicompass.match.client.SportteryProviderProperties;
import com.jingcaicompass.match.mapper.LeagueAliasMapper;
import com.jingcaicompass.match.mapper.LeagueMapper;
import com.jingcaicompass.match.mapper.MatchMapper;
import com.jingcaicompass.match.mapper.MatchResultFactMapper;
import com.jingcaicompass.match.mapper.MatchSourceMappingMapper;
import com.jingcaicompass.match.mapper.ProviderLeagueMappingMapper;
import com.jingcaicompass.match.mapper.ProviderTeamMappingMapper;
import com.jingcaicompass.match.mapper.SportteryPoolSnapshotMapper;
import com.jingcaicompass.match.mapper.TeamAliasMapper;
import com.jingcaicompass.match.mapper.TeamMapper;
import com.jingcaicompass.match.service.LeagueNormalizationService;
import com.jingcaicompass.match.service.LeagueNormalizationServiceImpl;
import com.jingcaicompass.match.service.MatchMappingReviewService;
import com.jingcaicompass.match.service.MatchQueryService;
import com.jingcaicompass.match.service.MatchQueryServiceImpl;
import com.jingcaicompass.match.service.MatchResultFactWriter;
import com.jingcaicompass.match.service.MatchResultSyncService;
import com.jingcaicompass.match.service.MatchResultSyncServiceImpl;
import com.jingcaicompass.match.service.MatchMappingReviewServiceImpl;
import com.jingcaicompass.match.service.MatchMappingService;
import com.jingcaicompass.match.service.MatchMappingServiceImpl;
import com.jingcaicompass.match.service.MatchNormalizationBackfillService;
import com.jingcaicompass.match.service.MatchNormalizationBackfillServiceImpl;
import com.jingcaicompass.match.service.MatchNormalizationWorker;
import com.jingcaicompass.match.service.ProviderNormalizationReviewService;
import com.jingcaicompass.match.service.ProviderNormalizationReviewServiceImpl;
import com.jingcaicompass.match.service.SportteryPoolMatchWriter;
import com.jingcaicompass.match.service.SportteryPoolPayloadMapper;
import com.jingcaicompass.match.service.SportteryPoolSyncService;
import com.jingcaicompass.match.service.SportteryPoolSyncServiceImpl;
import com.jingcaicompass.match.service.SportteryMatchResultPayloadMapper;
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
import com.jingcaicompass.prediction.service.PublicPredictionQueryService;
import com.jingcaicompass.prediction.service.PublicPredictionQueryServiceImpl;
import com.jingcaicompass.settlement.job.SettlementJob;
import com.jingcaicompass.settlement.mapper.SettlementMapper;
import com.jingcaicompass.settlement.service.MarketSettlementCalculatorRouter;
import com.jingcaicompass.settlement.service.SettlementService;
import com.jingcaicompass.settlement.service.SettlementServiceImpl;
import com.jingcaicompass.settlement.service.SettlementRecalculationService;
import com.jingcaicompass.settlement.service.SettlementRecalculationServiceImpl;
import com.jingcaicompass.settlement.service.SettlementRecalculationWriter;
import com.jingcaicompass.settlement.service.SettlementWriter;
import com.jingcaicompass.snapshot.job.SnapshotPublishJob;
import com.jingcaicompass.snapshot.mapper.PredictionSnapshotMapper;
import com.jingcaicompass.snapshot.service.PredictionSnapshotService;
import com.jingcaicompass.snapshot.service.PredictionSnapshotServiceImpl;
import com.jingcaicompass.snapshot.service.SnapshotManifestGenerator;
import com.jingcaicompass.snapshot.storage.SnapshotStorage;
import com.jingcaicompass.system.config.properties.PaginationProperties;
import com.jingcaicompass.system.config.properties.AdminSecurityProperties;
import com.jingcaicompass.system.config.properties.SyncTaskProperties;
import com.jingcaicompass.system.observability.MappingMetrics;
import com.jingcaicompass.system.observability.JobMetrics;
import com.jingcaicompass.system.observability.PredictionLifecycleMetrics;
import com.jingcaicompass.system.observability.ProviderMetrics;
import com.jingcaicompass.system.observability.SensitiveDataSanitizer;
import com.jingcaicompass.system.observability.SyncMetrics;
import com.jingcaicompass.statistics.service.StatisticsQueryService;
import com.jingcaicompass.statistics.service.StatisticsQueryServiceImpl;
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
    SensitiveDataSanitizer sensitiveDataSanitizer(ObjectMapper objectMapper) {
        return new SensitiveDataSanitizer(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    ProviderMetrics providerMetrics(MeterRegistry meterRegistry) {
        return new ProviderMetrics(meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    SyncMetrics syncMetrics(MeterRegistry meterRegistry) {
        return new SyncMetrics(meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    MappingMetrics mappingMetrics(MeterRegistry meterRegistry) {
        return new MappingMetrics(meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    JobMetrics jobMetrics(MeterRegistry meterRegistry) {
        return new JobMetrics(meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    PredictionLifecycleMetrics predictionLifecycleMetrics(MeterRegistry meterRegistry) {
        return new PredictionLifecycleMetrics(meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    HistoryRecordAssembler historyRecordAssembler(
            PredictionMapper predictionMapper,
            MatchMapper matchMapper,
            MatchResultFactMapper matchResultFactMapper,
            SettlementMapper settlementMapper,
            AuditLogMapper auditLogMapper
    ) {
        return new HistoryRecordAssembler(
                predictionMapper,
                matchMapper,
                matchResultFactMapper,
                settlementMapper,
                auditLogMapper
        );
    }

    @Bean
    @ConditionalOnMissingBean(HistoryQueryService.class)
    HistoryQueryService historyQueryService(
            HistoryQueryMapper historyQueryMapper,
            HistoryRecordAssembler historyRecordAssembler,
            PaginationProperties paginationProperties
    ) {
        return new HistoryQueryServiceImpl(historyQueryMapper, historyRecordAssembler, paginationProperties);
    }

    @Bean
    @ConditionalOnMissingBean(PublicPredictionQueryService.class)
    PublicPredictionQueryService publicPredictionQueryService(
            MatchMapper matchMapper,
            PredictionMapper predictionMapper,
            PredictionSnapshotMapper predictionSnapshotMapper,
            SnapshotStorage snapshotStorage,
            ObjectMapper objectMapper
    ) {
        return new PublicPredictionQueryServiceImpl(
                matchMapper,
                predictionMapper,
                predictionSnapshotMapper,
                snapshotStorage,
                objectMapper
        );
    }

    @Bean
    @ConditionalOnMissingBean(MatchQueryService.class)
    MatchQueryService matchQueryService(
            MatchMapper matchMapper,
            SportteryPoolSnapshotMapper sportteryPoolSnapshotMapper,
            AsianOddsSnapshotMapper asianOddsSnapshotMapper,
            MatchSourceMappingMapper matchSourceMappingMapper,
            RawDataPayloadMapper rawDataPayloadMapper,
            PaginationProperties paginationProperties
    ) {
        return new MatchQueryServiceImpl(
                matchMapper,
                sportteryPoolSnapshotMapper,
                asianOddsSnapshotMapper,
                matchSourceMappingMapper,
                rawDataPayloadMapper,
                paginationProperties
        );
    }

    @Bean
    @ConditionalOnMissingBean(StatisticsQueryService.class)
    StatisticsQueryService statisticsQueryService(
            HistoryQueryMapper historyQueryMapper,
            HistoryRecordAssembler historyRecordAssembler,
            Clock predictionImportClock
    ) {
        return new StatisticsQueryServiceImpl(
                historyQueryMapper,
                historyRecordAssembler,
                predictionImportClock
        );
    }

    @Bean
    @ConditionalOnMissingBean(HomeSummaryQueryService.class)
    HomeSummaryQueryService homeSummaryQueryService(
            HomeSummaryMapper homeSummaryMapper,
            StatisticsQueryService statisticsQueryService,
            Clock predictionImportClock
    ) {
        return new HomeSummaryQueryServiceImpl(homeSummaryMapper, statisticsQueryService, predictionImportClock);
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
            JdbcTemplate jdbcTemplate,
            PredictionLifecycleMetrics predictionLifecycleMetrics
    ) {
        return new PredictionSnapshotServiceImpl(
                predictionMapper,
                predictionSnapshotMapper,
                snapshotManifestGenerator,
                snapshotStorage,
                jdbcTemplate,
                predictionLifecycleMetrics
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
    @ConditionalOnMissingBean(DataSyncRunPayloadLinkService.class)
    DataSyncRunPayloadLinkService dataSyncRunPayloadLinkService(
            DataSyncRunPayloadMapper dataSyncRunPayloadMapper
    ) {
        return new DataSyncRunPayloadLinkServiceImpl(dataSyncRunPayloadMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    ProviderSyncTemplate providerSyncTemplate(
            DataSyncRunService dataSyncRunService,
            RawDataPayloadService rawDataPayloadService,
            DataSyncRunPayloadLinkService dataSyncRunPayloadLinkService,
            SyncMetrics syncMetrics,
            SensitiveDataSanitizer sanitizer
    ) {
        return new ProviderSyncTemplate(
                dataSyncRunService, rawDataPayloadService, dataSyncRunPayloadLinkService, syncMetrics, sanitizer
        );
    }

    @Bean
    @ConditionalOnMissingBean(AdminSyncRunQueryService.class)
    AdminSyncRunQueryService adminSyncRunQueryService(
            DataSyncRunMapper dataSyncRunMapper,
            RawDataPayloadMapper rawDataPayloadMapper,
            PaginationProperties paginationProperties,
            SportteryProviderProperties sportteryProperties,
            AsianOddsProviderProperties asianOddsProperties,
            SensitiveDataSanitizer sanitizer,
            Clock predictionImportClock
    ) {
        return new AdminSyncRunQueryServiceImpl(
                dataSyncRunMapper,
                rawDataPayloadMapper,
                paginationProperties,
                sportteryProperties,
                asianOddsProperties,
                sanitizer,
                predictionImportClock
        );
    }

    @Bean
    @ConditionalOnMissingBean(AdminPredictionStatusQueryService.class)
    AdminPredictionStatusQueryService adminPredictionStatusQueryService(
            AdminPredictionStatusMapper adminPredictionStatusMapper,
            HistoryRecordAssembler historyRecordAssembler,
            PaginationProperties paginationProperties
    ) {
        return new AdminPredictionStatusQueryServiceImpl(
                adminPredictionStatusMapper, historyRecordAssembler, paginationProperties
        );
    }

    @Bean
    @ConditionalOnMissingBean
    SportteryPoolMatchWriter sportteryPoolMatchWriter(
            MatchMapper matchMapper,
            SportteryPoolSnapshotMapper sportteryPoolSnapshotMapper,
            MatchResultFactMapper matchResultFactMapper
    ) {
        return new SportteryPoolMatchWriter(
                matchMapper,
                sportteryPoolSnapshotMapper,
                matchResultFactMapper
        );
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
    @ConditionalOnMissingBean
    MatchResultFactWriter matchResultFactWriter(
            MatchMapper matchMapper,
            MatchResultFactMapper matchResultFactMapper,
            AuditLogService auditLogService
    ) {
        return new MatchResultFactWriter(matchMapper, matchResultFactMapper, auditLogService);
    }

    @Bean
    @ConditionalOnMissingBean
    SportteryMatchResultPayloadMapper sportteryMatchResultPayloadMapper(ObjectMapper objectMapper) {
        return new SportteryMatchResultPayloadMapper(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(MatchResultSyncService.class)
    MatchResultSyncService matchResultSyncService(
            SportteryProvider sportteryProvider,
            ProviderSyncTemplate providerSyncTemplate,
            SportteryMatchResultPayloadMapper sportteryMatchResultPayloadMapper,
            MatchResultFactWriter matchResultFactWriter,
            ObjectMapper objectMapper
    ) {
        return new MatchResultSyncServiceImpl(
                sportteryProvider,
                providerSyncTemplate,
                sportteryMatchResultPayloadMapper,
                matchResultFactWriter,
                objectMapper
        );
    }

    @Bean
    @ConditionalOnMissingBean
    SettlementWriter settlementWriter(
            PredictionMapper predictionMapper,
            MatchMapper matchMapper,
            MatchResultFactMapper matchResultFactMapper,
            SportteryPoolSnapshotMapper sportteryPoolSnapshotMapper,
            SettlementMapper settlementMapper,
            MarketSettlementCalculatorRouter calculatorRouter,
            AuditLogService auditLogService
    ) {
        return new SettlementWriter(
                predictionMapper,
                matchMapper,
                matchResultFactMapper,
                sportteryPoolSnapshotMapper,
                settlementMapper,
                calculatorRouter,
                auditLogService
        );
    }

    @Bean
    @ConditionalOnMissingBean(SettlementService.class)
    SettlementService settlementService(
            SettlementMapper settlementMapper,
            SettlementWriter settlementWriter,
            PredictionLifecycleMetrics predictionLifecycleMetrics
    ) {
        return new SettlementServiceImpl(settlementMapper, settlementWriter, predictionLifecycleMetrics);
    }

    @Bean
    @ConditionalOnMissingBean
    SettlementRecalculationWriter settlementRecalculationWriter(
            PredictionMapper predictionMapper,
            MatchMapper matchMapper,
            MatchResultFactMapper matchResultFactMapper,
            SportteryPoolSnapshotMapper sportteryPoolSnapshotMapper,
            SettlementMapper settlementMapper,
            MarketSettlementCalculatorRouter calculatorRouter,
            AuditLogService auditLogService
    ) {
        return new SettlementRecalculationWriter(
                predictionMapper,
                matchMapper,
                matchResultFactMapper,
                sportteryPoolSnapshotMapper,
                settlementMapper,
                calculatorRouter,
                auditLogService
        );
    }

    @Bean
    @ConditionalOnMissingBean(SettlementRecalculationService.class)
    SettlementRecalculationService settlementRecalculationService(
            SettlementMapper settlementMapper,
            SettlementRecalculationWriter settlementRecalculationWriter,
            PredictionLifecycleMetrics predictionLifecycleMetrics
    ) {
        return new SettlementRecalculationServiceImpl(
                settlementMapper,
                settlementRecalculationWriter,
                predictionLifecycleMetrics
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
            MatchSourceMappingMapper matchSourceMappingMapper,
            MappingMetrics mappingMetrics
    ) {
        return new MatchMappingServiceImpl(matchMapper, matchSourceMappingMapper, mappingMetrics);
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
            LeagueNormalizationService leagueNormalizationService,
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
                leagueNormalizationService,
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
    @ConditionalOnMissingBean(ProviderNormalizationReviewService.class)
    ProviderNormalizationReviewService providerNormalizationReviewService(
            ProviderLeagueMappingMapper providerLeagueMappingMapper,
            ProviderTeamMappingMapper providerTeamMappingMapper,
            LeagueMapper leagueMapper,
            TeamMapper teamMapper,
            AuditLogMapper auditLogMapper,
            AuditLogService auditLogService,
            PaginationProperties paginationProperties
    ) {
        return new ProviderNormalizationReviewServiceImpl(
                providerLeagueMappingMapper,
                providerTeamMappingMapper,
                leagueMapper,
                teamMapper,
                auditLogMapper,
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
    SportteryPoolSyncJob sportteryPoolSyncJob(
            SportteryPoolSyncService sportteryPoolSyncService,
            JobMetrics jobMetrics
    ) {
        return new SportteryPoolSyncJob(sportteryPoolSyncService, jobMetrics);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "app.tasks",
            name = {"enabled", "match-result.enabled"},
            havingValue = "true"
    )
    MatchResultSyncJob matchResultSyncJob(
            MatchResultSyncService matchResultSyncService,
            SyncTaskProperties taskProperties,
            JobMetrics jobMetrics
    ) {
        return new MatchResultSyncJob(matchResultSyncService, taskProperties, jobMetrics);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "app.tasks",
            name = {"enabled", "asian-odds.enabled"},
            havingValue = "true"
    )
    AsianOddsSyncJob asianOddsSyncJob(AsianOddsSyncService asianOddsSyncService, JobMetrics jobMetrics) {
        return new AsianOddsSyncJob(asianOddsSyncService, jobMetrics);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "app.tasks",
            name = {"enabled", "data-pipeline.enabled"},
            havingValue = "true"
    )
    DataPipelineSyncJob dataPipelineSyncJob(DataPipelineService dataPipelineService, JobMetrics jobMetrics) {
        return new DataPipelineSyncJob(dataPipelineService, jobMetrics);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "app.tasks",
            name = {"enabled", "prediction-lock.enabled"},
            havingValue = "true"
    )
    PredictionLockJob predictionLockJob(PredictionLockService predictionLockService, JobMetrics jobMetrics) {
        return new PredictionLockJob(predictionLockService, jobMetrics);
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
            Clock predictionImportClock,
            JobMetrics jobMetrics
    ) {
        return new SnapshotPublishJob(predictionSnapshotService, predictionImportClock, jobMetrics);
    }

    @Bean
    @ConditionalOnBean({SettlementService.class, SettlementRecalculationService.class})
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "app.tasks",
            name = {"enabled", "settlement.enabled"},
            havingValue = "true"
    )
    SettlementJob settlementJob(
            SettlementRecalculationService settlementRecalculationService,
            SettlementService settlementService,
            SyncTaskProperties taskProperties,
            JobMetrics jobMetrics
    ) {
        return new SettlementJob(
                settlementRecalculationService,
                settlementService,
                taskProperties,
                jobMetrics
        );
    }
}
