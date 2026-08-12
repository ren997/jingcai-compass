package com.jingcaicompass.admin.service;

import com.jingcaicompass.admin.dto.AdminDraftPredictionListQueryDto;
import com.jingcaicompass.admin.mapper.AdminDraftPredictionCriteria;
import com.jingcaicompass.admin.mapper.AdminDraftPredictionMapper;
import com.jingcaicompass.admin.mapper.AdminDraftPredictionRow;
import com.jingcaicompass.admin.vo.AdminDraftPredictionListItemVo;
import com.jingcaicompass.admin.vo.AdminDraftPredictionPageVo;
import com.jingcaicompass.admin.vo.AdminPredictionMatchVo;
import com.jingcaicompass.prediction.vo.PredictionAsianMarketVo;
import com.jingcaicompass.system.config.properties.PaginationProperties;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 为后台发布页装配 DRAFT 预测和比赛复核信息。 */
@Service
@ConditionalOnBean(DataSource.class)
public class AdminDraftPredictionQueryServiceImpl implements AdminDraftPredictionQueryService {

    private final AdminDraftPredictionMapper draftPredictionMapper;
    private final PaginationProperties paginationProperties;

    public AdminDraftPredictionQueryServiceImpl(
            AdminDraftPredictionMapper draftPredictionMapper,
            PaginationProperties paginationProperties
    ) {
        this.draftPredictionMapper = draftPredictionMapper;
        this.paginationProperties = paginationProperties;
    }

    @Override
    public AdminDraftPredictionPageVo list(AdminDraftPredictionListQueryDto query) {
        // 1) 规范化分页和可选筛选，防止管理页使用无界读取。
        int pageNo = pageNo(query == null ? null : query.pageNo());
        int pageSize = pageSize(query == null ? null : query.pageSize());
        AdminDraftPredictionCriteria criteria = new AdminDraftPredictionCriteria(
                query == null ? null : query.lotteryDate(),
                normalizedModelVersion(query == null ? null : query.modelVersion()),
                pageSize,
                (long) (pageNo - 1) * pageSize
        );

        // 2) 只读取 DRAFT 平铺投影并转为稳定的管理员复核视图。
        List<AdminDraftPredictionListItemVo> records = draftPredictionMapper.selectDraftPredictions(criteria).stream()
                .map(this::toItem)
                .toList();
        return new AdminDraftPredictionPageVo(records, pageNo, pageSize,
                draftPredictionMapper.countDraftPredictions(criteria));
    }

    private AdminDraftPredictionListItemVo toItem(AdminDraftPredictionRow row) {
        return new AdminDraftPredictionListItemVo(
                row.getPredictionId(), row.getModelVersion(), row.getFeatureVersion(), row.getGenerationBatchId(),
                row.getGenerationBatchHash(), row.getPredictionVersion(), row.getHomeWinProb(), row.getDrawProb(),
                row.getAwayWinProb(), row.getHandicapPick(), row.getExpectedTotalGoals(), row.getAsianHandicapPick(),
                row.getTotalGoalsPick(), asianMarket(row), row.getConfidenceLevel(),
                row.getAnalysisSummary(), row.getGeneratedAt(),
                new AdminPredictionMatchVo(row.getMatchId(), row.getLotteryDate(), row.getLotteryMatchNo(),
                        row.getLeagueName(), row.getHomeTeamName(), row.getAwayTeamName(), row.getKickoffTime(),
                        row.getOfficialHandicap())
        );
    }

    private PredictionAsianMarketVo asianMarket(AdminDraftPredictionRow row) {
        if (row.getAsianOddsSnapshotId() == null) {
            return null;
        }
        return new PredictionAsianMarketVo(
                row.getAsianOddsSnapshotId(),
                row.getAsianProviderCode(),
                row.getAsianBookmakerCode(),
                row.getAsianHandicapLine(),
                row.getAsianHomeOdds(),
                row.getAsianAwayOdds(),
                row.getAsianTotalLine(),
                row.getAsianOverOdds(),
                row.getAsianUnderOdds(),
                row.getAsianSnapshotType(),
                row.getAsianCapturedAt(),
                row.getAsianProviderUpdatedAt()
        );
    }

    private String normalizedModelVersion(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private int pageNo(Integer requested) {
        return requested == null || requested < 1 ? 1 : requested;
    }

    private int pageSize(Integer requested) {
        int value = requested == null || requested < 1 ? 20 : requested;
        return (int) Math.min(value, paginationProperties.maxPageSize());
    }
}
