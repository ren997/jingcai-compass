package com.jingcaicompass.match.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jingcaicompass.match.dto.EntityNormalizeRequestDto;
import com.jingcaicompass.match.dto.EntityNormalizeResultDto;
import com.jingcaicompass.match.entity.League;
import com.jingcaicompass.match.entity.LeagueAlias;
import com.jingcaicompass.match.entity.ProviderLeagueMapping;
import com.jingcaicompass.match.enums.EntityNormalizeOutcomeEnum;
import com.jingcaicompass.match.enums.MappingStatusEnum;
import com.jingcaicompass.match.mapper.LeagueAliasMapper;
import com.jingcaicompass.match.mapper.LeagueMapper;
import com.jingcaicompass.match.mapper.ProviderLeagueMappingMapper;
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

/** 联赛标准化实现：已确认外部身份复用，The Odds 身份独立待人工复核。 */
@Service
@ConditionalOnBean(DataSource.class)
public class LeagueNormalizationServiceImpl implements LeagueNormalizationService {

    static final String METHOD_EXTERNAL_ID = "EXTERNAL_ID";
    static final String METHOD_ALIAS = "ALIAS";
    static final String METHOD_EXACT_NAME = "EXACT_NAME";
    static final String METHOD_NAME_CANDIDATE = "NAME_CANDIDATE";
    static final String METHOD_NAME_CANDIDATE_REUSE = "NAME_CANDIDATE_REUSE";
    static final String METHOD_REJECTED_REUSE = "REJECTED_REUSE";
    static final String METHOD_SPORTTERY_BASE_REUSE = "SPORTTERY_BASE_REUSE";
    private static final String SPORTTERY_PROVIDER_CODE = "CHINA_SPORTTERY";

    private final LeagueMapper leagueMapper;
    private final LeagueAliasMapper leagueAliasMapper;
    private final ProviderLeagueMappingMapper providerLeagueMappingMapper;

    public LeagueNormalizationServiceImpl(
            LeagueMapper leagueMapper,
            LeagueAliasMapper leagueAliasMapper,
            ProviderLeagueMappingMapper providerLeagueMappingMapper
    ) {
        this.leagueMapper = leagueMapper;
        this.leagueAliasMapper = leagueAliasMapper;
        this.providerLeagueMappingMapper = providerLeagueMappingMapper;
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
        ProviderLeagueMapping externalMapping = null;
        if (providerCode != null && externalId != null) {
            externalMapping = findExternalMapping(providerCode, externalId);
            if (isSportteryBaseProvider(providerCode) && externalMapping != null) {
                return new EntityNormalizeResultDto(
                        externalMapping.getLeagueId(),
                        EntityNormalizeOutcomeEnum.RESOLVED,
                        null,
                        METHOD_SPORTTERY_BASE_REUSE
                );
            }
            if (externalMapping != null && isConfirmed(externalMapping.getMappingStatus())) {
                return new EntityNormalizeResultDto(
                        externalMapping.getLeagueId(),
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

        // 2) The Odds 身份一律只保留外部资料，不能通过名称、别名或临时内部实体自动确认。
        if (requiresManualProviderReview(providerCode)) {
            if (externalMapping == null) {
                return createPendingExternalIdentity(providerCode, externalId, displayName, normalizedKey, externalScope);
            }
            return unresolvedExternalMapping(externalMapping, METHOD_NAME_CANDIDATE_REUSE);
        }

        // 3) 已确认别名
        LeagueAlias alias = leagueAliasMapper.selectOne(new LambdaQueryWrapper<LeagueAlias>()
                .eq(LeagueAlias::getAliasNormalized, normalizedKey)
                .last("LIMIT 1"));
        if (alias != null) {
            confirmExternalMapping(
                    externalMapping,
                    providerCode,
                    externalId,
                    alias.getLeagueId(),
                    MappingStatusEnum.MANUAL_CONFIRMED,
                    METHOD_ALIAS,
                    displayName,
                    normalizedKey,
                    externalScope
            );
            return new EntityNormalizeResultDto(
                    alias.getLeagueId(),
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
        List<League> exactHits = findExactNameHits(normalizedKey);
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
                    shouldPersistProviderMapping(providerCode, externalId) ? MappingStatusEnum.AUTO_CONFIRMED : null,
                    METHOD_EXACT_NAME
            );
        }

        // 6) 新建候选；有 externalId 时写 PENDING 映射
        League created = new League();
        created.setNameZh(displayName);
        created.setNameEn(looksPrimarilyLatin(displayName) ? displayName : null);
        leagueMapper.insert(created);

        MappingStatusEnum mappingStatus = null;
        if (shouldPersistProviderMapping(providerCode, externalId)) {
            ProviderLeagueMapping pending = new ProviderLeagueMapping();
            pending.setLeagueId(created.getId());
            pending.setProviderCode(providerCode);
            pending.setExternalLeagueId(externalId);
            pending.setExternalDisplayName(displayName);
            pending.setExternalNormalizedKey(normalizedKey);
            pending.setExternalScope(externalScope);
            pending.setMappingStatus(MappingStatusEnum.PENDING);
            pending.setMappingMethod(METHOD_NAME_CANDIDATE);
            providerLeagueMappingMapper.insert(pending);
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
    public LeagueAlias confirmAlias(Long leagueId, String aliasRaw, String source, String confirmedBy) {
        // 1) 校验联赛存在
        if (leagueId == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "leagueId must not be null");
        }
        if (!StringUtils.hasText(aliasRaw)) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "aliasRaw must not be blank");
        }
        League league = leagueMapper.selectById(leagueId);
        if (league == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "league not found: " + leagueId);
        }

        // 2) 规范化 key；UNIQUE 冲突则拒绝
        String normalized = NameNormalizationSupport.normalizedKey(aliasRaw);
        if (!StringUtils.hasText(normalized)) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "aliasRaw normalizes to empty key");
        }

        LeagueAlias existing = leagueAliasMapper.selectOne(new LambdaQueryWrapper<LeagueAlias>()
                .eq(LeagueAlias::getAliasNormalized, normalized)
                .last("LIMIT 1"));
        if (existing != null) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "league alias already confirmed: " + normalized);
        }

        // 3) 写入已确认别名（不改动其它 PENDING 映射）
        LeagueAlias alias = new LeagueAlias();
        alias.setLeagueId(leagueId);
        alias.setAliasRaw(aliasRaw.trim());
        alias.setAliasNormalized(normalized);
        alias.setSource(source);
        alias.setConfirmedBy(confirmedBy);
        alias.setConfirmedAt(Instant.now());
        leagueAliasMapper.insert(alias);
        return alias;
    }

    private ProviderLeagueMapping findExternalMapping(String providerCode, String externalId) {
        return providerLeagueMappingMapper.selectOne(new LambdaQueryWrapper<ProviderLeagueMapping>()
                .eq(ProviderLeagueMapping::getProviderCode, providerCode)
                .eq(ProviderLeagueMapping::getExternalLeagueId, externalId)
                .last("LIMIT 1"));
    }

    private EntityNormalizeResultDto unresolvedExternalMapping(
            ProviderLeagueMapping mapping,
            String method
    ) {
        return new EntityNormalizeResultDto(
                requiresManualProviderReview(mapping.getProviderCode()) ? null : mapping.getLeagueId(),
                EntityNormalizeOutcomeEnum.CANDIDATE_CREATED,
                mapping.getMappingStatus(),
                method
        );
    }

    private void confirmExternalMapping(
            ProviderLeagueMapping existing,
            String providerCode,
            String externalId,
            Long leagueId,
            MappingStatusEnum status,
            String method,
            String displayName,
            String normalizedKey,
            String externalScope
    ) {
        if (!shouldPersistProviderMapping(providerCode, externalId)) {
            return;
        }
        if (existing != null) {
            existing.setLeagueId(leagueId);
            existing.setMappingStatus(status);
            existing.setMappingMethod(method);
            applyReviewMetadata(existing, displayName, normalizedKey, externalScope);
            providerLeagueMappingMapper.updateById(existing);
            return;
        }
        ProviderLeagueMapping mapping = new ProviderLeagueMapping();
        mapping.setLeagueId(leagueId);
        mapping.setProviderCode(providerCode);
        mapping.setExternalLeagueId(externalId);
        mapping.setExternalDisplayName(displayName);
        mapping.setExternalNormalizedKey(normalizedKey);
        mapping.setExternalScope(externalScope);
        mapping.setMappingStatus(status);
        mapping.setMappingMethod(method);
        providerLeagueMappingMapper.insert(mapping);
    }

    private EntityNormalizeResultDto createPendingCandidate(
            String providerCode,
            String externalId,
            String displayName,
            String normalizedKey,
            String externalScope
    ) {
        League created = new League();
        created.setNameZh(displayName);
        created.setNameEn(looksPrimarilyLatin(displayName) ? displayName : null);
        leagueMapper.insert(created);

        ProviderLeagueMapping pending = new ProviderLeagueMapping();
        pending.setLeagueId(created.getId());
        pending.setProviderCode(providerCode);
        pending.setExternalLeagueId(externalId);
        pending.setExternalDisplayName(displayName);
        pending.setExternalNormalizedKey(normalizedKey);
        pending.setExternalScope(externalScope);
        pending.setMappingStatus(MappingStatusEnum.PENDING);
        pending.setMappingMethod(METHOD_NAME_CANDIDATE);
        providerLeagueMappingMapper.insert(pending);
        return new EntityNormalizeResultDto(created.getId(), EntityNormalizeOutcomeEnum.CANDIDATE_CREATED,
                MappingStatusEnum.PENDING, METHOD_NAME_CANDIDATE);
    }

    private static boolean requiresManualProviderReview(String providerCode) {
        return "THE_ODDS_API".equals(providerCode);
    }

    /** 为 The Odds 保留可审计的外部身份，不创建内部标准联赛。 */
    private EntityNormalizeResultDto createPendingExternalIdentity(
            String providerCode,
            String externalId,
            String displayName,
            String normalizedKey,
            String externalScope
    ) {
        ProviderLeagueMapping pending = new ProviderLeagueMapping();
        pending.setProviderCode(providerCode);
        pending.setExternalLeagueId(externalId);
        pending.setExternalDisplayName(displayName);
        pending.setExternalNormalizedKey(normalizedKey);
        pending.setExternalScope(externalScope);
        pending.setMappingStatus(MappingStatusEnum.PENDING);
        pending.setMappingMethod(METHOD_NAME_CANDIDATE);
        providerLeagueMappingMapper.insert(pending);
        return new EntityNormalizeResultDto(null, EntityNormalizeOutcomeEnum.CANDIDATE_CREATED,
                MappingStatusEnum.PENDING, METHOD_NAME_CANDIDATE);
    }

    private static boolean isSportteryBaseProvider(String providerCode) {
        return SPORTTERY_PROVIDER_CODE.equals(providerCode);
    }

    private static boolean shouldPersistProviderMapping(String providerCode, String externalId) {
        return providerCode != null && externalId != null && !isSportteryBaseProvider(providerCode);
    }

    private static void applyReviewMetadata(
            ProviderLeagueMapping mapping,
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

    private List<League> findExactNameHits(String normalizedKey) {
        List<League> all = leagueMapper.selectList(null);
        List<League> hits = new ArrayList<>();
        for (League league : all) {
            if (normalizedKey.equals(NameNormalizationSupport.normalizedKey(league.getNameZh()))
                    || normalizedKey.equals(NameNormalizationSupport.normalizedKey(league.getNameEn()))) {
                hits.add(league);
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
