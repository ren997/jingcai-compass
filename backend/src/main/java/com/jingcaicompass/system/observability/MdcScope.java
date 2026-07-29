package com.jingcaicompass.system.observability;

import org.slf4j.MDC;

/** 为单条业务日志临时附加 MDC 字段并在结束时恢复上层上下文。 */
public final class MdcScope implements AutoCloseable {

    private final String key;
    private final String priorValue;

    private MdcScope(String key, String value) {
        this.key = key;
        this.priorValue = MDC.get(key);
        if (value == null || value.isBlank()) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }

    public static MdcScope prediction(Long predictionId) {
        return new MdcScope("predictionId", predictionId == null ? null : String.valueOf(predictionId));
    }

    public static MdcScope snapshot(Long snapshotId) {
        return new MdcScope("snapshotId", snapshotId == null ? null : String.valueOf(snapshotId));
    }

    @Override
    public void close() {
        if (priorValue == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, priorValue);
        }
    }
}
