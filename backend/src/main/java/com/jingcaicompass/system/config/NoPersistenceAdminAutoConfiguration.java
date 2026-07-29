package com.jingcaicompass.system.config;

import com.jingcaicompass.admin.service.AdminPredictionStatusQueryService;
import com.jingcaicompass.admin.service.AdminSyncRunQueryService;
import com.jingcaicompass.admin.service.NoOpAdminPredictionStatusQueryService;
import com.jingcaicompass.admin.service.NoOpAdminSyncRunQueryService;
import javax.sql.DataSource;
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
    @ConditionalOnMissingBean(AdminPredictionStatusQueryService.class)
    AdminPredictionStatusQueryService unavailableAdminPredictionStatusQueryService() {
        return new NoOpAdminPredictionStatusQueryService();
    }
}
