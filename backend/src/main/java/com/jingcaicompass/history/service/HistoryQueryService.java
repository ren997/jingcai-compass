package com.jingcaicompass.history.service;

import com.jingcaicompass.history.dto.HistoryListQueryDto;
import com.jingcaicompass.history.vo.HistoryListItemVo;
import com.jingcaicompass.system.api.PageResult;

/** 面向公开端的预测、官方事实和结算历史查询。 */
public interface HistoryQueryService {

    /**
     * 按固定排序分页查询公开预测版本，并保留完整事实和结算版本链。
     *
     * @param query 可为空的筛选条件
     */
    PageResult<HistoryListItemVo> list(HistoryListQueryDto query);
}
