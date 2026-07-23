package com.jingcaicompass.system.provider;

/**
 * Provider HTTP 调用失败，携带已发生的重试与额度计数。
 */
public class ProviderHttpException extends ProviderException {

    private final int retryCount;
    private final int quotaCost;
    private final Integer httpStatus;

    public ProviderHttpException(
            String providerCode,
            ProviderErrorCategory category,
            String message,
            int retryCount,
            int quotaCost,
            Integer httpStatus
    ) {
        super(providerCode, category, message);
        this.retryCount = Math.max(retryCount, 0);
        this.quotaCost = Math.max(quotaCost, 0);
        this.httpStatus = httpStatus;
    }

    public ProviderHttpException(
            String providerCode,
            ProviderErrorCategory category,
            String message,
            int retryCount,
            int quotaCost,
            Integer httpStatus,
            Throwable cause
    ) {
        super(providerCode, category, message, cause);
        this.retryCount = Math.max(retryCount, 0);
        this.quotaCost = Math.max(quotaCost, 0);
        this.httpStatus = httpStatus;
    }

    public int retryCount() {
        return retryCount;
    }

    public int quotaCost() {
        return quotaCost;
    }

    public Integer httpStatus() {
        return httpStatus;
    }
}
