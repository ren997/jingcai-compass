package com.jingcaicompass.admin.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingcaicompass.admin.dto.AdminManualMatchResultDto;
import com.jingcaicompass.admin.vo.AdminManualMatchResultVo;
import com.jingcaicompass.admin.vo.AdminManualResultSettlementVo;
import com.jingcaicompass.data.dto.RawDataPayloadSaveDto;
import com.jingcaicompass.data.dto.RawDataPayloadSaveResult;
import com.jingcaicompass.data.enums.ProviderDataTypeEnum;
import com.jingcaicompass.data.service.RawDataPayloadService;
import com.jingcaicompass.match.dto.ManualMatchResultFactDto;
import com.jingcaicompass.match.entity.MatchEntity;
import com.jingcaicompass.match.entity.MatchResultFact;
import com.jingcaicompass.match.enums.MatchResultFactSourceEnum;
import com.jingcaicompass.match.enums.MatchResultFactStatusEnum;
import com.jingcaicompass.match.enums.MatchStatusEnum;
import com.jingcaicompass.match.mapper.MatchMapper;
import com.jingcaicompass.match.mapper.MatchResultFactMapper;
import com.jingcaicompass.match.service.MatchResultFactWriter;
import com.jingcaicompass.settlement.dto.SettlementBatchResultDto;
import com.jingcaicompass.settlement.dto.SettlementRecalculationBatchResultDto;
import com.jingcaicompass.settlement.service.SettlementRecalculationService;
import com.jingcaicompass.settlement.service.SettlementService;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 校验人工赛果输入、保存专用证据并定向调用既有结算/重算流程。 */
@Service
@ConditionalOnBean(DataSource.class)
public class AdminManualMatchResultServiceImpl implements AdminManualMatchResultService {

    static final String MANUAL_PROVIDER_CODE = "MANUAL_ENTRY";

    private final MatchMapper matchMapper;
    private final MatchResultFactMapper factMapper;
    private final RawDataPayloadService rawDataPayloadService;
    private final MatchResultFactWriter factWriter;
    private final SettlementRecalculationService recalculationService;
    private final SettlementService settlementService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AdminManualMatchResultServiceImpl(
            MatchMapper matchMapper,
            MatchResultFactMapper factMapper,
            RawDataPayloadService rawDataPayloadService,
            MatchResultFactWriter factWriter,
            SettlementRecalculationService recalculationService,
            SettlementService settlementService,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.matchMapper = matchMapper;
        this.factMapper = factMapper;
        this.rawDataPayloadService = rawDataPayloadService;
        this.factWriter = factWriter;
        this.recalculationService = recalculationService;
        this.settlementService = settlementService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public AdminManualMatchResultVo record(AdminManualMatchResultDto request, String operator) {
        validateRequest(request, operator);
        MatchEntity match = requireEligibleMatch(request.matchId());
        rejectCurrentOfficialFact(match.getId());
        Instant enteredAt = clock.instant();

        // 1) 先将可复现的人工证据存为专用原始载荷，绝不混入官方数据类型。
        RawDataPayloadSaveDto evidence = new RawDataPayloadSaveDto(
                MANUAL_PROVIDER_CODE,
                ProviderDataTypeEnum.MANUAL_RESULT,
                "manual-result:" + match.getId(),
                evidenceJson(request, operator),
                null,
                enteredAt,
                enteredAt
        );
        RawDataPayloadSaveResult savedPayload = saveManualEvidence(evidence);

        MatchResultFactWriter.WriteResult writeResult;
        try {
            // 2) 追加不可变事实与审计；同一比赛由写入器行锁串行化。
            writeResult = factWriter.writeManual(new ManualMatchResultFactDto(
                    match.getId(), request.factStatus(), request.matchStatus(), request.homeScore(), request.awayScore(),
                    request.sourceNote(), request.entryReason(), operator, enteredAt
            ), savedPayload.payload().getId());
        } catch (RuntimeException exception) {
            markNewPayloadFailed(savedPayload, exception);
            throw translateWriteFailure(exception);
        }
        if (!savedPayload.duplicate()) {
            rawDataPayloadService.markParseSuccess(savedPayload.payload().getId());
        }

        MatchResultFact fact = factMapper.selectById(writeResult.factId());
        if (fact == null) {
            throw new IllegalStateException("created match result fact not found: " + writeResult.factId());
        }

        // 3) 仅在新事实写入后，先定向重算旧结算，再补齐缺失市场结算。
        boolean settlementTriggered = writeResult.outcome() != MatchResultFactWriter.WriteOutcome.UNCHANGED;
        AdminManualResultSettlementVo settlement = settlementTriggered
                ? settleMatch(match.getId())
                : emptySettlement();
        return toVo(fact, writeResult, settlementTriggered, settlement);
    }

    private void validateRequest(AdminManualMatchResultDto request, String operator) {
        if (request == null || request.matchId() == null || !Boolean.TRUE.equals(request.confirmed())) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "人工赛果补录必须经二次确认");
        }
        if (!StringUtils.hasText(operator)) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
        }
        if (!StringUtils.hasText(request.sourceNote()) || !StringUtils.hasText(request.entryReason())
                || request.sourceNote().trim().length() > 500 || request.entryReason().trim().length() > 500) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "来源说明和补录原因不能为空");
        }
        if (request.factStatus() == MatchResultFactStatusEnum.FINAL) {
            if (request.matchStatus() != MatchStatusEnum.FINISHED
                    || !validScore(request.homeScore()) || !validScore(request.awayScore())) {
                throw new BusinessException(ErrorCode.INVALID_PARAMETER, "FINAL 人工赛果必须为 FINISHED 且包含双方非负比分");
            }
            return;
        }
        if (request.factStatus() == MatchResultFactStatusEnum.VOID) {
            if ((request.matchStatus() != MatchStatusEnum.CANCELLED && request.matchStatus() != MatchStatusEnum.ABANDONED)
                    || request.homeScore() != null || request.awayScore() != null) {
                throw new BusinessException(ErrorCode.INVALID_PARAMETER, "VOID 人工赛果仅允许取消或中止且不得填写比分");
            }
            return;
        }
        throw new BusinessException(ErrorCode.INVALID_PARAMETER, "人工赛果仅支持 FINAL 或 VOID");
    }

    private boolean validScore(Integer score) {
        return score != null && score >= 0 && score <= 99;
    }

    private MatchEntity requireEligibleMatch(Long matchId) {
        MatchEntity match = matchMapper.selectById(matchId);
        if (match == null) {
            throw new BusinessException(ErrorCode.MATCH_NOT_FOUND);
        }
        if (match.getKickoffTime() == null || match.getKickoffTime().isAfter(clock.instant())) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "比赛尚未开赛，不能人工补录赛果");
        }
        return match;
    }

    private void rejectCurrentOfficialFact(Long matchId) {
        MatchResultFact current = factMapper.selectCurrentByMatchId(matchId);
        if (current != null && current.getResultSource() == MatchResultFactSourceEnum.OFFICIAL) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "当前已有官方赛果，禁止人工覆盖");
        }
    }

    private String evidenceJson(AdminManualMatchResultDto request, String operator) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("matchId", request.matchId());
        evidence.put("factStatus", request.factStatus());
        evidence.put("matchStatus", request.matchStatus());
        evidence.put("homeScore", request.homeScore());
        evidence.put("awayScore", request.awayScore());
        evidence.put("sourceNote", request.sourceNote().trim());
        evidence.put("entryReason", request.entryReason().trim());
        evidence.put("enteredBy", operator.trim());
        try {
            return objectMapper.writeValueAsString(evidence);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("manual result evidence serialization failed", exception);
        }
    }

    private RawDataPayloadSaveResult saveManualEvidence(RawDataPayloadSaveDto evidence) {
        try {
            return rawDataPayloadService.savePayload(evidence);
        } catch (DataIntegrityViolationException exception) {
            // 并发相同补录命中数据库去重键时，前一独立事务已提交，重试即可复用其证据。
            return rawDataPayloadService.savePayload(evidence);
        }
    }

    private void markNewPayloadFailed(RawDataPayloadSaveResult savedPayload, RuntimeException exception) {
        if (!savedPayload.duplicate()) {
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            rawDataPayloadService.markParseFailed(savedPayload.payload().getId(), message);
        }
    }

    private RuntimeException translateWriteFailure(RuntimeException exception) {
        if (exception instanceof BusinessException) {
            return exception;
        }
        if (exception instanceof IllegalArgumentException) {
            return new BusinessException(ErrorCode.BUSINESS_ERROR, exception.getMessage(), exception);
        }
        return exception;
    }

    private AdminManualResultSettlementVo settleMatch(Long matchId) {
        SettlementRecalculationBatchResultDto recalculation = recalculationService
                .recalculateOutdatedSettlementsForMatch(matchId);
        SettlementBatchResultDto settlement = settlementService.settlePendingPredictionsForMatch(matchId);
        return new AdminManualResultSettlementVo(
                recalculation.candidatePredictionCount(), recalculation.recalculatedPredictionCount(),
                recalculation.recalculatedMarketCount(), recalculation.failedPredictionCount(),
                recalculation.manualReviewPredictionCount(), settlement.candidatePredictionCount(),
                settlement.settledPredictionCount(), settlement.settledMarketCount(), settlement.failedPredictionCount(),
                settlement.manualReviewPredictionCount()
        );
    }

    private AdminManualResultSettlementVo emptySettlement() {
        return new AdminManualResultSettlementVo(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private AdminManualMatchResultVo toVo(
            MatchResultFact fact,
            MatchResultFactWriter.WriteResult writeResult,
            boolean settlementTriggered,
            AdminManualResultSettlementVo settlement
    ) {
        return new AdminManualMatchResultVo(
                fact.getId(), fact.getFactVersion(), writeResult.outcome().name(), fact.getResultSource(),
                fact.getFactStatus(), fact.getMatchStatus(), fact.getHomeScore(), fact.getAwayScore(),
                fact.getSourceNote(), fact.getEntryReason(), fact.getEnteredBy(), fact.getProviderUpdatedAt(),
                settlementTriggered, settlement
        );
    }
}
