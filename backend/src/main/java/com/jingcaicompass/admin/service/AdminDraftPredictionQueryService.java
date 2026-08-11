package com.jingcaicompass.admin.service;

import com.jingcaicompass.admin.dto.AdminDraftPredictionListQueryDto;
import com.jingcaicompass.admin.vo.AdminDraftPredictionPageVo;

/** 管理员草稿预测的隔离查询服务，不改变或发布草稿。 */
public interface AdminDraftPredictionQueryService {

    /** 分页读取仅供管理员发布前复核的 DRAFT 预测。 */
    AdminDraftPredictionPageVo list(AdminDraftPredictionListQueryDto query);
}
