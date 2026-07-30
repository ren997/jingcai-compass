package com.jingcaicompass.match.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jingcaicompass.match.dto.EntityNormalizeRequestDto;
import com.jingcaicompass.match.dto.EntityNormalizeResultDto;
import com.jingcaicompass.match.entity.ProviderTeamMapping;
import com.jingcaicompass.match.entity.Team;
import com.jingcaicompass.match.entity.TeamAlias;
import com.jingcaicompass.match.enums.EntityNormalizeOutcomeEnum;
import com.jingcaicompass.match.enums.MappingStatusEnum;
import com.jingcaicompass.match.mapper.ProviderTeamMappingMapper;
import com.jingcaicompass.match.mapper.TeamAliasMapper;
import com.jingcaicompass.match.mapper.TeamMapper;
import com.jingcaicompass.match.support.NameNormalizationSupport;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 球队标准化实现：外部 ID → 别名 → 唯一精确名 → 新建候选。 */
@Service
@ConditionalOnBean(DataSource.class)
public class TeamNormalizationServiceImpl implements TeamNormalizationService {

    static final String METHOD_EXTERNAL_ID = "EXTERNAL_ID";
    static final String METHOD_ALIAS = "ALIAS";
    static final String METHOD_EXACT_NAME = "EXACT_NAME";
    static final String METHOD_NAME_CANDIDATE = "NAME_CANDIDATE";
    static final String METHOD_NAME_CANDIDATE_REUSE = "NAME_CANDIDATE_REUSE";
    static final String METHOD_REJECTED_REUSE = "REJECTED_REUSE";

    private final TeamMapper teamMapper;
    private final TeamAliasMapper teamAliasMapper;
    private final ProviderTeamMappingMapper providerTeamMappingMapper;

    public TeamNormalizationServiceImpl(
            TeamMapper teamMapper,
            TeamAliasMapper teamAliasMapper,
            ProviderTeamMappingMapper providerTeamMappingMapper
    ) {
        this.teamMapper = teamMapper;
        this.teamAliasMapper = teamAliasMapper;
        this.providerTeamMappingMapper = providerTeamMappingMapper;
    }

    @Override
    public EntityNormalizeResultDto resolve(EntityNormalizeRequestDto request) {
        Objects.requireNonNull(request, "request must not be null");
        if (!StringUtils.hasText(request.displayName())) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "displayName must not be blank");
        }

        String displayName = request.displayName().trim();
        String providerCode = blankToNull(request.providerCode());
        String externalId = blankToNull(request.externalId());
        String externalScope = blankToNull(request.externalScope());
        String normalizedKey = NameNormalizationSupport.normalizedKey(displayName);
        if (!StringUtils.hasText(normalizedKey)) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "displayName normalizes to empty key");
        }

        // 1) 外部 ID 映射优先；PENDING/REJECTED 也必须稳定复用，避免重跑静默确认
        ProviderTeamMapping externalMapping = null;
        if (providerCode != null && externalId != null) {
            externalMapping = findExternalMapping(providerCode, externalId);
            if (externalMapping != null && isConfirmed(externalMapping.getMappingStatus())) {
                return new EntityNormalizeResultDto(
                        externalMapping.getTeamId(),
                        EntityNormalizeOutcomeEnum.RESOLVED,
                        externalMapping.getMappingStatus(),
                        METHOD_EXTERNAL_ID
                );
            }
            if (externalMapping != null
                    && externalMapping.getMappingStatus() == MappingStatusEnum.REJECTED) {
                return unresolvedExternalMapping(externalMapping, METHOD_REJECTED_REUSE);
            }
        }

        // 2) The Odds 的新外部身份必须进入人工队列，不能由全局别名或名称命中自动确认。
        if (requiresManualProviderReview(providerCode) && externalMapping == null) {
            return createPendingCandidate(providerCode, externalId, displayName, normalizedKey, externalScope);
        }

        // 3) 已确认别名
        TeamAlias alias = teamAliasMapper.selectOne(new LambdaQueryWrapper<TeamAlias>()
                .eq(TeamAlias::getAliasNormalized, normalizedKey)
                .last("LIMIT 1"));
        if (alias != null) {
            confirmExternalMapping(
                    externalMapping,
                    providerCode,
                    externalId,
                    alias.getTeamId(),
                    MappingStatusEnum.MANUAL_CONFIRMED,
                    METHOD_ALIAS,
                    displayName,
                    normalizedKey,
                    externalScope
            );
            return new EntityNormalizeResultDto(
                    alias.getTeamId(),
                    EntityNormalizeOutcomeEnum.RESOLVED,
                    MappingStatusEnum.MANUAL_CONFIRMED,
                    METHOD_ALIAS
            );
        }

        // 4) 旧候选在人工确认前保持 PENDING，不被候选自身名称反向“精确命中”
        if (externalMapping != null) {
            return unresolvedExternalMapping(externalMapping, METHOD_NAME_CANDIDATE_REUSE);
        }

        // 5) 标准名规范化后唯一精确命中
        List<Team> exactHits = findExactNameHits(normalizedKey);
        if (exactHits.size() == 1) {
            confirmExternalMapping(
                    null,
                    providerCode,
                    externalId,
                    exactHits.get(0).getId(),
                    MappingStatusEnum.AUTO_CONFIRMED,
                    METHOD_EXACT_NAME,
                    displayName,
                    normalizedKey,
                    externalScope
            );
            return new EntityNormalizeResultDto(
                    exactHits.get(0).getId(),
                    EntityNormalizeOutcomeEnum.RESOLVED,
                    providerCode == null || externalId == null ? null : MappingStatusEnum.AUTO_CONFIRMED,
                    METHOD_EXACT_NAME
            );
        }

        // 6) 新建候选；有 externalId 时写 PENDING 映射
        Team created = new Team();
        created.setNameZh(displayName);
        created.setNameEn(looksPrimarilyLatin(displayName) ? displayName : null);
        teamMapper.insert(created);

        MappingStatusEnum mappingStatus = null;
        if (providerCode != null && externalId != null) {
            ProviderTeamMapping pending = new ProviderTeamMapping();
            pending.setTeamId(created.getId());
            pending.setProviderCode(providerCode);
            pending.setExternalTeamId(externalId);
            pending.setExternalDisplayName(displayName);
            pending.setExternalNormalizedKey(normalizedKey);
            pending.setExternalScope(externalScope);
            pending.setMappingStatus(MappingStatusEnum.PENDING);
            pending.setMappingMethod(METHOD_NAME_CANDIDATE);
            providerTeamMappingMapper.insert(pending);
            mappingStatus = MappingStatusEnum.PENDING;
        }

        return new EntityNormalizeResultDto(
                created.getId(),
                EntityNormalizeOutcomeEnum.CANDIDATE_CREATED,
                mappingStatus,
                METHOD_NAME_CANDIDATE
        );
    }

    @Override
    public TeamAlias confirmAlias(Long teamId, String aliasRaw, String source, String confirmedBy) {
        // 1) 校验球队存在
        if (teamId == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "teamId must not be null");
        }
        if (!StringUtils.hasText(aliasRaw)) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "aliasRaw must not be blank");
        }
        Team team = teamMapper.selectById(teamId);
        if (team == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "team not found: " + teamId);
        }

        // 2) 规范化 key；UNIQUE 冲突则拒绝
        String normalized = NameNormalizationSupport.normalizedKey(aliasRaw);
        if (!StringUtils.hasText(normalized)) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "aliasRaw normalizes to empty key");
        }

        TeamAlias existing = teamAliasMapper.selectOne(new LambdaQueryWrapper<TeamAlias>()
                .eq(TeamAlias::getAliasNormalized, normalized)
                .last("LIMIT 1"));
        if (existing != null) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "team alias already confirmed: " + normalized);
        }

        // 3) 写入已确认别名（不改动其它 PENDING 映射）
        TeamAlias alias = new TeamAlias();
        alias.setTeamId(teamId);
        alias.setAliasRaw(aliasRaw.trim());
        alias.setAliasNormalized(normalized);
        alias.setSource(source);
        alias.setConfirmedBy(confirmedBy);
        alias.setConfirmedAt(Instant.now());
        teamAliasMapper.insert(alias);
        return alias;
    }

    private ProviderTeamMapping findExternalMapping(String providerCode, String externalId) {
        return providerTeamMappingMapper.selectOne(new LambdaQueryWrapper<ProviderTeamMapping>()
                .eq(ProviderTeamMapping::getProviderCode, providerCode)
                .eq(ProviderTeamMapping::getExternalTeamId, externalId)
                .last("LIMIT 1"));
    }

    private EntityNormalizeResultDto unresolvedExternalMapping(
            ProviderTeamMapping mapping,
            String method
    ) {
        return new EntityNormalizeResultDto(
                mapping.getTeamId(),
                EntityNormalizeOutcomeEnum.CANDIDATE_CREATED,
                mapping.getMappingStatus(),
                method
        );
    }

    private void confirmExternalMapping(
            ProviderTeamMapping existing,
            String providerCode,
            String externalId,
            Long teamId,
            MappingStatusEnum status,
            String method,
            String displayName,
            String normalizedKey,
            String externalScope
    ) {
        if (providerCode == null || externalId == null) {
            return;
        }
        if (existing != null) {
            existing.setTeamId(teamId);
            existing.setMappingStatus(status);
            existing.setMappingMethod(method);
            applyReviewMetadata(existing, displayName, normalizedKey, externalScope);
            providerTeamMappingMapper.updateById(existing);
            return;
        }
        ProviderTeamMapping mapping = new ProviderTeamMapping();
        mapping.setTeamId(teamId);
        mapping.setProviderCode(providerCode);
        mapping.setExternalTeamId(externalId);
        mapping.setExternalDisplayName(displayName);
        mapping.setExternalNormalizedKey(normalizedKey);
        mapping.setExternalScope(externalScope);
        mapping.setMappingStatus(status);
        mapping.setMappingMethod(method);
        providerTeamMappingMapper.insert(mapping);
    }

    private EntityNormalizeResultDto createPendingCandidate(
            String providerCode,
            String externalId,
            String displayName,
            String normalizedKey,
            String externalScope
    ) {
        Team created = new Team();
        created.setNameZh(displayName);
        created.setNameEn(looksPrimarilyLatin(displayName) ? displayName : null);
        teamMapper.insert(created);

        ProviderTeamMapping pending = new ProviderTeamMapping();
        pending.setTeamId(created.getId());
        pending.setProviderCode(providerCode);
        pending.setExternalTeamId(externalId);
        pending.setExternalDisplayName(displayName);
        pending.setExternalNormalizedKey(normalizedKey);
        pending.setExternalScope(externalScope);
        pending.setMappingStatus(MappingStatusEnum.PENDING);
        pending.setMappingMethod(METHOD_NAME_CANDIDATE);
        providerTeamMappingMapper.insert(pending);
        return new EntityNormalizeResultDto(created.getId(), EntityNormalizeOutcomeEnum.CANDIDATE_CREATED,
                MappingStatusEnum.PENDING, METHOD_NAME_CANDIDATE);
    }

    private static boolean requiresManualProviderReview(String providerCode) {
        return "THE_ODDS_API".equals(providerCode);
    }

    private static void applyReviewMetadata(
            ProviderTeamMapping mapping,
            String displayName,
            String normalizedKey,
            String externalScope
    ) {
        if (!StringUtils.hasText(mapping.getExternalDisplayName())) {
            mapping.setExternalDisplayName(displayName);
        }
        if (!StringUtils.hasText(mapping.getExternalNormalizedKey())) {
            mapping.setExternalNormalizedKey(normalizedKey);
        }
        if (!StringUtils.hasText(mapping.getExternalScope()) && StringUtils.hasText(externalScope)) {
            mapping.setExternalScope(externalScope);
        }
    }

    private static boolean isConfirmed(MappingStatusEnum status) {
        return status == MappingStatusEnum.AUTO_CONFIRMED
                || status == MappingStatusEnum.MANUAL_CONFIRMED;
    }

    private List<Team> findExactNameHits(String normalizedKey) {
        List<Team> all = teamMapper.selectList(null);
        List<Team> hits = new ArrayList<>();
        for (Team team : all) {
            if (normalizedKey.equals(NameNormalizationSupport.normalizedKey(team.getNameZh()))
                    || normalizedKey.equals(NameNormalizationSupport.normalizedKey(team.getNameEn()))) {
                hits.add(team);
            }
        }
        return hits;
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static boolean looksPrimarilyLatin(String value) {
        int letters = 0;
        int latin = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isLetter(ch)) {
                letters++;
                if (ch <= 0x7F) {
                    latin++;
                }
            }
        }
        return letters > 0 && latin * 2 >= letters;
    }
}
