package com.jingcaicompass.system.infrastructure;

import org.slf4j.MDC;

import java.util.UUID;

public final class TraceIdContext {

    public static final String HEADER_NAME = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    private TraceIdContext() {
    }

    public static String currentOrCreate() {
        String current = MDC.get(MDC_KEY);
        return current == null || current.isBlank()
                ? UUID.randomUUID().toString().replace("-", "")
                : current;
    }
}
