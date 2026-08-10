package com.jingcaicompass.admin.service;

import com.jingcaicompass.admin.dto.AdminManualMatchResultDto;
import com.jingcaicompass.admin.vo.AdminManualMatchResultVo;

/** 受控人工赛果补录：写入事实证据后只定向联动既有结算流程。 */
public interface AdminManualMatchResultService {

    /**
     * 以已认证操作者追加或修正一场已开赛比赛的人工事实。
     *
     * @param request 人工赛果输入
     * @param operator 已认证管理员用户名
     * @return 事实版本与定向结算处理摘要
     */
    AdminManualMatchResultVo record(AdminManualMatchResultDto request, String operator);
}
