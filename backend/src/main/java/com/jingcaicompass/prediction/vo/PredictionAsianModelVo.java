package com.jingcaicompass.prediction.vo;

import com.jingcaicompass.prediction.entity.Prediction;
import java.math.BigDecimal;

/**
 * 亚盘模型输出（V24 十列）。
 *
 * <p>概率一律为<b>首边口径</b>：{@code modelCoverProb} / {@code marketCoverProb} 指主队赢下冻结让球线，
 * {@code modelOverProb} / {@code marketOverProb} 指总进球高于冻结大小球线。消费方需要所选方向的概率时
 * 自行取 {@code 1 − p}，不要在服务端就地换算——换算是方向相关的一次性动作，就地换算会让同一份
 * JSON 在不同接口里语义不同。
 *
 * <p>三个成对约束由数据库与导入校验共同保证（{@code ck_predictions_asian_model_output_pairs}）：
 * λ 两列同生同灭、cover 三列同生同灭、over 三列与 {@code totalsDispersion} 同生同灭。
 */
public record PredictionAsianModelVo(
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

    /** 是否有可用的让球方向概率。 */
    public boolean hasHandicap() {
        return modelCoverProb != null && marketCoverProb != null;
    }

    /** 是否有可用的大小球方向概率。 */
    public boolean hasTotals() {
        return modelOverProb != null && marketOverProb != null;
    }

    /**
     * 从预测实体映射；十列全空时返回 {@code null}。
     *
     * <p>返回 null 而不是"十列都为 null 的对象"，是为了让消费方能区分「这一版没有模型概率」
     * （体彩预测、V24 之前的存量行）与「模型算出来是 0」——后者若按同样方式渲染会得到
     * "模型概率 0%、市场概率 0%" 这种明显错误却看似正常的结论。
     *
     * <p>判定用"全空"而不是"任一为空"：成对约束保证同组三列同生同灭，用全空判定不会漏掉半组。
     */
    public static PredictionAsianModelVo from(Prediction prediction) {
        if (prediction == null
                || (prediction.getLambdaHome() == null
                && prediction.getModelCoverProb() == null
                && prediction.getModelOverProb() == null)) {
            return null;
        }
        return new PredictionAsianModelVo(
                prediction.getLambdaHome(),
                prediction.getLambdaAway(),
                prediction.getModelCoverProb(),
                prediction.getMarketCoverProb(),
                prediction.getEdgeCover(),
                prediction.getModelOverProb(),
                prediction.getMarketOverProb(),
                prediction.getEdgeOver(),
                prediction.getHandicapDispersion(),
                prediction.getTotalsDispersion()
        );
    }
}
