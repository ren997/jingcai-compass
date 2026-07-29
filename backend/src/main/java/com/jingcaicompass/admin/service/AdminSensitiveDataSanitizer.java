package com.jingcaicompass.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.system.observability.SensitiveDataSanitizer;

/**
 * 兼容旧后台服务与测试的脱敏器名称；实际实现由系统级脱敏器统一提供。
 */
@Deprecated(forRemoval = false)
public class AdminSensitiveDataSanitizer extends SensitiveDataSanitizer {

    public AdminSensitiveDataSanitizer(ObjectMapper objectMapper) {
        super(objectMapper);
    }
}
