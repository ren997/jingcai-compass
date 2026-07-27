package com.jingcaicompass.history.service;

import com.jingcaicompass.history.dto.HistoryListQueryDto;
import com.jingcaicompass.history.vo.HistoryListItemVo;
import com.jingcaicompass.system.api.PageResult;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;

/** 无数据库运行配置下的公开历史服务占位，保留统一错误语义。 */
public class UnavailableHistoryQueryService implements HistoryQueryService {

    @Override
    public PageResult<HistoryListItemVo> list(HistoryListQueryDto query) {
        throw new BusinessException(ErrorCode.DATA_SOURCE_UNAVAILABLE, "public history requires a database");
    }
}
