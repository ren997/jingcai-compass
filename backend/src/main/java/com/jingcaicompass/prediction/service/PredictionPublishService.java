package com.jingcaicompass.prediction.service;

import com.jingcaicompass.prediction.dto.PredictionPublishDto;
import com.jingcaicompass.prediction.vo.PredictionPublishResultVo;

/** 将 T302 导入的草稿按版本顺序发布，并保留历史版本。 */
public interface PredictionPublishService {

    /**
     * 发布单条草稿；已发布或已锁定记录按原结果幂等返回。
     *
     * @param request 发布目标
     * @param operatorUsername 已认证管理员用户名
     * @return 发布后的版本、时间和内容哈希
     */
    PredictionPublishResultVo publish(PredictionPublishDto request, String operatorUsername);
}
