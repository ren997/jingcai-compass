package com.jingcaicompass.system.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    @Bean
    OpenAPI jingCaiCompassOpenApi() {
        return new OpenAPI().info(new Info()
                .title("竞彩罗盘 API")
                .description("竞彩足球数据、预测与结算服务")
                .version("v0.1"));
    }
}
