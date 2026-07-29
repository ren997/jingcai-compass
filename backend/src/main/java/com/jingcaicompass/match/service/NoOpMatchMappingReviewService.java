package com.jingcaicompass.match.service;

import com.jingcaicompass.match.dto.MappingReviewConfirmDto;
import com.jingcaicompass.match.dto.MappingReviewDetailQueryDto;
import com.jingcaicompass.match.dto.MappingReviewMatchDetailQueryDto;
import com.jingcaicompass.match.dto.MappingReviewListQueryDto;
import com.jingcaicompass.match.dto.MappingReviewRejectDto;
import com.jingcaicompass.match.dto.MappingReviewReopenDto;
import com.jingcaicompass.match.vo.MappingReviewDetailVo;
import com.jingcaicompass.match.vo.MappingReviewListItemVo;
import com.jingcaicompass.match.vo.MappingReviewMatchListItemVo;
import com.jingcaicompass.match.vo.MappingReviewMatchDetailVo;
import com.jingcaicompass.system.api.PageResult;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import java.util.List;

/**
 * 无 DataSource 时的占位实现，由 NoPersistenceAdminAutoConfiguration 显式装配。
 */
public class NoOpMatchMappingReviewService implements MatchMappingReviewService {

    @Override
    public PageResult<MappingReviewListItemVo> list(MappingReviewListQueryDto query) {
        return new PageResult<>(List.of(), 1, 20, 0);
    }

    @Override
    public PageResult<MappingReviewMatchListItemVo> listByMatch(MappingReviewListQueryDto query) {
        return new PageResult<>(List.of(), 1, 20, 0);
    }

    @Override
    public MappingReviewMatchDetailVo detailByMatch(MappingReviewMatchDetailQueryDto query) {
        throw unsupported();
    }

    @Override
    public MappingReviewDetailVo detail(MappingReviewDetailQueryDto query) {
        throw unsupported();
    }

    @Override
    public MappingReviewDetailVo confirm(MappingReviewConfirmDto request, String operatorUsername) {
        throw unsupported();
    }

    @Override
    public MappingReviewDetailVo reject(MappingReviewRejectDto request, String operatorUsername) {
        throw unsupported();
    }

    @Override
    public MappingReviewDetailVo reopen(MappingReviewReopenDto request, String operatorUsername) {
        throw unsupported();
    }

    private static BusinessException unsupported() {
        return new BusinessException(ErrorCode.BUSINESS_ERROR, "mapping review requires DataSource");
    }
}
