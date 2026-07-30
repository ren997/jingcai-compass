package com.jingcaicompass.match.service;

import com.jingcaicompass.match.dto.ProviderNormalizationCandidateQueryDto;
import com.jingcaicompass.match.dto.ProviderNormalizationReviewConfirmDto;
import com.jingcaicompass.match.dto.ProviderNormalizationReviewDetailQueryDto;
import com.jingcaicompass.match.dto.ProviderNormalizationReviewListQueryDto;
import com.jingcaicompass.match.dto.ProviderNormalizationReviewRejectDto;
import com.jingcaicompass.match.dto.ProviderNormalizationReviewReopenDto;
import com.jingcaicompass.match.vo.ProviderNormalizationEntityVo;
import com.jingcaicompass.match.vo.ProviderNormalizationReviewDetailVo;
import com.jingcaicompass.match.vo.ProviderNormalizationReviewListItemVo;
import com.jingcaicompass.system.api.PageResult;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import java.util.List;

/** 无 DataSource 时的供应商标准化复核降级实现。 */
public class NoOpProviderNormalizationReviewService implements ProviderNormalizationReviewService {

    @Override
    public PageResult<ProviderNormalizationReviewListItemVo> list(ProviderNormalizationReviewListQueryDto query) {
        return new PageResult<>(List.of(), 1, 20, 0);
    }

    @Override
    public ProviderNormalizationReviewDetailVo detail(ProviderNormalizationReviewDetailQueryDto query) {
        throw unavailable();
    }

    @Override
    public List<ProviderNormalizationEntityVo> candidates(ProviderNormalizationCandidateQueryDto query) {
        return List.of();
    }

    @Override
    public ProviderNormalizationReviewDetailVo confirm(ProviderNormalizationReviewConfirmDto request, String operatorUsername) {
        throw unavailable();
    }

    @Override
    public ProviderNormalizationReviewDetailVo reject(ProviderNormalizationReviewRejectDto request, String operatorUsername) {
        throw unavailable();
    }

    @Override
    public ProviderNormalizationReviewDetailVo reopen(ProviderNormalizationReviewReopenDto request, String operatorUsername) {
        throw unavailable();
    }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.DATA_SOURCE_UNAVAILABLE);
    }
}
