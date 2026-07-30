package com.jingcaicompass.admin.controller;

import com.jingcaicompass.match.dto.ProviderNormalizationCandidateQueryDto;
import com.jingcaicompass.match.dto.ProviderNormalizationReviewConfirmDto;
import com.jingcaicompass.match.dto.ProviderNormalizationReviewDetailQueryDto;
import com.jingcaicompass.match.dto.ProviderNormalizationReviewListQueryDto;
import com.jingcaicompass.match.dto.ProviderNormalizationReviewRejectDto;
import com.jingcaicompass.match.dto.ProviderNormalizationReviewReopenDto;
import com.jingcaicompass.match.service.ProviderNormalizationReviewService;
import com.jingcaicompass.match.vo.ProviderNormalizationEntityVo;
import com.jingcaicompass.match.vo.ProviderNormalizationReviewDetailVo;
import com.jingcaicompass.match.vo.ProviderNormalizationReviewListItemVo;
import com.jingcaicompass.system.api.ApiResponse;
import com.jingcaicompass.system.api.PageResult;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 供应商联赛、球队标准化人工复核后台接口。 */
@RestController
@RequestMapping("/api/admin/provider/normalizations")
public class ProviderNormalizationReviewController {

    private final ProviderNormalizationReviewService providerNormalizationReviewService;

    public ProviderNormalizationReviewController(ProviderNormalizationReviewService providerNormalizationReviewService) {
        this.providerNormalizationReviewService = providerNormalizationReviewService;
    }

    /** 分页读取联赛或球队标准化复核队列，默认仅 PENDING。 */
    @PostMapping("/list")
    public ApiResponse<PageResult<ProviderNormalizationReviewListItemVo>> list(
            @RequestBody ProviderNormalizationReviewListQueryDto query
    ) {
        return ApiResponse.success(providerNormalizationReviewService.list(query));
    }

    /** 读取一条复核映射与追加审计历史。 */
    @PostMapping("/detail")
    public ApiResponse<ProviderNormalizationReviewDetailVo> detail(
            @RequestBody ProviderNormalizationReviewDetailQueryDto query
    ) {
        return ApiResponse.success(providerNormalizationReviewService.detail(query));
    }

    /** 按名称在内部标准字典中搜索管理员可选择的确认目标。 */
    @PostMapping("/candidates/list")
    public ApiResponse<List<ProviderNormalizationEntityVo>> candidates(
            @RequestBody ProviderNormalizationCandidateQueryDto query
    ) {
        return ApiResponse.success(providerNormalizationReviewService.candidates(query));
    }

    /** 确认：PENDING → MANUAL_CONFIRMED。 */
    @PostMapping("/confirm")
    public ApiResponse<ProviderNormalizationReviewDetailVo> confirm(
            @RequestBody ProviderNormalizationReviewConfirmDto request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(providerNormalizationReviewService.confirm(request, requireUsername(jwt)));
    }

    /** 拒绝：PENDING → REJECTED。 */
    @PostMapping("/reject")
    public ApiResponse<ProviderNormalizationReviewDetailVo> reject(
            @RequestBody ProviderNormalizationReviewRejectDto request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(providerNormalizationReviewService.reject(request, requireUsername(jwt)));
    }

    /** 重新打开：REJECTED → PENDING。 */
    @PostMapping("/reopen")
    public ApiResponse<ProviderNormalizationReviewDetailVo> reopen(
            @RequestBody ProviderNormalizationReviewReopenDto request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(providerNormalizationReviewService.reopen(request, requireUsername(jwt)));
    }

    private static String requireUsername(Jwt jwt) {
        if (jwt == null || jwt.getClaimAsString("username") == null || jwt.getClaimAsString("username").isBlank()) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
        }
        return jwt.getClaimAsString("username");
    }
}
