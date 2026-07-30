package com.jingcaicompass.admin.service;

import com.jingcaicompass.admin.dto.AdminSportteryResultSyncDto;
import com.jingcaicompass.admin.vo.AdminSportteryResultSyncVo;

/** 管理员触发的体彩赛果同步，负责窗口校验与安全摘要。 */
public interface AdminSportteryResultSyncService {

    /** 默认上海昨日；显式窗口必须为连续 1 至 7 天且不包含未来日期。 */
    AdminSportteryResultSyncVo sync(AdminSportteryResultSyncDto request);
}
