package com.jingcaicompass.match.service;

import com.jingcaicompass.match.dto.MappingReviewConfirmDto;
import com.jingcaicompass.match.dto.MappingReviewBundleConfirmDto;
import com.jingcaicompass.match.dto.MappingReviewDetailQueryDto;
import com.jingcaicompass.match.dto.MappingReviewMatchDetailQueryDto;
import com.jingcaicompass.match.dto.MappingReviewListQueryDto;
import com.jingcaicompass.match.dto.MappingReviewRejectDto;
import com.jingcaicompass.match.dto.MappingReviewReopenDto;
import com.jingcaicompass.match.vo.MappingReviewDetailVo;
import com.jingcaicompass.match.vo.MappingReviewListItemVo;
import com.jingcaicompass.match.vo.MappingReviewMatchListItemVo;
import com.jingcaicompass.match.vo.MappingReviewMatchDetailVo;
import com.jingcaicompass.system.api.PageResult;

/** 映射人工复核：列表/详情/确认/拒绝/重新打开，并追加审计。 */
public interface MatchMappingReviewService {

    /** 分页查询映射；默认仅 PENDING。 */
    PageResult<MappingReviewListItemVo> list(MappingReviewListQueryDto query);

    /** 以竞彩比赛为主体，返回其已持久化外部候选。 */
    PageResult<MappingReviewMatchListItemVo> listByMatch(MappingReviewListQueryDto query);

    /** 按竞彩比赛读取已持久化外部候选；时间和队名仅供人工核对。 */
    MappingReviewMatchDetailVo detailByMatch(MappingReviewMatchDetailQueryDto query);

    /** 查询映射详情（含候选与内部比赛摘要）。 */
    MappingReviewDetailVo detail(MappingReviewDetailQueryDto query);

    /** PENDING → MANUAL_CONFIRMED；操作者必须来自已认证主体。 */
    MappingReviewDetailVo confirm(MappingReviewConfirmDto request, String operatorUsername);

    /** 赛事确认时可原子地确认已显式勾选的联赛、主队和客队关系。 */
    MappingReviewDetailVo confirmBundle(MappingReviewBundleConfirmDto request, String operatorUsername);

    /** PENDING → REJECTED；操作者必须来自已认证主体。 */
    MappingReviewDetailVo reject(MappingReviewRejectDto request, String operatorUsername);

    /** REJECTED → PENDING；操作者必须来自已认证主体。 */
    MappingReviewDetailVo reopen(MappingReviewReopenDto request, String operatorUsername);
}
