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
import com.jingcaicompass.match.dto.MappingReviewMatchDetailQueryDto;
import com.jingcaicompass.match.dto.MappingReviewListQueryDto;
import com.jingcaicompass.match.dto.MappingReviewRejectDto;
import com.jingcaicompass.match.dto.MappingReviewReopenDto;
import com.jingcaicompass.match.entity.MatchEntity;
import com.jingcaicompass.match.entity.MatchSourceMapping;
import com.jingcaicompass.match.enums.MappingStatusEnum;
import com.jingcaicompass.match.enums.MappingReviewScopeEnum;
import com.jingcaicompass.match.mapper.MatchMapper;
import com.jingcaicompass.match.mapper.MatchSourceMappingMapper;
import com.jingcaicompass.match.vo.MappingReviewDetailVo;
import com.jingcaicompass.match.vo.MappingReviewListItemVo;
import com.jingcaicompass.match.vo.MappingReviewMatchListItemVo;
import com.jingcaicompass.match.vo.MappingReviewMatchDetailVo;
import com.jingcaicompass.system.api.PageResult;
import com.jingcaicompass.system.config.properties.PaginationProperties;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    public PageResult<MappingReviewMatchListItemVo> listByMatch(MappingReviewListQueryDto query) {
        int pageNo = query == null || query.pageNo() == null || query.pageNo() < 1 ? 1 : query.pageNo();
        long requestedSize = query == null || query.pageSize() == null || query.pageSize() < 1
                ? 20
                : query.pageSize();
        long pageSize = Math.min(requestedSize, paginationProperties.maxPageSize());
        MappingStatusEnum status = query == null || query.mappingStatus() == null
                ? MappingStatusEnum.PENDING
                : query.mappingStatus();
        MappingReviewScopeEnum reviewScope = reviewScope(query);
        String providerCode = query == null || !StringUtils.hasText(query.providerCode())
                ? null
                : query.providerCode().trim();

        // 1) 先按竞彩比赛分页，避免外部事件重复占据复核列表。
        long total = matchMapper.countMappingReviewMatches(providerCode, status.getCode(), reviewScope.name());
        List<MatchEntity> matches = matchMapper.selectMappingReviewMatchPage(
                providerCode,
                status.getCode(),
                reviewScope.name(),
                (long) (pageNo - 1) * pageSize,
                pageSize
        );
        if (matches == null || matches.isEmpty()) {
            return new PageResult<>(List.of(), pageNo, pageSize, total);
        }

        // 2) 批量读取当前页比赛可选择的外部事件，不做逐场查询。
        List<Long> matchIds = matches.stream().map(MatchEntity::getId).filter(Objects::nonNull).toList();
        List<Long> mappingIds = matchIds.isEmpty()
                ? List.of()
                : matchSourceMappingMapper.selectReviewMappingIdsForMatches(providerCode, status.getCode(), matchIds);
        List<MatchSourceMapping> mappings = mappingIds == null || mappingIds.isEmpty()
                ? List.of()
                : matchSourceMappingMapper.selectBatchIds(mappingIds);

        // 3) 每个竞彩比赛仅展示服务端保留的外部候选，确认仍受既有候选校验保护。
        List<MappingReviewMatchListItemVo> records = matches.stream()
                .map(match -> new MappingReviewMatchListItemVo(
                        toMatchBrief(match),
                        mappings.stream()
                                .map(mapping -> toExternalCandidate(mapping, match.getId()))
                                .filter(Objects::nonNull)
                                .sorted(Comparator.comparing(
                                                MappingReviewMatchListItemVo.ExternalCandidateVo::updatedAt,
                                                Comparator.nullsLast(Comparator.reverseOrder()))
                                        .thenComparing(MappingReviewMatchListItemVo.ExternalCandidateVo::mappingId,
                                                Comparator.nullsLast(Comparator.reverseOrder())))
                                .toList()
                ))
                .toList();
        return new PageResult<>(records, pageNo, pageSize, total);
    }

    @Override
    public MappingReviewMatchDetailVo detailByMatch(MappingReviewMatchDetailQueryDto query) {
        if (query == null || query.matchId() == null || query.matchId() < 1) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "matchId must be a positive integer");
        }
        MatchEntity match = matchMapper.selectById(query.matchId());
        if (match == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "match not found: " + query.matchId());
        }
        MappingStatusEnum status = query.mappingStatus() == null ? MappingStatusEnum.PENDING : query.mappingStatus();
        String providerCode = StringUtils.hasText(query.providerCode()) ? query.providerCode().trim() : null;

        // 1) 仅读取当前竞彩比赛已持久化的外部候选，不接受客户端任意赛事 ID。
        List<Long> mappingIds = matchSourceMappingMapper.selectReviewMappingIdsForMatches(
                providerCode, status.getCode(), List.of(match.getId())
        );
        List<MatchSourceMapping> mappings = mappingIds == null || mappingIds.isEmpty()
                ? List.of()
                : matchSourceMappingMapper.selectBatchIds(mappingIds);

        // 2) 返回可读外部队名和供应商原始开赛时间，供与官方开赛时间并列核对。
        List<MappingReviewMatchListItemVo.ExternalCandidateVo> candidates = mappings.stream()
                .map(mapping -> toExternalCandidate(mapping, match.getId()))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(
                                MappingReviewMatchListItemVo.ExternalCandidateVo::updatedAt,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(MappingReviewMatchListItemVo.ExternalCandidateVo::mappingId,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        return new MappingReviewMatchDetailVo(toMatchBrief(match), candidates);
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
    public MappingReviewDetailVo confirm(MappingReviewConfirmDto request, String operatorUsername) {
        Objects.requireNonNull(request, "request must not be null");
        if (request.mappingId() == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "mappingId must not be null");
        }
        String operator = requireOperator(operatorUsername);

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
        if (!allowedTargetMatchIds(current).contains(targetMatchId)) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "targetMatchId must be current match or persisted candidate");
        }
        MatchEntity targetMatch = matchMapper.selectById(targetMatchId);
        if (targetMatch == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "match not found: " + targetMatchId);
        }
        if (hasStarted(targetMatch)) {
            throw new BusinessException(ErrorCode.MAPPING_REVIEW_EXPIRED);
        }

        String oldSnapshot = snapshot(current);
        // 2) 条件更新：仅 PENDING 可确认
        UpdateWrapper<MatchSourceMapping> update = new UpdateWrapper<MatchSourceMapping>()
                .eq("id", current.getId())
                .eq("mapping_status", MappingStatusEnum.PENDING.getCode())
                .set("mapping_status", MappingStatusEnum.MANUAL_CONFIRMED.getCode())
                .set("match_id", targetMatchId)
                .set("confirmed_by", operator)
                .set("mapping_method", METHOD_MANUAL_REVIEW);
        int rows = matchSourceMappingMapper.update(null, update);
        if (rows == 0) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "mapping confirm conflict: not PENDING");
        }

        MatchSourceMapping updated = requireMapping(current.getId());
        // 3) 追加审计
        auditLogService.append(
                operator,
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
    public MappingReviewDetailVo reject(MappingReviewRejectDto request, String operatorUsername) {
        Objects.requireNonNull(request, "request must not be null");
        if (request.mappingId() == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "mappingId must not be null");
        }
        String operator = requireOperator(operatorUsername);

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
                .set("confirmed_by", operator)
                .set("mapping_method", METHOD_MANUAL_REVIEW)
                .set("mapping_explanation", explanation);
        int rows = matchSourceMappingMapper.update(null, update);
        if (rows == 0) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "mapping reject conflict: not PENDING");
        }

        MatchSourceMapping updated = requireMapping(current.getId());
        auditLogService.append(
                operator,
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
    public MappingReviewDetailVo reopen(MappingReviewReopenDto request, String operatorUsername) {
        Objects.requireNonNull(request, "request must not be null");
        if (request.mappingId() == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "mappingId must not be null");
        }
        String operator = requireOperator(operatorUsername);

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
                operator,
                AuditTargetTypeEnum.MATCH_SOURCE_MAPPING,
                String.valueOf(current.getId()),
                AuditActionTypeEnum.REOPEN,
                "mappingStatus",
                oldSnapshot,
                snapshot(updated)
        );
        return toDetail(updated);
    }

    private String requireOperator(String operatorUsername) {
        if (!StringUtils.hasText(operatorUsername)) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
        }
        return operatorUsername.trim();
    }

    private static MappingReviewScopeEnum reviewScope(MappingReviewListQueryDto query) {
        return query == null || query.reviewScope() == null
                ? MappingReviewScopeEnum.ACTIVE
                : query.reviewScope();
    }

    private static boolean hasStarted(MatchEntity match) {
        return match != null
                && match.getKickoffTime() != null
                && !match.getKickoffTime().isAfter(Instant.now());
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

    private MappingReviewMatchListItemVo.ExternalCandidateVo toExternalCandidate(
            MatchSourceMapping mapping,
            Long targetMatchId
    ) {
        if (mapping == null || targetMatchId == null) {
            return null;
        }
        MatchMapCandidateDto candidate = toCandidateDtos(mapping.getMappingCandidates()).stream()
                .filter(item -> Objects.equals(item.matchId(), targetMatchId))
                .findFirst()
                .orElse(null);
        if (!Objects.equals(mapping.getMatchId(), targetMatchId) && candidate == null) {
            return null;
        }
        return new MappingReviewMatchListItemVo.ExternalCandidateVo(
                mapping.getId(),
                mapping.getProviderCode(),
                mapping.getExternalMatchId(),
                mapping.getExternalLeagueId(),
                mapping.getExternalHomeTeamName(),
                mapping.getExternalAwayTeamName(),
                mapping.getExternalKickoffTime(),
                mapping.getMappingStatus(),
                candidate == null ? mapping.getMappingConfidence() : candidate.score(),
                candidate == null ? List.of("当前关联比赛") : candidate.reasons(),
                mapping.getMappingExplanation(),
                mapping.getUpdatedAt()
        );
    }

    private MappingReviewDetailVo toDetail(MatchSourceMapping mapping) {
        List<MatchMapCandidateDto> candidateDtos = toCandidateDtos(mapping.getMappingCandidates());
        Map<Long, MatchEntity> matchesById = loadCandidateMatches(mapping.getMatchId(), candidateDtos);
        MappingReviewDetailVo.MatchBriefVo brief = toMatchBrief(matchesById.get(mapping.getMatchId()));
        List<MappingReviewDetailVo.CandidateVo> candidates = candidateDtos.stream()
                .map(candidate -> new MappingReviewDetailVo.CandidateVo(
                        candidate.matchId(), candidate.score(), candidate.reasons(),
                        toMatchBrief(matchesById.get(candidate.matchId()))
                ))
                .toList();
        return new MappingReviewDetailVo(
                mapping.getId(),
                mapping.getMatchId(),
                mapping.getProviderCode(),
                mapping.getExternalMatchId(),
                mapping.getExternalLeagueId(),
                mapping.getExternalHomeTeamId(),
                mapping.getExternalAwayTeamId(),
                mapping.getExternalHomeTeamName(),
                mapping.getExternalAwayTeamName(),
                mapping.getExternalKickoffTime(),
                mapping.getMappingStatus(),
                mapping.getMappingConfidence(),
                mapping.getMappingMethod(),
                mapping.getMappingExplanation(),
                candidates,
                mapping.getConfirmedBy(),
                brief,
                mapping.getUpdatedAt()
        );
    }

    private Map<Long, MatchEntity> loadCandidateMatches(
            Long currentMatchId,
            List<MatchMapCandidateDto> candidates
    ) {
        Set<Long> matchIds = new HashSet<>();
        if (currentMatchId != null) {
            matchIds.add(currentMatchId);
        }
        candidates.stream().map(MatchMapCandidateDto::matchId).filter(Objects::nonNull).forEach(matchIds::add);
        if (matchIds.isEmpty()) {
            return Map.of();
        }
        List<MatchEntity> matches = matchMapper.selectBatchIds(matchIds);
        if (matches == null || matches.isEmpty()) {
            return Map.of();
        }
        Map<Long, MatchEntity> result = new HashMap<>();
        for (MatchEntity match : matches) {
            if (match.getId() != null) {
                result.put(match.getId(), match);
            }
        }
        return result;
    }

    private static MappingReviewDetailVo.MatchBriefVo toMatchBrief(MatchEntity match) {
        if (match == null) {
            return null;
        }
        return new MappingReviewDetailVo.MatchBriefVo(
                match.getId(), match.getLotteryMatchNo(), match.getLotteryDate(), match.getLeagueName(),
                match.getHomeTeamName(), match.getAwayTeamName(), match.getKickoffTime()
        );
    }

    private static Set<Long> allowedTargetMatchIds(MatchSourceMapping mapping) {
        Set<Long> allowed = new HashSet<>();
        if (mapping.getMatchId() != null) {
            allowed.add(mapping.getMatchId());
        }
        toCandidateDtos(mapping.getMappingCandidates()).stream()
                .map(MatchMapCandidateDto::matchId)
                .filter(Objects::nonNull)
                .forEach(allowed::add);
        return allowed;
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
