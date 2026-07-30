package com.jingcaicompass.admin.controller;

import com.jingcaicompass.match.dto.MappingReviewConfirmDto;
import com.jingcaicompass.match.dto.MappingReviewBundleConfirmDto;
import com.jingcaicompass.match.dto.MappingReviewDetailQueryDto;
import com.jingcaicompass.match.dto.MappingReviewMatchDetailQueryDto;
import com.jingcaicompass.match.dto.MappingReviewListQueryDto;
import com.jingcaicompass.match.dto.MappingReviewRejectDto;
import com.jingcaicompass.match.dto.MappingReviewReopenDto;
import com.jingcaicompass.match.service.MatchMappingReviewService;
import com.jingcaicompass.match.vo.MappingReviewDetailVo;
import com.jingcaicompass.match.vo.MappingReviewListItemVo;
import com.jingcaicompass.match.vo.MappingReviewMatchListItemVo;
import com.jingcaicompass.match.vo.MappingReviewMatchDetailVo;
import com.jingcaicompass.system.api.ApiResponse;
import com.jingcaicompass.system.api.PageResult;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 供应商比赛映射人工复核后台接口。
 * 操作者身份只从已校验的管理员 JWT 取得。
 */
@RestController
@RequestMapping("/api/admin/provider/mappings")
public class ProviderMappingReviewController {

    private final MatchMappingReviewService matchMappingReviewService;

    public ProviderMappingReviewController(MatchMappingReviewService matchMappingReviewService) {
        this.matchMappingReviewService = matchMappingReviewService;
    }

    /** 分页列表；默认 PENDING。 */
    @PostMapping("/list")
    public ApiResponse<PageResult<MappingReviewListItemVo>> list(
            @RequestBody(required = false) MappingReviewListQueryDto query
    ) {
        return ApiResponse.success(matchMappingReviewService.list(query));
    }

    /** 按竞彩比赛分页展示已持久化的外部候选，供人工选择。 */
    @PostMapping("/matches/list")
    public ApiResponse<PageResult<MappingReviewMatchListItemVo>> listByMatch(
            @RequestBody(required = false) MappingReviewListQueryDto query
    ) {
        return ApiResponse.success(matchMappingReviewService.listByMatch(query));
    }

    /** 读取一场竞彩比赛及其可安全确认的外部候选。 */
    @PostMapping("/matches/detail")
    public ApiResponse<MappingReviewMatchDetailVo> detailByMatch(
            @RequestBody MappingReviewMatchDetailQueryDto query
    ) {
        return ApiResponse.success(matchMappingReviewService.detailByMatch(query));
    }

    /** 详情（含候选与内部比赛摘要）。 */
    @PostMapping("/detail")
    public ApiResponse<MappingReviewDetailVo> detail(@RequestBody MappingReviewDetailQueryDto query) {
        return ApiResponse.success(matchMappingReviewService.detail(query));
    }

    /** 确认：PENDING → MANUAL_CONFIRMED。 */
    @PostMapping("/confirm")
    public ApiResponse<MappingReviewDetailVo> confirm(
            @RequestBody MappingReviewConfirmDto request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(matchMappingReviewService.confirm(request, requireUsername(jwt)));
    }

    /** 确认赛事，并按管理员勾选原子确认联赛、主队和客队标准化关系。 */
    @PostMapping("/confirm-bundle")
    public ApiResponse<MappingReviewDetailVo> confirmBundle(
            @RequestBody MappingReviewBundleConfirmDto request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(matchMappingReviewService.confirmBundle(request, requireUsername(jwt)));
    }

    /** 拒绝：PENDING → REJECTED。 */
    @PostMapping("/reject")
    public ApiResponse<MappingReviewDetailVo> reject(
            @RequestBody MappingReviewRejectDto request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(matchMappingReviewService.reject(request, requireUsername(jwt)));
    }

    /** 重新打开：REJECTED → PENDING。 */
    @PostMapping("/reopen")
    public ApiResponse<MappingReviewDetailVo> reopen(
            @RequestBody MappingReviewReopenDto request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(matchMappingReviewService.reopen(request, requireUsername(jwt)));
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
