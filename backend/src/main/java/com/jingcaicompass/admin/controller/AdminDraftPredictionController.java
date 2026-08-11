package com.jingcaicompass.admin.controller;

import com.jingcaicompass.admin.dto.AdminDraftPredictionListQueryDto;
import com.jingcaicompass.admin.service.AdminDraftPredictionQueryService;
import com.jingcaicompass.admin.vo.AdminDraftPredictionPageVo;
import com.jingcaicompass.system.api.ApiResponse;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 受管理员 JWT 保护的草稿预测复核查询接口。 */
@RestController
@RequestMapping("/api/admin/predictions/drafts")
public class AdminDraftPredictionController {

    private final ObjectProvider<AdminDraftPredictionQueryService> queryServiceProvider;

    public AdminDraftPredictionController(ObjectProvider<AdminDraftPredictionQueryService> queryServiceProvider) {
        this.queryServiceProvider = queryServiceProvider;
    }

    /** 分页读取仅面向管理员的 DRAFT 预测；发布仍由既有单条接口处理。 */
    @PostMapping("/list")
    public ApiResponse<AdminDraftPredictionPageVo> list(
            @Valid @RequestBody(required = false) AdminDraftPredictionListQueryDto request
    ) {
        AdminDraftPredictionQueryService service = queryServiceProvider.getIfAvailable();
        if (service == null) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "draft prediction query service is unavailable");
        }
        return ApiResponse.success(service.list(request));
    }
}
