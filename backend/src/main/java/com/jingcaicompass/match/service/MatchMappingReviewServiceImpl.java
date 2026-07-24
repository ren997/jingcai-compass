package com.jingcaicompass.match.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jingcaicompass.audit.enums.AuditActionTypeEnum;
import com.jingcaicompass.audit.enums.AuditTargetTypeEnum;
import com.jingcaicompass.audit.service.AuditLogService;
import com.jingcaicompass.match.dto.MatchMapCandidateDto;
import com.jingcaicompass.match.dto.MappingReviewConfirmDto;
import com.jingcaicompass.match.dto.MappingReviewDetailQueryDto;
import com.jingcaicompass.match.dto.MappingReviewListQueryDto;
import com.jingcaicompass.match.dto.MappingReviewRejectDto;
import com.jingcaicompass.match.dto.MappingReviewReopenDto;
import com.jingcaicompass.match.entity.MatchEntity;
import com.jingcaicompass.match.entity.MatchSourceMapping;
import com.jingcaicompass.match.enums.MappingStatusEnum;
import com.jingcaicompass.match.mapper.MatchMapper;
import com.jingcaicompass.match.mapper.MatchSourceMappingMapper;
import com.jingcaicompass.match.vo.MappingReviewDetailVo;
import com.jingcaicompass.match.vo.MappingReviewListItemVo;
import com.jingcaicompass.system.api.PageResult;
import com.jingcaicompass.system.config.properties.PaginationProperties;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 映射人工复核实现：条件更新状态机 + 追加审计。 */
@Service
@ConditionalOnBean(DataSource.class)
public class MatchMappingReviewServiceImpl implements MatchMappingReviewService {

    static final String METHOD_MANUAL_REVIEW = "MANUAL_REVIEW";

    private final MatchSourceMappingMapper matchSourceMappingMapper;
    private final MatchMapper matchMapper;
    private final AuditLogService auditLogService;
    private final PaginationProperties paginationProperties;

    public MatchMappingReviewServiceImpl(
            MatchSourceMappingMapper matchSourceMappingMapper,
            MatchMapper matchMapper,
            AuditLogService auditLogService,
            PaginationProperties paginationProperties
    ) {
        this.matchSourceMappingMapper = matchSourceMappingMapper;
        this.matchMapper = matchMapper;
        this.auditLogService = auditLogService;
        this.paginationProperties = paginationProperties;
    }

    @Override
    public PageResult<MappingReviewListItemVo> list(MappingReviewListQueryDto query) {
        int pageNo = query == null || query.pageNo() == null || query.pageNo() < 1 ? 1 : query.pageNo();
        long requestedSize = query == null || query.pageSize() == null || query.pageSize() < 1
                ? 20
                : query.pageSize();
        long pageSize = Math.min(requestedSize, paginationProperties.maxPageSize());

        MappingStatusEnum status = query == null || query.mappingStatus() == null
                ? MappingStatusEnum.PENDING
                : query.mappingStatus();

        LambdaQueryWrapper<MatchSourceMapping> wrapper = new LambdaQueryWrapper<MatchSourceMapping>()
                .eq(MatchSourceMapping::getMappingStatus, status)
                .orderByDesc(MatchSourceMapping::getUpdatedAt);
        if (query != null && StringUtils.hasText(query.providerCode())) {
            wrapper.eq(MatchSourceMapping::getProviderCode, query.providerCode().trim());
        }

        Page<MatchSourceMapping> page = matchSourceMappingMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
        List<MappingReviewListItemVo> records = page.getRecords().stream()
                .map(this::toListItem)
                .toList();
        return new PageResult<>(records, page.getCurrent(), page.getSize(), page.getTotal());
    }

    @Override
    public MappingReviewDetailVo detail(MappingReviewDetailQueryDto query) {
        Objects.requireNonNull(query, "query must not be null");
        if (query.mappingId() == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "mappingId must not be null");
        }
        MatchSourceMapping mapping = requireMapping(query.mappingId());
        return toDetail(mapping);
    }

    @Override
    @Transactional
    public MappingReviewDetailVo confirm(MappingReviewConfirmDto request) {
        Objects.requireNonNull(request, "request must not be null");
        if (request.mappingId() == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "mappingId must not be null");
        }
        if (!StringUtils.hasText(request.operatorId())) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "operatorId must not be blank");
        }

        // 1) 读取当前 PENDING 行
        MatchSourceMapping current = requireMapping(request.mappingId());
        if (current.getMappingStatus() != MappingStatusEnum.PENDING) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_ERROR,
                    "mapping confirm conflict: expected PENDING but was " + current.getMappingStatus()
            );
        }

        Long targetMatchId = request.targetMatchId() == null ? current.getMatchId() : request.targetMatchId();
        if (targetMatchId == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "targetMatchId must not be null");
        }
        if (matchMapper.selectById(targetMatchId) == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "match not found: " + targetMatchId);
        }

        String oldSnapshot = snapshot(current);
        // 2) 条件更新：仅 PENDING 可确认
        UpdateWrapper<MatchSourceMapping> update = new UpdateWrapper<MatchSourceMapping>()
                .eq("id", current.getId())
                .eq("mapping_status", MappingStatusEnum.PENDING.getCode())
                .set("mapping_status", MappingStatusEnum.MANUAL_CONFIRMED.getCode())
                .set("match_id", targetMatchId)
                .set("confirmed_by", request.operatorId().trim())
                .set("mapping_method", METHOD_MANUAL_REVIEW);
        int rows = matchSourceMappingMapper.update(null, update);
        if (rows == 0) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "mapping confirm conflict: not PENDING");
        }

        MatchSourceMapping updated = requireMapping(current.getId());
        // 3) 追加审计
        auditLogService.append(
                request.operatorId().trim(),
                AuditTargetTypeEnum.MATCH_SOURCE_MAPPING,
                String.valueOf(current.getId()),
                AuditActionTypeEnum.CONFIRM,
                "mappingStatus",
                oldSnapshot,
                snapshot(updated)
        );
        return toDetail(updated);
    }

    @Override
    @Transactional
    public MappingReviewDetailVo reject(MappingReviewRejectDto request) {
        Objects.requireNonNull(request, "request must not be null");
        if (request.mappingId() == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "mappingId must not be null");
        }
        if (!StringUtils.hasText(request.operatorId())) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "operatorId must not be blank");
        }

        MatchSourceMapping current = requireMapping(request.mappingId());
        if (current.getMappingStatus() != MappingStatusEnum.PENDING) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_ERROR,
                    "mapping reject conflict: expected PENDING but was " + current.getMappingStatus()
            );
        }

        String oldSnapshot = snapshot(current);
        String explanation = StringUtils.hasText(request.reason())
                ? "REJECTED: " + request.reason().trim()
                : "REJECTED";

        UpdateWrapper<MatchSourceMapping> update = new UpdateWrapper<MatchSourceMapping>()
                .eq("id", current.getId())
                .eq("mapping_status", MappingStatusEnum.PENDING.getCode())
                .set("mapping_status", MappingStatusEnum.REJECTED.getCode())
                .set("confirmed_by", request.operatorId().trim())
                .set("mapping_method", METHOD_MANUAL_REVIEW)
                .set("mapping_explanation", explanation);
        int rows = matchSourceMappingMapper.update(null, update);
        if (rows == 0) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "mapping reject conflict: not PENDING");
        }

        MatchSourceMapping updated = requireMapping(current.getId());
        auditLogService.append(
                request.operatorId().trim(),
                AuditTargetTypeEnum.MATCH_SOURCE_MAPPING,
                String.valueOf(current.getId()),
                AuditActionTypeEnum.REJECT,
                "mappingStatus",
                oldSnapshot,
                snapshot(updated)
        );
        return toDetail(updated);
    }

    @Override
    @Transactional
    public MappingReviewDetailVo reopen(MappingReviewReopenDto request) {
        Objects.requireNonNull(request, "request must not be null");
        if (request.mappingId() == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "mappingId must not be null");
        }
        if (!StringUtils.hasText(request.operatorId())) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "operatorId must not be blank");
        }

        MatchSourceMapping current = requireMapping(request.mappingId());
        if (current.getMappingStatus() != MappingStatusEnum.REJECTED) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_ERROR,
                    "mapping reopen conflict: expected REJECTED but was " + current.getMappingStatus()
            );
        }

        String oldSnapshot = snapshot(current);
        UpdateWrapper<MatchSourceMapping> update = new UpdateWrapper<MatchSourceMapping>()
                .eq("id", current.getId())
                .eq("mapping_status", MappingStatusEnum.REJECTED.getCode())
                .set("mapping_status", MappingStatusEnum.PENDING.getCode())
                .setSql("confirmed_by = NULL")
                .set("mapping_method", METHOD_MANUAL_REVIEW);
        int rows = matchSourceMappingMapper.update(null, update);
        if (rows == 0) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "mapping reopen conflict: not REJECTED");
        }

        MatchSourceMapping updated = requireMapping(current.getId());
        auditLogService.append(
                request.operatorId().trim(),
                AuditTargetTypeEnum.MATCH_SOURCE_MAPPING,
                String.valueOf(current.getId()),
                AuditActionTypeEnum.REOPEN,
                "mappingStatus",
                oldSnapshot,
                snapshot(updated)
        );
        return toDetail(updated);
    }

    private MatchSourceMapping requireMapping(Long mappingId) {
        MatchSourceMapping mapping = matchSourceMappingMapper.selectById(mappingId);
        if (mapping == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "mapping not found: " + mappingId);
        }
        return mapping;
    }

    private MappingReviewListItemVo toListItem(MatchSourceMapping mapping) {
        int candidateCount = mapping.getMappingCandidates() == null ? 0 : mapping.getMappingCandidates().size();
        return new MappingReviewListItemVo(
                mapping.getId(),
                mapping.getMatchId(),
                mapping.getProviderCode(),
                mapping.getExternalMatchId(),
                mapping.getMappingStatus(),
                mapping.getMappingConfidence(),
                mapping.getMappingMethod(),
                mapping.getMappingExplanation(),
                candidateCount,
                mapping.getConfirmedBy(),
                mapping.getUpdatedAt()
        );
    }

    private MappingReviewDetailVo toDetail(MatchSourceMapping mapping) {
        MappingReviewDetailVo.MatchBriefVo brief = null;
        if (mapping.getMatchId() != null) {
            MatchEntity match = matchMapper.selectById(mapping.getMatchId());
            if (match != null) {
                brief = new MappingReviewDetailVo.MatchBriefVo(
                        match.getId(),
                        match.getLotteryMatchNo(),
                        match.getLotteryDate(),
                        match.getLeagueName(),
                        match.getHomeTeamName(),
                        match.getAwayTeamName(),
                        match.getKickoffTime()
                );
            }
        }
        return new MappingReviewDetailVo(
                mapping.getId(),
                mapping.getMatchId(),
                mapping.getProviderCode(),
                mapping.getExternalMatchId(),
                mapping.getExternalLeagueId(),
                mapping.getExternalHomeTeamId(),
                mapping.getExternalAwayTeamId(),
                mapping.getMappingStatus(),
                mapping.getMappingConfidence(),
                mapping.getMappingMethod(),
                mapping.getMappingExplanation(),
                toCandidateDtos(mapping.getMappingCandidates()),
                mapping.getConfirmedBy(),
                brief,
                mapping.getUpdatedAt()
        );
    }

    private static List<MatchMapCandidateDto> toCandidateDtos(List<Map<String, Object>> maps) {
        if (maps == null || maps.isEmpty()) {
            return List.of();
        }
        List<MatchMapCandidateDto> result = new ArrayList<>();
        for (Map<String, Object> map : maps) {
            Long matchId = map.get("matchId") == null ? null : ((Number) map.get("matchId")).longValue();
            java.math.BigDecimal score = map.get("score") == null
                    ? null
                    : new java.math.BigDecimal(map.get("score").toString());
            @SuppressWarnings("unchecked")
            List<String> reasons = map.get("reasons") instanceof List<?> list
                    ? list.stream().map(String::valueOf).collect(Collectors.toList())
                    : List.of();
            result.add(new MatchMapCandidateDto(matchId, score, reasons));
        }
        return result;
    }

    private static String snapshot(MatchSourceMapping mapping) {
        return "status=" + mapping.getMappingStatus()
                + ";matchId=" + mapping.getMatchId()
                + ";method=" + mapping.getMappingMethod()
                + ";confirmedBy=" + mapping.getConfirmedBy();
    }
}
