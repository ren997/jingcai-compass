package com.jingcaicompass.home.service;

import com.jingcaicompass.home.vo.HomeSummaryVo;

/** 面向公开首页的事实汇总查询。 */
public interface HomeSummaryQueryService {

    /** 返回上海当天的比赛、预测、结算、表现和数据新鲜度摘要。 */
    HomeSummaryVo summary();
}
