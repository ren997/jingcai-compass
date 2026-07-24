package com.jingcaicompass.match.service;

import com.jingcaicompass.match.dto.MappingReviewConfirmDto;
import com.jingcaicompass.match.dto.MappingReviewDetailQueryDto;
import com.jingcaicompass.match.dto.MappingReviewListQueryDto;
import com.jingcaicompass.match.dto.MappingReviewRejectDto;
import com.jingcaicompass.match.dto.MappingReviewReopenDto;
import com.jingcaicompass.match.vo.MappingReviewDetailVo;
import com.jingcaicompass.match.vo.MappingReviewListItemVo;
import com.jingcaicompass.system.api.PageResult;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

/**
 * 无 DataSource 时的占位实现，保证 admin Controller 在排除数据源的 test profile 下可装配。
 * 使用 MissingBean(DataSource) 而非 MissingBean(接口)，避免与未生效的 Impl 定义互相挤掉。
 */
@Service
@ConditionalOnMissingBean(DataSource.class)
public class NoOpMatchMappingReviewService implements MatchMappingReviewService {

    @Override
    public PageResult<MappingReviewListItemVo> list(MappingReviewListQueryDto query) {
        return new PageResult<>(List.of(), 1, 20, 0);
    }

    @Override
    public MappingReviewDetailVo detail(MappingReviewDetailQueryDto query) {
        throw unsupported();
    }

    @Override
    public MappingReviewDetailVo confirm(MappingReviewConfirmDto request) {
        throw unsupported();
    }

    @Override
    public MappingReviewDetailVo reject(MappingReviewRejectDto request) {
        throw unsupported();
    }

    @Override
    public MappingReviewDetailVo reopen(MappingReviewReopenDto request) {
        throw unsupported();
    }

    private static BusinessException unsupported() {
        return new BusinessException(ErrorCode.BUSINESS_ERROR, "mapping review requires DataSource");
    }
}
