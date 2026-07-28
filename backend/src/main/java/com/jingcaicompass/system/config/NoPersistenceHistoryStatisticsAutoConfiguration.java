package com.jingcaicompass.system.config;

import com.jingcaicompass.history.service.HistoryQueryService;
import com.jingcaicompass.history.service.UnavailableHistoryQueryService;
import com.jingcaicompass.match.service.MatchQueryService;
import com.jingcaicompass.match.service.UnavailableMatchQueryService;
import com.jingcaicompass.statistics.service.StatisticsQueryService;
import com.jingcaicompass.statistics.service.UnavailableStatisticsQueryService;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** 无 DataSource 时为公开历史与统计 Controller 提供统一的不可用响应。 */
@AutoConfiguration(afterName = "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration")
@ConditionalOnMissingBean(DataSource.class)
public class NoPersistenceHistoryStatisticsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(HistoryQueryService.class)
    HistoryQueryService unavailableHistoryQueryService() {
        return new UnavailableHistoryQueryService();
    }

    @Bean
    @ConditionalOnMissingBean(MatchQueryService.class)
    MatchQueryService unavailableMatchQueryService() {
        return new UnavailableMatchQueryService();
    }

    @Bean
    @ConditionalOnMissingBean(StatisticsQueryService.class)
    StatisticsQueryService unavailableStatisticsQueryService() {
        return new UnavailableStatisticsQueryService();
    }
}
