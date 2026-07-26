package com.jingcaicompass.prediction.service;

import com.jingcaicompass.prediction.entity.Prediction;
import com.jingcaicompass.prediction.mapper.PredictionMapper;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 在单一事务中写入已完成业务校验的预测批次。 */
@Component
@ConditionalOnBean(DataSource.class)
public class PredictionImportWriter {

    private final PredictionMapper predictionMapper;

    public PredictionImportWriter(PredictionMapper predictionMapper) {
        this.predictionMapper = predictionMapper;
    }

    /**
     * 依次写入全部预测；任意一条失败时由事务回滚整个批次。
     *
     * @param predictions 已完成校验并分配版本的预测
     * @return 已写入且带主键的预测
     */
    @Transactional
    public List<Prediction> writeAll(List<Prediction> predictions) {
        // 1) 写入前先检查整个集合，避免循环中途发现调用契约错误
        Objects.requireNonNull(predictions, "predictions must not be null");
        if (predictions.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("predictions must not contain null");
        }

        // 2) 逐条写入同一事务，数据库异常向上抛出并触发整批回滚
        for (Prediction prediction : predictions) {
            predictionMapper.insert(prediction);
        }
        return List.copyOf(predictions);
    }
}
