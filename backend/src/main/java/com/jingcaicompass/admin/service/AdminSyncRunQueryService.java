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

/** 管理员只读同步运行、错误、额度与安全载荷片段查询契约。 */
public interface AdminSyncRunQueryService {

    /** 分页读取同步运行。 */
    PageResult<AdminSyncRunListItemVo> list(AdminSyncRunListQueryDto query);

    /** 读取单条同步运行及其精确关联的脱敏载荷。 */
    AdminSyncRunDetailVo detail(AdminSyncRunDetailQueryDto query);

    /** 分页读取失败或部分成功运行。 */
    PageResult<AdminSyncRunErrorVo> errors(AdminSyncRunErrorQueryDto query);

    /** 汇总上海业务日内已消耗额度和预警阈值。 */
    AdminSyncRunQuotaSummaryVo quotaSummary(AdminSyncRunQuotaSummaryQueryDto query);
}
