package com.jingcaicompass.admin.service;

import com.jingcaicompass.admin.dto.AdminSportteryPoolBackfillDto;
import com.jingcaicompass.admin.vo.AdminSportteryPoolBackfillVo;

/** 管理员触发的体彩赛程池回填，负责窗口校验与逐日串行执行。 */
public interface AdminSportteryPoolBackfillService {

    /** 显式窗口必须为连续 1 至 7 天；某天失败不中断后续日期。 */
    AdminSportteryPoolBackfillVo backfill(AdminSportteryPoolBackfillDto request);
}
