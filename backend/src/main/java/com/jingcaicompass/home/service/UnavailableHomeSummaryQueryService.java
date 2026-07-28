package com.jingcaicompass.home.service;

import com.jingcaicompass.home.vo.HomeSummaryVo;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;

/** 无数据库运行配置下的公开首页查询占位，禁止回退到实时 Provider。 */
public class UnavailableHomeSummaryQueryService implements HomeSummaryQueryService {

    @Override
    public HomeSummaryVo summary() {
        throw new BusinessException(ErrorCode.DATA_SOURCE_UNAVAILABLE, "public home summary requires a database");
    }
}
