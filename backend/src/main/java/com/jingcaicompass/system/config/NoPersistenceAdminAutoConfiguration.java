package com.jingcaicompass.system.config;

import com.jingcaicompass.admin.service.AdminPredictionStatusQueryService;
import com.jingcaicompass.admin.service.AdminSyncRunQueryService;
import com.jingcaicompass.admin.service.AdminSportteryResultSyncService;
import com.jingcaicompass.admin.service.NoOpAdminPredictionStatusQueryService;
import com.jingcaicompass.admin.service.NoOpAdminSyncRunQueryService;
import com.jingcaicompass.match.service.MatchMappingReviewService;
import com.jingcaicompass.match.service.NoOpMatchMappingReviewService;
import com.jingcaicompass.match.service.NoOpProviderNormalizationReviewService;
import com.jingcaicompass.match.service.ProviderNormalizationReviewService;
import javax.sql.DataSource;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** 无 DataSource 时为后台只读查询 Controller 提供统一降级实现。 */
@AutoConfiguration(afterName = "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration")
@ConditionalOnMissingBean(DataSource.class)
public class NoPersistenceAdminAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(AdminSyncRunQueryService.class)
    AdminSyncRunQueryService unavailableAdminSyncRunQueryService() {
        return new NoOpAdminSyncRunQueryService();
    }

    @Bean
    @ConditionalOnMissingBean(AdminSportteryResultSyncService.class)
    AdminSportteryResultSyncService unavailableAdminSportteryResultSyncService() {
        return request -> {
            throw new BusinessException(ErrorCode.DATA_SOURCE_UNAVAILABLE);
        };
    }

    @Bean
    @ConditionalOnMissingBean(AdminPredictionStatusQueryService.class)
    AdminPredictionStatusQueryService unavailableAdminPredictionStatusQueryService() {
        return new NoOpAdminPredictionStatusQueryService();
    }

    @Bean
    @ConditionalOnMissingBean(MatchMappingReviewService.class)
    MatchMappingReviewService unavailableMatchMappingReviewService() {
        return new NoOpMatchMappingReviewService();
    }

    @Bean
    @ConditionalOnMissingBean(ProviderNormalizationReviewService.class)
    ProviderNormalizationReviewService unavailableProviderNormalizationReviewService() {
        return new NoOpProviderNormalizationReviewService();
    }
}
