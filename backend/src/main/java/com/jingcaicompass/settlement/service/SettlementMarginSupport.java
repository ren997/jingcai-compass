package com.jingcaicompass.settlement.service;

import com.jingcaicompass.settlement.enums.SettlementStatusEnum;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 亚盘统一的 margin 判定与收益换算，纯函数、无状态。
 *
 * <p>所有亚盘市场都归约到同一个带符号 margin：预测方向上的净胜刻度。
 * {@code margin > 0} 表示该方向赢盘，{@code = 0} 走盘，{@code < 0} 输盘。
 * 四分之一盘（.25/.75）按盘口线拆成上下两个半盘分别判定后再合并，
 * 因此内部一律以 0.25 为一个整数刻度做精确运算，不使用浮点数。</p>
 */
final class SettlementMarginSupport {

    /** margin 与盘口线的唯一合法步长：0.25。 */
    static final BigDecimal QUARTER = new BigDecimal("0.25");

    private static final BigDecimal ONE = BigDecimal.ONE;

    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private static final BigDecimal HALF_LOSS_PROFIT = new BigDecimal("-0.5");

    private static final BigDecimal FULL_LOSS_PROFIT = new BigDecimal("-1");

    /** 结算净收益落库精度，与 {@code settlements.profit numeric(10,4)} 对齐。 */
    private static final int PROFIT_SCALE = 4;

    private SettlementMarginSupport() {
    }

    /**
     * 校验盘口线：必须存在且为 0.25 的整数倍。
     *
     * @param line      让球线或大小球线
     * @param fieldName 报错中使用的字段名
     * @return 原样返回已校验的盘口线
     */
    static BigDecimal requireQuarterLine(BigDecimal line, String fieldName) {
        if (line == null) {
            throw new IllegalArgumentException(fieldName + " must not be null for asian settlement");
        }
        try {
            line.divide(QUARTER).toBigIntegerExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    fieldName + " must be a multiple of 0.25 for asian settlement: " + line,
                    exception
            );
        }
        return line;
    }

    /**
     * 按带符号 margin 判定结算状态。
     *
     * @param signedMargin 预测方向上的净胜刻度，步长 0.25
     * @return HIT/MISS/PUSH/HALF_WIN/HALF_LOSS
     */
    static SettlementStatusEnum settleByMargin(BigDecimal signedMargin) {
        if (signedMargin == null) {
            throw new IllegalArgumentException("signedMargin must not be null");
        }
        long quarters;
        try {
            quarters = signedMargin.divide(QUARTER).longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("signedMargin must be a multiple of 0.25: " + signedMargin, exception);
        }

        // 1) 半球盘与整数盘只有单一结果，不存在拆盘。
        if (quarters % 2 == 0) {
            if (quarters > 0) {
                return SettlementStatusEnum.HIT;
            }
            return quarters < 0 ? SettlementStatusEnum.MISS : SettlementStatusEnum.PUSH;
        }

        // 2) 四分之一盘拆成 quarters-1 与 quarters+1 两个半盘。
        long lower = quarters - 1;
        long upper = quarters + 1;
        if (lower > 0) {
            return SettlementStatusEnum.HIT;
        }
        if (upper < 0) {
            return SettlementStatusEnum.MISS;
        }
        // 3) 拆盘后跨越零点：下半盘走盘为半赢，上半盘走盘为半输。
        return lower == 0 ? SettlementStatusEnum.HALF_WIN : SettlementStatusEnum.HALF_LOSS;
    }

    /**
     * 以单注本金 1 计算的净收益。
     *
     * <p>竞彩市场不落库价格，调用方保存空收益；亚盘市场按预测生成时绑定的该方向水位换算：
     * 命中 {@code price-1}、半赢 {@code (price-1)/2}、走盘与作废 0、半输 {@code -0.5}、
     * 未中 {@code -1}。待结算没有收益。</p>
     *
     * @param status 结算状态
     * @param price  预测方向的欧式赔率（含本金），待结算、走盘与作废时可为空
     * @return 净收益
     */
    static BigDecimal netProfit(SettlementStatusEnum status, BigDecimal price) {
        if (status == null) {
            throw new IllegalArgumentException("settlementStatus must not be null");
        }
        if (status == SettlementStatusEnum.PENDING) {
            return null;
        }
        if (status == SettlementStatusEnum.PUSH || status == SettlementStatusEnum.VOID) {
            return BigDecimal.ZERO.setScale(PROFIT_SCALE, RoundingMode.UNNECESSARY);
        }
        if (price == null) {
            throw new IllegalArgumentException("price must not be null for settled status: " + status);
        }
        if (price.compareTo(ONE) <= 0) {
            throw new IllegalArgumentException("price must be greater than 1 for settled status: " + price);
        }
        return switch (status) {
            case HIT -> price.subtract(ONE).setScale(PROFIT_SCALE, RoundingMode.HALF_UP);
            case HALF_WIN -> price.subtract(ONE).divide(TWO, PROFIT_SCALE, RoundingMode.HALF_UP);
            case HALF_LOSS -> HALF_LOSS_PROFIT.setScale(PROFIT_SCALE, RoundingMode.UNNECESSARY);
            case MISS -> FULL_LOSS_PROFIT.setScale(PROFIT_SCALE, RoundingMode.UNNECESSARY);
            case PENDING, PUSH, VOID -> throw new IllegalStateException("unreachable status: " + status);
        };
    }
}
