package com.jingcaicompass.prediction.vo;

import static org.assertj.core.api.Assertions.assertThat;

import com.jingcaicompass.prediction.entity.Prediction;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("亚盘模型概率视图映射")
class PredictionAsianModelVoTest {

    @Test
    @DisplayName("十列全空的预测映射为 null，而不是十列都为 null 的对象")
    void mapsAllNullColumnsToNull() {
        Prediction prediction = new Prediction();

        assertThat(PredictionAsianModelVo.from(prediction)).isNull();
    }

    @Test
    @DisplayName("十列逐列搬运，不丢列不错位")
    void copiesEveryColumn() {
        Prediction prediction = new Prediction();
        prediction.setLambdaHome(new BigDecimal("1.420000"));
        prediction.setLambdaAway(new BigDecimal("1.180000"));
        prediction.setModelCoverProb(new BigDecimal("0.531000"));
        prediction.setMarketCoverProb(new BigDecimal("0.512000"));
        prediction.setEdgeCover(new BigDecimal("0.019000"));
        prediction.setModelOverProb(new BigDecimal("0.487000"));
        prediction.setMarketOverProb(new BigDecimal("0.501000"));
        prediction.setEdgeOver(new BigDecimal("-0.014000"));
        prediction.setHandicapDispersion(new BigDecimal("0.013000"));
        prediction.setTotalsDispersion(new BigDecimal("0.021000"));

        PredictionAsianModelVo vo = PredictionAsianModelVo.from(prediction);

        assertThat(vo).isNotNull();
        assertThat(vo.lambdaHome()).isEqualByComparingTo("1.420000");
        assertThat(vo.lambdaAway()).isEqualByComparingTo("1.180000");
        assertThat(vo.modelCoverProb()).isEqualByComparingTo("0.531000");
        assertThat(vo.marketCoverProb()).isEqualByComparingTo("0.512000");
        assertThat(vo.edgeCover()).isEqualByComparingTo("0.019000");
        assertThat(vo.modelOverProb()).isEqualByComparingTo("0.487000");
        assertThat(vo.marketOverProb()).isEqualByComparingTo("0.501000");
        assertThat(vo.edgeOver()).isEqualByComparingTo("-0.014000");
        assertThat(vo.handicapDispersion()).isEqualByComparingTo("0.013000");
        assertThat(vo.totalsDispersion()).isEqualByComparingTo("0.021000");
    }

    @Test
    @DisplayName("防御性：只有 λ 的行仍返回对象（成对约束本不允许此形态），两方向都判为不可用")
    void keepsLambdaOnlyPrediction() {
        Prediction prediction = new Prediction();
        prediction.setLambdaHome(new BigDecimal("1.420000"));
        prediction.setLambdaAway(new BigDecimal("1.180000"));

        PredictionAsianModelVo vo = PredictionAsianModelVo.from(prediction);

        assertThat(vo).isNotNull();
        assertThat(vo.hasHandicap()).isFalse();
        assertThat(vo.hasTotals()).isFalse();
    }

    @Test
    @DisplayName("方向可用性只要求模型与市场概率都在，不要求 edge")
    void availabilityIgnoresEdge() {
        Prediction prediction = new Prediction();
        prediction.setModelCoverProb(new BigDecimal("0.531000"));
        prediction.setMarketCoverProb(new BigDecimal("0.512000"));

        PredictionAsianModelVo vo = PredictionAsianModelVo.from(prediction);

        assertThat(vo).isNotNull();
        assertThat(vo.hasHandicap()).isTrue();
        assertThat(vo.hasTotals()).isFalse();
    }
}
