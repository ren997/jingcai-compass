package com.jingcaicompass.system.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.jingcaicompass.admin.service.AdminPredictionStatusQueryService;
import com.jingcaicompass.admin.service.AdminSyncRunQueryService;
import com.jingcaicompass.admin.service.NoOpAdminPredictionStatusQueryService;
import com.jingcaicompass.admin.service.NoOpAdminSyncRunQueryService;
import com.jingcaicompass.match.service.MatchMappingReviewService;
import com.jingcaicompass.match.service.NoOpMatchMappingReviewService;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class NoPersistenceAdminAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NoPersistenceAdminAutoConfiguration.class));

    @Test
    void suppliesNoOpQueriesOnlyWithoutDataSource() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AdminSyncRunQueryService.class);
            assertThat(context).hasSingleBean(AdminPredictionStatusQueryService.class);
            assertThat(context).hasSingleBean(MatchMappingReviewService.class);
            assertThat(context.getBean(AdminSyncRunQueryService.class))
                    .isInstanceOf(NoOpAdminSyncRunQueryService.class);
            assertThat(context.getBean(AdminPredictionStatusQueryService.class))
                    .isInstanceOf(NoOpAdminPredictionStatusQueryService.class);
            assertThat(context.getBean(MatchMappingReviewService.class))
                    .isInstanceOf(NoOpMatchMappingReviewService.class);
        });
    }

    @Test
    void doesNotRegisterNoOpQueriesWhenDataSourceExists() {
        contextRunner.withBean(DataSource.class, () -> mock(DataSource.class)).run(context -> {
            assertThat(context).doesNotHaveBean(AdminSyncRunQueryService.class);
            assertThat(context).doesNotHaveBean(AdminPredictionStatusQueryService.class);
            assertThat(context).doesNotHaveBean(MatchMappingReviewService.class);
        });
    }
}
