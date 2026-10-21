package com.jingcaicompass.admin.controller;

import com.jingcaicompass.prediction.dto.PredictionBatchPublishDto;
import com.jingcaicompass.prediction.dto.PredictionPublishDto;
import com.jingcaicompass.prediction.service.PredictionBatchPublishService;
import com.jingcaicompass.prediction.service.PredictionPublishService;
import com.jingcaicompass.prediction.vo.PredictionBatchPublishResultVo;
import com.jingcaicompass.prediction.vo.PredictionPublishResultVo;
import com.jingcaicompass.system.api.ApiResponse;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理员预测发布接口，操作者身份只取自已校验 JWT。 */
@RestController
@RequestMapping("/api/admin/predictions")
public class PredictionPublishController {

    private final ObjectProvider<PredictionPublishService> publishServiceProvider;
    private final ObjectProvider<PredictionBatchPublishService> batchPublishServiceProvider;

    public PredictionPublishController(
            ObjectProvider<PredictionPublishService> publishServiceProvider,
            ObjectProvider<PredictionBatchPublishService> batchPublishServiceProvider
    ) {
        this.publishServiceProvider = publishServiceProvider;
        this.batchPublishServiceProvider = batchPublishServiceProvider;
    }

    /** 发布单条已导入草稿，重复请求按既有发布结果幂等返回。 */
    @PostMapping("/publish")
    public ApiResponse<PredictionPublishResultVo> publish(
            @Valid @RequestBody PredictionPublishDto request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        PredictionPublishService service = publishServiceProvider.getIfAvailable();
        if (service == null) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_ERROR,
                    "prediction publish service is unavailable"
            );
        }
        return ApiResponse.success(service.publish(request, requireUsername(jwt)));
    }

    /**
     * 批量发布；逐条独立执行，单条失败不影响其余条目。
     *
     * <p>响应里带逐条结果而不是只有汇总数：部分失败时运营要知道具体哪几条没发出去，
     * 否则只能整批重跑，而已成功的条目会被幂等复用，等于白跑一遍。
     */
    @PostMapping("/publish/batch")
    public ApiResponse<PredictionBatchPublishResultVo> publishBatch(
            @Valid @RequestBody PredictionBatchPublishDto request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        PredictionBatchPublishService service = batchPublishServiceProvider.getIfAvailable();
        if (service == null) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_ERROR,
                    "prediction publish service is unavailable"
            );
        }
        return ApiResponse.success(service.publish(request, requireUsername(jwt)));
    }

    private String requireUsername(Jwt jwt) {
        if (jwt == null) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
        }
        String username = jwt.getClaimAsString("username");
        if (username == null || username.isBlank()) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
        }
        return username;
    }
}
