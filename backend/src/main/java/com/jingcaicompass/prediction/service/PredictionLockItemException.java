package com.jingcaicompass.prediction.service;

/** 携带预测 ID 和失败阶段的单条锁定异常。 */
final class PredictionLockItemException extends RuntimeException {

    private final Long predictionId;
    private final String stage;

    PredictionLockItemException(
            Long predictionId,
            String stage,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.predictionId = predictionId;
        this.stage = stage;
    }

    Long predictionId() {
        return predictionId;
    }

    String stage() {
        return stage;
    }
}
