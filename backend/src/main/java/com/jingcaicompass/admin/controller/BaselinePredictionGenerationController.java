package com.jingcaicompass.admin.controller;

import com.jingcaicompass.prediction.dto.BaselinePredictionGenerateDto;
import com.jingcaicompass.prediction.service.BaselinePredictionGenerationService;
import com.jingcaicompass.prediction.vo.BaselinePredictionGenerationVo;
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

/** 管理员受控触发首版离线预测生成；结果只会导入为 DRAFT。 */
@RestController
@RequestMapping("/api/admin/predictions/baseline")
public class BaselinePredictionGenerationController {

    private final ObjectProvider<BaselinePredictionGenerationService> generationServiceProvider;

    public BaselinePredictionGenerationController(
            ObjectProvider<BaselinePredictionGenerationService> generationServiceProvider
    ) {
        this.generationServiceProvider = generationServiceProvider;
    }

    /** 按竞彩业务日生成并导入预测草稿，不自动发布。 */
    @PostMapping("/generate")
    public ApiResponse<BaselinePredictionGenerationVo> generate(
            @Valid @RequestBody BaselinePredictionGenerateDto request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        BaselinePredictionGenerationService service = generationServiceProvider.getIfAvailable();
        if (service == null) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_ERROR,
                    "baseline prediction generation service is unavailable"
            );
        }
        return ApiResponse.success(service.generateAndImport(request.lotteryDate(), requireUsername(jwt)));
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
