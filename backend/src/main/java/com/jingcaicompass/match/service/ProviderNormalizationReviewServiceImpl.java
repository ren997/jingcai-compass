package com.jingcaicompass.match.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jingcaicompass.audit.entity.AuditLog;
import com.jingcaicompass.audit.enums.AuditActionTypeEnum;
import com.jingcaicompass.audit.enums.AuditTargetTypeEnum;
import com.jingcaicompass.audit.mapper.AuditLogMapper;
import com.jingcaicompass.audit.service.AuditLogService;
import com.jingcaicompass.match.dto.ProviderNormalizationCandidateQueryDto;
import com.jingcaicompass.match.dto.ProviderNormalizationReviewConfirmDto;
import com.jingcaicompass.match.dto.ProviderNormalizationReviewDetailQueryDto;
import com.jingcaicompass.match.dto.ProviderNormalizationReviewListQueryDto;
import com.jingcaicompass.match.dto.ProviderNormalizationReviewRejectDto;
import com.jingcaicompass.match.dto.ProviderNormalizationReviewReopenDto;
import com.jingcaicompass.match.entity.League;
import com.jingcaicompass.match.entity.ProviderLeagueMapping;
import com.jingcaicompass.match.entity.ProviderTeamMapping;
import com.jingcaicompass.match.entity.Team;
import com.jingcaicompass.match.enums.MappingStatusEnum;
import com.jingcaicompass.match.enums.ProviderNormalizationEntityTypeEnum;
import com.jingcaicompass.match.mapper.LeagueMapper;
import com.jingcaicompass.match.mapper.ProviderLeagueMappingMapper;
import com.jingcaicompass.match.mapper.ProviderTeamMappingMapper;
import com.jingcaicompass.match.mapper.TeamMapper;
import com.jingcaicompass.match.vo.ProviderNormalizationAuditVo;
import com.jingcaicompass.match.vo.ProviderNormalizationEntityVo;
import com.jingcaicompass.match.vo.ProviderNormalizationReviewDetailVo;
import com.jingcaicompass.match.vo.ProviderNormalizationReviewListItemVo;
import com.jingcaicompass.system.api.PageResult;
import com.jingcaicompass.system.config.properties.PaginationProperties;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 供应商联赛、球队映射人工复核：条件状态机、受控候选和只追加审计。 */
@Service
@ConditionalOnBean(DataSource.class)
public class ProviderNormalizationReviewServiceImpl implements ProviderNormalizationReviewService {

    static final String METHOD_MANUAL_NORMALIZATION_REVIEW = "MANUAL_NORMALIZATION_REVIEW";
    static final String METHOD_MANUAL_NORMALIZATION_REJECTED = "MANUAL_NORMALIZATION_REJECTED";
    private static final String SPORTTERY_PROVIDER_CODE = "CHINA_SPORTTERY";

    private final ProviderLeagueMappingMapper providerLeagueMappingMapper;
    private final ProviderTeamMappingMapper providerTeamMappingMapper;
    private final LeagueMapper leagueMapper;
    private final TeamMapper teamMapper;
    private final AuditLogMapper auditLogMapper;
    private final AuditLogService auditLogService;
    private final PaginationProperties paginationProperties;

    public ProviderNormalizationReviewServiceImpl(
            ProviderLeagueMappingMapper providerLeagueMappingMapper,
            ProviderTeamMappingMapper providerTeamMappingMapper,
            LeagueMapper leagueMapper,
            TeamMapper teamMapper,
            AuditLogMapper auditLogMapper,
            AuditLogService auditLogService,
            PaginationProperties paginationProperties
    ) {
        this.providerLeagueMappingMapper = providerLeagueMappingMapper;
        this.providerTeamMappingMapper = providerTeamMappingMapper;
        this.leagueMapper = leagueMapper;
        this.teamMapper = teamMapper;
        this.auditLogMapper = auditLogMapper;
        this.auditLogService = auditLogService;
        this.paginationProperties = paginationProperties;
    }

    @Override
    public PageResult<ProviderNormalizationReviewListItemVo> list(ProviderNormalizationReviewListQueryDto query) {
        ProviderNormalizationEntityTypeEnum type = requireType(query == null ? null : query.entityType());
        int pageNo = query == null || query.pageNo() == null || query.pageNo() < 1 ? 1 : query.pageNo();
        long requestedSize = query == null || query.pageSize() == null || query.pageSize() < 1 ? 20 : query.pageSize();
        long pageSize = Math.min(requestedSize, paginationProperties.maxPageSize());
        MappingStatusEnum status = query == null || query.mappingStatus() == null
                ? MappingStatusEnum.PENDING : query.mappingStatus();
        String providerCode = query == null ? null : trimToNull(query.providerCode());

        // 1) 两张映射表分开分页，避免联赛和球队身份混淆。
        if (type == ProviderNormalizationEntityTypeEnum.LEAGUE) {
            if (isSportteryBaseProvider(providerCode)) {
                return new PageResult<>(List.of(), pageNo, pageSize, 0);
            }
            LambdaQueryWrapper<ProviderLeagueMapping> wrapper = new LambdaQueryWrapper<ProviderLeagueMapping>()
                    .eq(ProviderLeagueMapping::getMappingStatus, status)
                    .ne(ProviderLeagueMapping::getProviderCode, SPORTTERY_PROVIDER_CODE)
                    .orderByDesc(ProviderLeagueMapping::getUpdatedAt)
                    .orderByDesc(ProviderLeagueMapping::getId);
            if (providerCode != null) {
                wrapper.eq(ProviderLeagueMapping::getProviderCode, providerCode);
            }
            Page<ProviderLeagueMapping> page = providerLeagueMappingMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
            Map<Long, ProviderNormalizationEntityVo> entities = leagueEntities(page.getRecords());
            return new PageResult<>(page.getRecords().stream().map(mapping -> toListItem(mapping, entities.get(mapping.getLeagueId())))
                    .toList(), page.getCurrent(), page.getSize(), page.getTotal());
        }

        if (isSportteryBaseProvider(providerCode)) {
            return new PageResult<>(List.of(), pageNo, pageSize, 0);
        }
        LambdaQueryWrapper<ProviderTeamMapping> wrapper = new LambdaQueryWrapper<ProviderTeamMapping>()
                .eq(ProviderTeamMapping::getMappingStatus, status)
                .ne(ProviderTeamMapping::getProviderCode, SPORTTERY_PROVIDER_CODE)
                .orderByDesc(ProviderTeamMapping::getUpdatedAt)
                .orderByDesc(ProviderTeamMapping::getId);
        if (providerCode != null) {
            wrapper.eq(ProviderTeamMapping::getProviderCode, providerCode);
        }
        Page<ProviderTeamMapping> page = providerTeamMappingMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
        Map<Long, ProviderNormalizationEntityVo> entities = teamEntities(page.getRecords());
        return new PageResult<>(page.getRecords().stream().map(mapping -> toListItem(mapping, entities.get(mapping.getTeamId())))
                .toList(), page.getCurrent(), page.getSize(), page.getTotal());
    }

    @Override
    public ProviderNormalizationReviewDetailVo detail(ProviderNormalizationReviewDetailQueryDto query) {
        ProviderNormalizationEntityTypeEnum type = requireType(query == null ? null : query.entityType());
        Long mappingId = requireId(query == null ? null : query.mappingId(), "mappingId");
        if (type == ProviderNormalizationEntityTypeEnum.LEAGUE) {
            ProviderLeagueMapping mapping = requireLeagueMapping(mappingId);
            requireReviewableProvider(mapping.getProviderCode());
            return toDetail(mapping);
        }
        ProviderTeamMapping mapping = requireTeamMapping(mappingId);
        requireReviewableProvider(mapping.getProviderCode());
        return toDetail(mapping);
    }

    @Override
    public List<ProviderNormalizationEntityVo> candidates(ProviderNormalizationCandidateQueryDto query) {
        ProviderNormalizationEntityTypeEnum type = requireType(query == null ? null : query.entityType());
        requireId(query == null ? null : query.mappingId(), "mappingId");
        String keyword = trimToNull(query == null ? null : query.keyword());

        // 1) 只搜索内部标准字典；客户端不能提交裸 ID 以外的外部赛事或载荷数据。
        if (type == ProviderNormalizationEntityTypeEnum.LEAGUE) {
            requireReviewableProvider(requireLeagueMapping(mappingId).getProviderCode());
            LambdaQueryWrapper<League> wrapper = new LambdaQueryWrapper<League>().orderByAsc(League::getId).last("LIMIT 20");
            if (keyword != null) {
                wrapper.and(item -> item.like(League::getNameZh, keyword).or().like(League::getNameEn, keyword));
            }
            return leagueMapper.selectList(wrapper).stream().map(this::toEntity).toList();
        }
        requireReviewableProvider(requireTeamMapping(mappingId).getProviderCode());
        LambdaQueryWrapper<Team> wrapper = new LambdaQueryWrapper<Team>().orderByAsc(Team::getId).last("LIMIT 20");
        if (keyword != null) {
            wrapper.and(item -> item.like(Team::getNameZh, keyword).or().like(Team::getNameEn, keyword));
        }
        return teamMapper.selectList(wrapper).stream().map(this::toEntity).toList();
    }

    @Override
    @Transactional
    public ProviderNormalizationReviewDetailVo confirm(ProviderNormalizationReviewConfirmDto request, String operatorUsername) {
        ProviderNormalizationEntityTypeEnum type = requireType(request == null ? null : request.entityType());
        Long mappingId = requireId(request == null ? null : request.mappingId(), "mappingId");
        Long targetEntityId = requireId(request == null ? null : request.targetEntityId(), "targetEntityId");
        String operator = requireOperator(operatorUsername);

        // 1) 读取 PENDING 行，目标必须为已存在且与当前暂存候选不同的内部实体。
        if (type == ProviderNormalizationEntityTypeEnum.LEAGUE) {
            ProviderLeagueMapping current = requireLeagueMapping(mappingId);
            requireReviewableProvider(current.getProviderCode());
            requirePending(current.getMappingStatus());
            requireLeagueTarget(targetEntityId, current.getLeagueId());
            String before = snapshot(current);
            int rows = providerLeagueMappingMapper.update(null, new UpdateWrapper<ProviderLeagueMapping>()
                    .eq("id", mappingId).eq("mapping_status", MappingStatusEnum.PENDING.getCode())
                    .set("league_id", targetEntityId)
                    .set("mapping_status", MappingStatusEnum.MANUAL_CONFIRMED.getCode())
                    .set("mapping_confidence", BigDecimal.ONE)
                    .set("mapping_method", METHOD_MANUAL_NORMALIZATION_REVIEW));
            requireUpdated(rows, "confirm");
            ProviderLeagueMapping updated = requireLeagueMapping(mappingId);
            appendAudit(operator, AuditTargetTypeEnum.PROVIDER_LEAGUE_MAPPING, mappingId, AuditActionTypeEnum.CONFIRM, before, snapshot(updated));
            return toDetail(updated);
        }

        ProviderTeamMapping current = requireTeamMapping(mappingId);
        requireReviewableProvider(current.getProviderCode());
        requirePending(current.getMappingStatus());
        requireTeamTarget(targetEntityId, current.getTeamId());
        String before = snapshot(current);
        int rows = providerTeamMappingMapper.update(null, new UpdateWrapper<ProviderTeamMapping>()
                .eq("id", mappingId).eq("mapping_status", MappingStatusEnum.PENDING.getCode())
                .set("team_id", targetEntityId)
                .set("mapping_status", MappingStatusEnum.MANUAL_CONFIRMED.getCode())
                .set("mapping_confidence", BigDecimal.ONE)
                .set("mapping_method", METHOD_MANUAL_NORMALIZATION_REVIEW));
        requireUpdated(rows, "confirm");
        ProviderTeamMapping updated = requireTeamMapping(mappingId);
        appendAudit(operator, AuditTargetTypeEnum.PROVIDER_TEAM_MAPPING, mappingId, AuditActionTypeEnum.CONFIRM, before, snapshot(updated));
        return toDetail(updated);
    }

    @Override
    @Transactional
    public ProviderNormalizationReviewDetailVo reject(ProviderNormalizationReviewRejectDto request, String operatorUsername) {
        ProviderNormalizationEntityTypeEnum type = requireType(request == null ? null : request.entityType());
        Long mappingId = requireId(request == null ? null : request.mappingId(), "mappingId");
        String operator = requireOperator(operatorUsername);
        String reason = trimToNull(request == null ? null : request.reason());
        if (type == ProviderNormalizationEntityTypeEnum.LEAGUE) {
            ProviderLeagueMapping current = requireLeagueMapping(mappingId);
            requireReviewableProvider(current.getProviderCode());
            requirePending(current.getMappingStatus());
            String before = snapshot(current);
            int rows = providerLeagueMappingMapper.update(null, new UpdateWrapper<ProviderLeagueMapping>()
                    .eq("id", mappingId).eq("mapping_status", MappingStatusEnum.PENDING.getCode())
                    .set("mapping_status", MappingStatusEnum.REJECTED.getCode())
                    .set("mapping_method", METHOD_MANUAL_NORMALIZATION_REJECTED));
            requireUpdated(rows, "reject");
            ProviderLeagueMapping updated = requireLeagueMapping(mappingId);
            appendAudit(operator, AuditTargetTypeEnum.PROVIDER_LEAGUE_MAPPING, mappingId, AuditActionTypeEnum.REJECT,
                    before, snapshot(updated) + (reason == null ? "" : ";reason=" + reason));
            return toDetail(updated);
        }
        ProviderTeamMapping current = requireTeamMapping(mappingId);
        requireReviewableProvider(current.getProviderCode());
        requirePending(current.getMappingStatus());
        String before = snapshot(current);
        int rows = providerTeamMappingMapper.update(null, new UpdateWrapper<ProviderTeamMapping>()
                .eq("id", mappingId).eq("mapping_status", MappingStatusEnum.PENDING.getCode())
                .set("mapping_status", MappingStatusEnum.REJECTED.getCode())
                .set("mapping_method", METHOD_MANUAL_NORMALIZATION_REJECTED));
        requireUpdated(rows, "reject");
        ProviderTeamMapping updated = requireTeamMapping(mappingId);
        appendAudit(operator, AuditTargetTypeEnum.PROVIDER_TEAM_MAPPING, mappingId, AuditActionTypeEnum.REJECT,
                before, snapshot(updated) + (reason == null ? "" : ";reason=" + reason));
        return toDetail(updated);
    }

    @Override
    @Transactional
    public ProviderNormalizationReviewDetailVo reopen(ProviderNormalizationReviewReopenDto request, String operatorUsername) {
        ProviderNormalizationEntityTypeEnum type = requireType(request == null ? null : request.entityType());
        Long mappingId = requireId(request == null ? null : request.mappingId(), "mappingId");
        String operator = requireOperator(operatorUsername);
        if (type == ProviderNormalizationEntityTypeEnum.LEAGUE) {
            ProviderLeagueMapping current = requireLeagueMapping(mappingId);
            requireReviewableProvider(current.getProviderCode());
            requireRejected(current.getMappingStatus());
            String before = snapshot(current);
            int rows = providerLeagueMappingMapper.update(null, new UpdateWrapper<ProviderLeagueMapping>()
                    .eq("id", mappingId).eq("mapping_status", MappingStatusEnum.REJECTED.getCode())
                    .set("mapping_status", MappingStatusEnum.PENDING.getCode())
                    .set("mapping_method", METHOD_MANUAL_NORMALIZATION_REVIEW));
            requireUpdated(rows, "reopen");
            ProviderLeagueMapping updated = requireLeagueMapping(mappingId);
            appendAudit(operator, AuditTargetTypeEnum.PROVIDER_LEAGUE_MAPPING, mappingId, AuditActionTypeEnum.REOPEN, before, snapshot(updated));
            return toDetail(updated);
        }
        ProviderTeamMapping current = requireTeamMapping(mappingId);
        requireReviewableProvider(current.getProviderCode());
        requireRejected(current.getMappingStatus());
        String before = snapshot(current);
        int rows = providerTeamMappingMapper.update(null, new UpdateWrapper<ProviderTeamMapping>()
                .eq("id", mappingId).eq("mapping_status", MappingStatusEnum.REJECTED.getCode())
                .set("mapping_status", MappingStatusEnum.PENDING.getCode())
                .set("mapping_method", METHOD_MANUAL_NORMALIZATION_REVIEW));
        requireUpdated(rows, "reopen");
        ProviderTeamMapping updated = requireTeamMapping(mappingId);
        appendAudit(operator, AuditTargetTypeEnum.PROVIDER_TEAM_MAPPING, mappingId, AuditActionTypeEnum.REOPEN, before, snapshot(updated));
        return toDetail(updated);
    }

    private ProviderNormalizationReviewListItemVo toListItem(ProviderLeagueMapping mapping, ProviderNormalizationEntityVo entity) {
        return new ProviderNormalizationReviewListItemVo(mapping.getId(), ProviderNormalizationEntityTypeEnum.LEAGUE,
                mapping.getProviderCode(), mapping.getExternalLeagueId(), mapping.getExternalScope(), mapping.getExternalDisplayName(),
                mapping.getExternalNormalizedKey(), mapping.getMappingStatus(), mapping.getMappingConfidence(), mapping.getMappingMethod(), entity, mapping.getUpdatedAt());
    }

    private ProviderNormalizationReviewListItemVo toListItem(ProviderTeamMapping mapping, ProviderNormalizationEntityVo entity) {
        return new ProviderNormalizationReviewListItemVo(mapping.getId(), ProviderNormalizationEntityTypeEnum.TEAM,
                mapping.getProviderCode(), mapping.getExternalTeamId(), mapping.getExternalScope(), mapping.getExternalDisplayName(),
                mapping.getExternalNormalizedKey(), mapping.getMappingStatus(), mapping.getMappingConfidence(), mapping.getMappingMethod(), entity, mapping.getUpdatedAt());
    }

    private ProviderNormalizationReviewDetailVo toDetail(ProviderLeagueMapping mapping) {
        return new ProviderNormalizationReviewDetailVo(mapping.getId(), ProviderNormalizationEntityTypeEnum.LEAGUE,
                mapping.getProviderCode(), mapping.getExternalLeagueId(), mapping.getExternalScope(), mapping.getExternalDisplayName(),
                mapping.getExternalNormalizedKey(), mapping.getMappingStatus(), mapping.getMappingConfidence(), mapping.getMappingMethod(),
                toEntity(leagueMapper.selectById(mapping.getLeagueId())), auditHistory(AuditTargetTypeEnum.PROVIDER_LEAGUE_MAPPING, mapping.getId()), mapping.getUpdatedAt());
    }

    private ProviderNormalizationReviewDetailVo toDetail(ProviderTeamMapping mapping) {
        return new ProviderNormalizationReviewDetailVo(mapping.getId(), ProviderNormalizationEntityTypeEnum.TEAM,
                mapping.getProviderCode(), mapping.getExternalTeamId(), mapping.getExternalScope(), mapping.getExternalDisplayName(),
                mapping.getExternalNormalizedKey(), mapping.getMappingStatus(), mapping.getMappingConfidence(), mapping.getMappingMethod(),
                toEntity(teamMapper.selectById(mapping.getTeamId())), auditHistory(AuditTargetTypeEnum.PROVIDER_TEAM_MAPPING, mapping.getId()), mapping.getUpdatedAt());
    }

    private List<ProviderNormalizationAuditVo> auditHistory(AuditTargetTypeEnum targetType, Long mappingId) {
        return auditLogMapper.selectList(new LambdaQueryWrapper<AuditLog>()
                        .eq(AuditLog::getTargetType, targetType)
                        .eq(AuditLog::getTargetId, String.valueOf(mappingId))
                        .orderByDesc(AuditLog::getCreatedAt))
                .stream().map(log -> new ProviderNormalizationAuditVo(log.getOperatorId(), log.getActionType().getCode(), log.getFieldName(), log.getCreatedAt())).toList();
    }

    private Map<Long, ProviderNormalizationEntityVo> leagueEntities(Collection<ProviderLeagueMapping> mappings) {
        Map<Long, ProviderNormalizationEntityVo> result = new HashMap<>();
        if (mappings == null) return result;
        List<Long> ids = mappings.stream().map(ProviderLeagueMapping::getLeagueId).filter(java.util.Objects::nonNull).distinct().toList();
        for (League league : ids.isEmpty() ? List.<League>of() : leagueMapper.selectBatchIds(ids)) result.put(league.getId(), toEntity(league));
        return result;
    }

    private Map<Long, ProviderNormalizationEntityVo> teamEntities(Collection<ProviderTeamMapping> mappings) {
        Map<Long, ProviderNormalizationEntityVo> result = new HashMap<>();
        if (mappings == null) return result;
        List<Long> ids = mappings.stream().map(ProviderTeamMapping::getTeamId).filter(java.util.Objects::nonNull).distinct().toList();
        for (Team team : ids.isEmpty() ? List.<Team>of() : teamMapper.selectBatchIds(ids)) result.put(team.getId(), toEntity(team));
        return result;
    }

    private ProviderNormalizationEntityVo toEntity(League league) {
        return league == null ? null : new ProviderNormalizationEntityVo(league.getId(), league.getNameZh(), league.getNameEn());
    }

    private ProviderNormalizationEntityVo toEntity(Team team) {
        return team == null ? null : new ProviderNormalizationEntityVo(team.getId(), team.getNameZh(), team.getNameEn());
    }

    private ProviderLeagueMapping requireLeagueMapping(Long mappingId) {
        ProviderLeagueMapping mapping = providerLeagueMappingMapper.selectById(mappingId);
        if (mapping == null) throw new BusinessException(ErrorCode.NORMALIZATION_MAPPING_NOT_FOUND);
        return mapping;
    }

    private ProviderTeamMapping requireTeamMapping(Long mappingId) {
        ProviderTeamMapping mapping = providerTeamMappingMapper.selectById(mappingId);
        if (mapping == null) throw new BusinessException(ErrorCode.NORMALIZATION_MAPPING_NOT_FOUND);
        return mapping;
    }

    private void requireLeagueTarget(Long targetEntityId, Long provisionalEntityId) {
        if (targetEntityId.equals(provisionalEntityId) || leagueMapper.selectById(targetEntityId) == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "targetLeagueId must be a separate existing internal entity");
        }
    }

    private void requireTeamTarget(Long targetEntityId, Long provisionalEntityId) {
        if (targetEntityId.equals(provisionalEntityId) || teamMapper.selectById(targetEntityId) == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "targetTeamId must be a separate existing internal entity");
        }
    }

    private static ProviderNormalizationEntityTypeEnum requireType(ProviderNormalizationEntityTypeEnum type) {
        if (type == null) throw new BusinessException(ErrorCode.INVALID_PARAMETER, "entityType must be LEAGUE or TEAM");
        return type;
    }

    private static boolean isSportteryBaseProvider(String providerCode) {
        return SPORTTERY_PROVIDER_CODE.equals(providerCode);
    }

    private static void requireReviewableProvider(String providerCode) {
        if (isSportteryBaseProvider(providerCode)) {
            throw new BusinessException(ErrorCode.NORMALIZATION_PROVIDER_NOT_REVIEWABLE);
        }
    }

    private static Long requireId(Long value, String name) {
        if (value == null || value < 1) throw new BusinessException(ErrorCode.INVALID_PARAMETER, name + " must be a positive integer");
        return value;
    }

    private static String requireOperator(String operator) {
        if (!StringUtils.hasText(operator)) throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
        return operator.trim();
    }

    private static void requirePending(MappingStatusEnum status) {
        if (status != MappingStatusEnum.PENDING) throw new BusinessException(ErrorCode.BUSINESS_ERROR, "normalization mapping conflict: expected PENDING");
    }

    private static void requireRejected(MappingStatusEnum status) {
        if (status != MappingStatusEnum.REJECTED) throw new BusinessException(ErrorCode.BUSINESS_ERROR, "normalization mapping conflict: expected REJECTED");
    }

    private static void requireUpdated(int rows, String action) {
        if (rows == 0) throw new BusinessException(ErrorCode.BUSINESS_ERROR, "normalization mapping " + action + " conflict");
    }

    private void appendAudit(String operator, AuditTargetTypeEnum targetType, Long mappingId, AuditActionTypeEnum action, String oldValue, String newValue) {
        auditLogService.append(operator, targetType, String.valueOf(mappingId), action, "mappingStatus", oldValue, newValue);
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String snapshot(ProviderLeagueMapping mapping) {
        return "provider=" + mapping.getProviderCode() + ";externalId=" + mapping.getExternalLeagueId()
                + ";scope=" + mapping.getExternalScope() + ";status=" + mapping.getMappingStatus()
                + ";leagueId=" + mapping.getLeagueId();
    }

    private static String snapshot(ProviderTeamMapping mapping) {
        return "provider=" + mapping.getProviderCode() + ";externalId=" + mapping.getExternalTeamId()
                + ";scope=" + mapping.getExternalScope() + ";status=" + mapping.getMappingStatus()
                + ";teamId=" + mapping.getTeamId();
    }
}
