package com.jingcaicompass.admin.service;

import com.jingcaicompass.admin.dto.AdminPredictionLockListQueryDto;
import com.jingcaicompass.admin.dto.AdminPredictionStatusDetailQueryDto;
import com.jingcaicompass.admin.dto.AdminSettlementStatusListQueryDto;
import com.jingcaicompass.admin.vo.AdminPredictionStatusDetailVo;
import com.jingcaicompass.admin.vo.AdminPredictionStatusPageVo;

/** 管理员只读预测锁定、结算状态和版本链查询契约。 */
public interface AdminPredictionStatusQueryService {

    /** 分页读取公开预测的锁定状态。 */
    AdminPredictionStatusPageVo locks(AdminPredictionLockListQueryDto query);

    /** 分页读取已锁定预测的待赛果、待结算和需重算状态。 */
    AdminPredictionStatusPageVo settlements(AdminSettlementStatusListQueryDto query);

    /** 读取一条运营预测的当前投影和完整版本链。 */
    AdminPredictionStatusDetailVo detail(AdminPredictionStatusDetailQueryDto query);
}
