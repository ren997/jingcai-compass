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

        // 1) 已确认外部 ID 映射优先
        if (providerCode != null && externalId != null) {
            ProviderTeamMapping confirmed = findConfirmedExternalMapping(providerCode, externalId);
            if (confirmed != null) {
                return new EntityNormalizeResultDto(
                        confirmed.getTeamId(),
                        EntityNormalizeOutcomeEnum.RESOLVED,
                        confirmed.getMappingStatus(),
                        METHOD_EXTERNAL_ID
                );
            }
        }

        String normalizedKey = NameNormalizationSupport.normalizedKey(displayName);
        if (!StringUtils.hasText(normalizedKey)) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "displayName normalizes to empty key");
        }

        // 2) 已确认别名
        TeamAlias alias = teamAliasMapper.selectOne(new LambdaQueryWrapper<TeamAlias>()
                .eq(TeamAlias::getAliasNormalized, normalizedKey)
                .last("LIMIT 1"));
        if (alias != null) {
            return new EntityNormalizeResultDto(
                    alias.getTeamId(),
                    EntityNormalizeOutcomeEnum.RESOLVED,
                    MappingStatusEnum.MANUAL_CONFIRMED,
                    METHOD_ALIAS
            );
        }

        // 3) 标准名规范化后唯一精确命中
        List<Team> exactHits = findExactNameHits(normalizedKey);
        if (exactHits.size() == 1) {
            return new EntityNormalizeResultDto(
                    exactHits.get(0).getId(),
                    EntityNormalizeOutcomeEnum.RESOLVED,
                    null,
                    METHOD_EXACT_NAME
            );
        }

        // 4) 新建候选；有 externalId 时写 PENDING 映射
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

    private ProviderTeamMapping findConfirmedExternalMapping(String providerCode, String externalId) {
        return providerTeamMappingMapper.selectOne(new LambdaQueryWrapper<ProviderTeamMapping>()
                .eq(ProviderTeamMapping::getProviderCode, providerCode)
                .eq(ProviderTeamMapping::getExternalTeamId, externalId)
                .in(
                        ProviderTeamMapping::getMappingStatus,
                        MappingStatusEnum.AUTO_CONFIRMED,
                        MappingStatusEnum.MANUAL_CONFIRMED
                )
                .last("LIMIT 1"));
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
