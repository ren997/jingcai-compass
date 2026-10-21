package com.jingcaicompass.prediction.dto;

import java.math.BigDecimal;

/**
 * 亚盘模型的概率输出，对应 V24 落在 {@code predictions} 上的十列。
 *
 * <p>概率一律为<b>首边口径</b>（主队赢盘 / 大球）。要看另一边时消费方自行取 {@code 1 − p}，
 * 入库不存换算后的值——存了就无法区分「模型真的这么认为」和「为了展示翻过来的」。
 *
 * <p>字段顺序与迁移列顺序严格一致，便于逐列核对。
 *
 * @param lambdaHome 主队泊松期望进球
 * @param lambdaAway 客队泊松期望进球
 * @param modelCoverProb 模型认为主队赢下冻结让球线的概率
 * @param marketCoverProb 跨庄去水后主队赢盘的共识概率
 * @param edgeCover 让球方向模型概率减共识概率，在主盘上结构性为 0
 * @param modelOverProb 模型认为总进球高过冻结大小球线的概率
 * @param marketOverProb 跨庄去水后大球的共识概率
 * @param edgeOver 大小球方向模型概率减共识概率，仅在冻结线不等于大小球主盘时非零
 * @param handicapDispersion 各庄让球去水概率的总体标准差
 * @param totalsDispersion 各庄大小球去水概率的总体标准差
 */
public record AsianModelOutputDto(
        BigDecimal lambdaHome,
        BigDecimal lambdaAway,
        BigDecimal modelCoverProb,
        BigDecimal marketCoverProb,
        BigDecimal edgeCover,
        BigDecimal modelOverProb,
        BigDecimal marketOverProb,
        BigDecimal edgeOver,
        BigDecimal handicapDispersion,
        BigDecimal totalsDispersion
) {

    /** 大小球三元组与离散度是否整组存在；缺一不可，否则 Brier 会拿没有分歧证据的方向计账。 */
    public boolean hasTotals() {
        return modelOverProb != null;
    }

    /** 让球三元组是否存在。 */
    public boolean hasHandicap() {
        return modelCoverProb != null;
    }
}
