package com.jingcaicompass.admin.service;

import com.jingcaicompass.admin.dto.AdminPredictionLockListQueryDto;
import com.jingcaicompass.admin.dto.AdminPredictionStatusDetailQueryDto;
import com.jingcaicompass.admin.dto.AdminSettlementStatusListQueryDto;
import com.jingcaicompass.admin.vo.AdminPredictionStatusDetailVo;
import com.jingcaicompass.admin.vo.AdminPredictionStatusPageVo;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;

/** 无数据库时保持后台状态接口可装配并返回统一降级错误。 */
public class NoOpAdminPredictionStatusQueryService implements AdminPredictionStatusQueryService {

    @Override
    public AdminPredictionStatusPageVo locks(AdminPredictionLockListQueryDto query) {
        throw unavailable();
    }

    @Override
    public AdminPredictionStatusPageVo settlements(AdminSettlementStatusListQueryDto query) {
        throw unavailable();
    }

    @Override
    public AdminPredictionStatusDetailVo detail(AdminPredictionStatusDetailQueryDto query) {
        throw unavailable();
    }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.DATA_SOURCE_UNAVAILABLE);
    }
}
