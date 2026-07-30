package com.jingcaicompass.match.service;

import com.jingcaicompass.match.dto.ProviderNormalizationCandidateQueryDto;
import com.jingcaicompass.match.dto.ProviderNormalizationReviewConfirmDto;
import com.jingcaicompass.match.dto.ProviderNormalizationReviewDetailQueryDto;
import com.jingcaicompass.match.dto.ProviderNormalizationReviewListQueryDto;
import com.jingcaicompass.match.dto.ProviderNormalizationReviewRejectDto;
import com.jingcaicompass.match.dto.ProviderNormalizationReviewReopenDto;
import com.jingcaicompass.match.vo.ProviderNormalizationEntityVo;
import com.jingcaicompass.match.vo.ProviderNormalizationReviewDetailVo;
import com.jingcaicompass.match.vo.ProviderNormalizationReviewListItemVo;
import com.jingcaicompass.system.api.PageResult;
import java.util.List;

/** 供应商联赛、球队映射的独立人工复核契约。 */
public interface ProviderNormalizationReviewService {

    /** 分页读取待复核或指定状态的供应商标准化映射。 */
    PageResult<ProviderNormalizationReviewListItemVo> list(ProviderNormalizationReviewListQueryDto query);

    /** 读取一条映射及其只追加审计历史。 */
    ProviderNormalizationReviewDetailVo detail(ProviderNormalizationReviewDetailQueryDto query);

    /** 搜索管理员可明确选择的内部标准实体。 */
    List<ProviderNormalizationEntityVo> candidates(ProviderNormalizationCandidateQueryDto query);

    /** 将 PENDING 映射条件更新为人工确认。 */
    ProviderNormalizationReviewDetailVo confirm(ProviderNormalizationReviewConfirmDto request, String operatorUsername);

    /** 将 PENDING 映射条件更新为拒绝。 */
    ProviderNormalizationReviewDetailVo reject(ProviderNormalizationReviewRejectDto request, String operatorUsername);

    /** 将 REJECTED 映射条件更新回待复核。 */
    ProviderNormalizationReviewDetailVo reopen(ProviderNormalizationReviewReopenDto request, String operatorUsername);
}
