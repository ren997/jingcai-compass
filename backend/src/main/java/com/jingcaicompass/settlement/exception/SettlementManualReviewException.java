package com.jingcaicompass.settlement.exception;

/** 结算输入不可追溯但可由人工补数后重试的业务异常。 */
public class SettlementManualReviewException extends RuntimeException {

    public SettlementManualReviewException(String message) {
        super(message);
    }
}
