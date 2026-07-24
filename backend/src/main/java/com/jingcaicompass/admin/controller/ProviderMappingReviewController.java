package com.jingcaicompass.admin.controller;

import com.jingcaicompass.match.dto.MappingReviewConfirmDto;
import com.jingcaicompass.match.dto.MappingReviewDetailQueryDto;
import com.jingcaicompass.match.dto.MappingReviewListQueryDto;
import com.jingcaicompass.match.dto.MappingReviewRejectDto;
import com.jingcaicompass.match.dto.MappingReviewReopenDto;
import com.jingcaicompass.match.service.MatchMappingReviewService;
import com.jingcaicompass.match.vo.MappingReviewDetailVo;
import com.jingcaicompass.match.vo.MappingReviewListItemVo;
import com.jingcaicompass.system.api.ApiResponse;
import com.jingcaicompass.system.api.PageResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 供应商比赛映射人工复核后台接口。
 * 生产环境在 T601 前由 Security 拒绝；服务层契约可独立测试。
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

    /** 详情（含候选与内部比赛摘要）。 */
    @PostMapping("/detail")
    public ApiResponse<MappingReviewDetailVo> detail(@RequestBody MappingReviewDetailQueryDto query) {
        return ApiResponse.success(matchMappingReviewService.detail(query));
    }

    /** 确认：PENDING → MANUAL_CONFIRMED。 */
    @PostMapping("/confirm")
    public ApiResponse<MappingReviewDetailVo> confirm(@RequestBody MappingReviewConfirmDto request) {
        return ApiResponse.success(matchMappingReviewService.confirm(request));
    }

    /** 拒绝：PENDING → REJECTED。 */
    @PostMapping("/reject")
    public ApiResponse<MappingReviewDetailVo> reject(@RequestBody MappingReviewRejectDto request) {
        return ApiResponse.success(matchMappingReviewService.reject(request));
    }

    /** 重新打开：REJECTED → PENDING。 */
    @PostMapping("/reopen")
    public ApiResponse<MappingReviewDetailVo> reopen(@RequestBody MappingReviewReopenDto request) {
        return ApiResponse.success(matchMappingReviewService.reopen(request));
    }
}
