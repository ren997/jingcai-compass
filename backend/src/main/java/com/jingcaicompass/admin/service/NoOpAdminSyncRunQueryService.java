package com.jingcaicompass.admin.service;

import com.jingcaicompass.admin.dto.AdminSyncRunDetailQueryDto;
import com.jingcaicompass.admin.dto.AdminSyncRunErrorQueryDto;
import com.jingcaicompass.admin.dto.AdminSyncRunListQueryDto;
import com.jingcaicompass.admin.dto.AdminSyncRunQuotaSummaryQueryDto;
import com.jingcaicompass.admin.vo.AdminSyncRunDetailVo;
import com.jingcaicompass.admin.vo.AdminSyncRunErrorVo;
import com.jingcaicompass.admin.vo.AdminSyncRunListItemVo;
import com.jingcaicompass.admin.vo.AdminSyncRunQuotaSummaryVo;
import com.jingcaicompass.system.api.PageResult;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;

/** 无数据库时保持后台观测接口可装配，并返回统一数据源不可用错误。 */
public class NoOpAdminSyncRunQueryService implements AdminSyncRunQueryService {

    @Override
    public PageResult<AdminSyncRunListItemVo> list(AdminSyncRunListQueryDto query) {
        throw unavailable();
    }

    @Override
    public AdminSyncRunDetailVo detail(AdminSyncRunDetailQueryDto query) {
        throw unavailable();
    }

    @Override
    public PageResult<AdminSyncRunErrorVo> errors(AdminSyncRunErrorQueryDto query) {
        throw unavailable();
    }

    @Override
    public AdminSyncRunQuotaSummaryVo quotaSummary(AdminSyncRunQuotaSummaryQueryDto query) {
        throw unavailable();
    }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.DATA_SOURCE_UNAVAILABLE);
    }
}
