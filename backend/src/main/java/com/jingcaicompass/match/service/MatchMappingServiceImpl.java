package com.jingcaicompass.match.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jingcaicompass.match.dto.MatchMapCandidateDto;
import com.jingcaicompass.match.dto.MatchMapRequestDto;
import com.jingcaicompass.match.dto.MatchMapResultDto;
import com.jingcaicompass.match.entity.MatchEntity;
import com.jingcaicompass.match.entity.MatchSourceMapping;
import com.jingcaicompass.match.enums.MatchMapOutcomeEnum;
import com.jingcaicompass.match.enums.MappingStatusEnum;
import com.jingcaicompass.match.mapper.MatchMapper;
import com.jingcaicompass.match.mapper.MatchSourceMappingMapper;
import com.jingcaicompass.match.support.MatchMappingScoreSupport;
import com.jingcaicompass.match.support.MatchMappingScoreSupport.ScoredCandidate;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 比赛自动映射实现：复用已确认 → 时间窗打分 → 自动确认或待复核。 */
@Service
@ConditionalOnBean(DataSource.class)
public class MatchMappingServiceImpl implements MatchMappingService {

    static final String METHOD_EXTERNAL_ID_REUSE = "EXTERNAL_ID_REUSE";
    static final String METHOD_SCORE_AUTO = "SCORE_AUTO";
    static final String METHOD_SCORE_PENDING = "SCORE_PENDING";
    static final String METHOD_HARD_REJECT_PENDING = "HARD_REJECT_PENDING";

    private final MatchMapper matchMapper;
    private final MatchSourceMappingMapper matchSourceMappingMapper;

    public MatchMappingServiceImpl(
            MatchMapper matchMapper,
            MatchSourceMappingMapper matchSourceMappingMapper
    ) {
        this.matchMapper = matchMapper;
        this.matchSourceMappingMapper = matchSourceMappingMapper;
    }

    @Override
    public MatchMapResultDto resolve(MatchMapRequestDto request) {
        Objects.requireNonNull(request, "request must not be null");
        if (!StringUtils.hasText(request.providerCode())) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "providerCode must not be blank");
        }
        if (!StringUtils.hasText(request.externalMatchId())) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "externalMatchId must not be blank");
        }
        if (request.kickoffTime() == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "kickoffTime must not be null");
        }

        String providerCode = request.providerCode().trim();
        String externalMatchId = request.externalMatchId().trim();

        // 1) 已确认外部比赛映射优先复用
        MatchSourceMapping confirmed = findConfirmed(providerCode, externalMatchId);
        if (confirmed != null) {
            return new MatchMapResultDto(
                    confirmed.getId(),
                    confirmed.getMatchId(),
                    MatchMapOutcomeEnum.REUSED,
                    confirmed.getMappingStatus(),
                    confirmed.getMappingConfidence(),
                    confirmed.getMappingExplanation(),
                    METHOD_EXTERNAL_ID_REUSE,
                    toCandidateDtos(confirmed.getMappingCandidates())
            );
        }

        // 2) 时间窗内拉内部比赛并打分
        List<MatchEntity> windowMatches = loadCandidates(request.kickoffTime());
        List<ScoredCandidate> scored = windowMatches.stream()
                .map(match -> MatchMappingScoreSupport.score(request, match))
                .sorted(Comparator
                        .comparing(ScoredCandidate::score)
                        .reversed()
                        .thenComparing(ScoredCandidate::matchId))
                .toList();

        if (scored.isEmpty()) {
            return new MatchMapResultDto(
                    null,
                    null,
                    MatchMapOutcomeEnum.NO_CANDIDATE,
                    null,
                    null,
                    "NO_CANDIDATE",
                    METHOD_SCORE_PENDING,
                    List.of()
            );
        }

        ScoredCandidate top = scored.get(0);
        ScoredCandidate second = scored.size() > 1 ? scored.get(1) : null;
        boolean auto = MatchMappingScoreSupport.canAutoConfirm(top, second);

        MappingStatusEnum status = auto ? MappingStatusEnum.AUTO_CONFIRMED : MappingStatusEnum.PENDING;
        MatchMapOutcomeEnum outcome = auto ? MatchMapOutcomeEnum.AUTO_CONFIRMED : MatchMapOutcomeEnum.PENDING;
        String method = auto
                ? METHOD_SCORE_AUTO
                : (top.hardReject() ? METHOD_HARD_REJECT_PENDING : METHOD_SCORE_PENDING);
        String explanation = buildExplanation(top, second, auto);
        List<MatchMapCandidateDto> candidateDtos = scored.stream()
                .limit(5)
                .map(c -> new MatchMapCandidateDto(c.matchId(), c.score(), c.reasons()))
                .toList();

        // 3) 幂等 upsert：同 provider+external 的 PENDING 更新；否则插入
        MatchSourceMapping saved = upsertMapping(
                providerCode,
                externalMatchId,
                request,
                top.matchId(),
                status,
                top.score(),
                method,
                explanation,
                candidateDtos
        );

        return new MatchMapResultDto(
                saved.getId(),
                saved.getMatchId(),
                outcome,
                status,
                top.score(),
                explanation,
                method,
                candidateDtos
        );
    }

    @Override
    public List<MatchSourceMapping> listPending(String providerCode) {
        LambdaQueryWrapper<MatchSourceMapping> query = new LambdaQueryWrapper<MatchSourceMapping>()
                .eq(MatchSourceMapping::getMappingStatus, MappingStatusEnum.PENDING)
                .orderByDesc(MatchSourceMapping::getUpdatedAt);
        if (StringUtils.hasText(providerCode)) {
            query.eq(MatchSourceMapping::getProviderCode, providerCode.trim());
        }
        return matchSourceMappingMapper.selectList(query);
    }

    private MatchSourceMapping findConfirmed(String providerCode, String externalMatchId) {
        return matchSourceMappingMapper.selectOne(new LambdaQueryWrapper<MatchSourceMapping>()
                .eq(MatchSourceMapping::getProviderCode, providerCode)
                .eq(MatchSourceMapping::getExternalMatchId, externalMatchId)
                .in(
                        MatchSourceMapping::getMappingStatus,
                        MappingStatusEnum.AUTO_CONFIRMED,
                        MappingStatusEnum.MANUAL_CONFIRMED
                )
                .last("LIMIT 1"));
    }

    private List<MatchEntity> loadCandidates(Instant kickoffTime) {
        Instant from = kickoffTime.minus(MatchMappingScoreSupport.CANDIDATE_WINDOW_MINUTES, ChronoUnit.MINUTES);
        Instant to = kickoffTime.plus(MatchMappingScoreSupport.CANDIDATE_WINDOW_MINUTES, ChronoUnit.MINUTES);
        return matchMapper.selectList(new LambdaQueryWrapper<MatchEntity>()
                .ge(MatchEntity::getKickoffTime, from)
                .le(MatchEntity::getKickoffTime, to));
    }

    private MatchSourceMapping upsertMapping(
            String providerCode,
            String externalMatchId,
            MatchMapRequestDto request,
            Long matchId,
            MappingStatusEnum status,
            BigDecimal confidence,
            String method,
            String explanation,
            List<MatchMapCandidateDto> candidates
    ) {
        MatchSourceMapping existing = matchSourceMappingMapper.selectOne(new LambdaQueryWrapper<MatchSourceMapping>()
                .eq(MatchSourceMapping::getProviderCode, providerCode)
                .eq(MatchSourceMapping::getExternalMatchId, externalMatchId)
                .last("LIMIT 1"));

        List<Map<String, Object>> candidateMaps = toCandidateMaps(candidates);

        if (existing != null) {
            if (existing.getMappingStatus() == MappingStatusEnum.AUTO_CONFIRMED
                    || existing.getMappingStatus() == MappingStatusEnum.MANUAL_CONFIRMED) {
                return existing;
            }
            existing.setMatchId(matchId);
            existing.setExternalLeagueId(request.externalLeagueId());
            existing.setExternalHomeTeamId(request.externalHomeTeamId());
            existing.setExternalAwayTeamId(request.externalAwayTeamId());
            existing.setMappingStatus(status);
            existing.setMappingConfidence(confidence);
            existing.setMappingMethod(method);
            existing.setMappingExplanation(explanation);
            existing.setMappingCandidates(candidateMaps);
            matchSourceMappingMapper.updateById(existing);
            return existing;
        }

        MatchSourceMapping created = new MatchSourceMapping();
        created.setMatchId(matchId);
        created.setProviderCode(providerCode);
        created.setExternalMatchId(externalMatchId);
        created.setExternalLeagueId(request.externalLeagueId());
        created.setExternalHomeTeamId(request.externalHomeTeamId());
        created.setExternalAwayTeamId(request.externalAwayTeamId());
        created.setMappingStatus(status);
        created.setMappingConfidence(confidence);
        created.setMappingMethod(method);
        created.setMappingExplanation(explanation);
        created.setMappingCandidates(candidateMaps);
        matchSourceMappingMapper.insert(created);
        return created;
    }

    private static String buildExplanation(ScoredCandidate top, ScoredCandidate second, boolean auto) {
        List<String> parts = new ArrayList<>(top.reasons());
        parts.add("SCORE=" + top.score());
        if (second != null) {
            parts.add("SECOND_SCORE=" + second.score());
        }
        parts.add(auto ? "AUTO" : "PENDING");
        return String.join("; ", parts);
    }

    private static List<Map<String, Object>> toCandidateMaps(List<MatchMapCandidateDto> candidates) {
        List<Map<String, Object>> maps = new ArrayList<>();
        for (MatchMapCandidateDto candidate : candidates) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("matchId", candidate.matchId());
            map.put("score", candidate.score());
            map.put("reasons", candidate.reasons());
            maps.add(map);
        }
        return maps;
    }

    private static List<MatchMapCandidateDto> toCandidateDtos(List<Map<String, Object>> maps) {
        if (maps == null || maps.isEmpty()) {
            return List.of();
        }
        List<MatchMapCandidateDto> result = new ArrayList<>();
        for (Map<String, Object> map : maps) {
            Long matchId = map.get("matchId") == null ? null : ((Number) map.get("matchId")).longValue();
            BigDecimal score = map.get("score") == null
                    ? null
                    : new BigDecimal(map.get("score").toString());
            @SuppressWarnings("unchecked")
            List<String> reasons = map.get("reasons") instanceof List<?> list
                    ? list.stream().map(String::valueOf).collect(Collectors.toList())
                    : List.of();
            result.add(new MatchMapCandidateDto(matchId, score, reasons));
        }
        return result;
    }
}
