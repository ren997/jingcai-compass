package com.jingcaicompass.system.config.properties;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.pagination")
public record PaginationProperties(
        /** 单次分页查询允许返回的最大记录数。 */
        @Min(1) @Max(1000) long maxPageSize
) {
}
