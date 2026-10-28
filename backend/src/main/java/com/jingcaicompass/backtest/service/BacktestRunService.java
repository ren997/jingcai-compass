package com.jingcaicompass.backtest.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jingcaicompass.backtest.BacktestProperties;
import com.jingcaicompass.backtest.dto.BacktestCandidateDto;
import com.jingcaicompass.backtest.dto.BacktestCaseQueryDto;
import com.jingcaicompass.backtest.dto.BacktestRunCreateDto;
import com.jingcaicompass.backtest.dto.BacktestRunListQueryDto;
import com.jingcaicompass.backtest.entity.BacktestCaseEntity;
import com.jingcaicompass.backtest.entity.BacktestRunEntity;
import com.jingcaicompass.backtest.enums.BacktestMarketScopeEnum;
import com.jingcaicompass.backtest.enums.BacktestRunStatusEnum;
import com.jingcaicompass.backtest.enums.BacktestStageEnum;
import com.jingcaicompass.backtest.mapper.BacktestCandidateMapper;
import com.jingcaicompass.backtest.mapper.BacktestCaseMapper;
import com.jingcaicompass.backtest.mapper.BacktestRunMapper;
import com.jingcaicompass.backtest.mapper.OddsHistoryArchiveMapper;
import com.jingcaicompass.backtest.service.BacktestParameterSets.ParamGroup;
import com.jingcaicompass.backtest.vo.BacktestCaseVo;
import com.jingcaicompass.backtest.vo.BacktestEstimateVo;
import com.jingcaicompass.backtest.vo.BacktestLeagueVo;
import com.jingcaicompass.backtest.vo.BacktestRunVo;
import com.jingcaicompass.odds.client.AsianModelProperties;
import com.jingcaicompass.prediction.enums.BaselinePredictionSkipReasonEnum;
import com.jingcaicompass.odds.service.AsianFreezeSnapshotSelector;
import com.jingcaicompass.odds.service.AsianMarketConsensusAggregator;
import com.jingcaicompass.odds.service.OddsHistorySnapshotBuilder;
import com.jingcaicompass.prediction.service.AsianDecisionEngine;
import com.jingcaicompass.prediction.service.BaselinePredictionGenerationServiceImpl;
import com.jingcaicompass.system.api.PageResult;
import com.jingcaicompass.system.config.properties.PaginationProperties;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

/**
 * 回测运行的定义与查询。
 *
 * <p>创建端点<b>不</b>同步执行回测：一次 120 天跨度的 run 要跑 40 分钟以上，HTTP 请求
 * 等不起，而「先返回再后台跑」必须有一个可恢复的执行实体——那就是 {@code backtest_runs}。
 * 所以创建只写一行 {@code QUEUED}，真正的推进由 {@code BacktestRunnerService} 抢单完成。</p>
 *
 * <p>日期与预算的校验集中在这一处，因为它是唯一能拒绝非法输入的地方：worker 拿到的 run
 * 一定已经通过校验。把校验也放进 worker 会让「入队时合法、执行时非法」变得可能
 * （例如 minLotteryDate 配置在两次启动之间被调大）。</p>
 */
@Service
@ConditionalOnBean(DataSource.class)
public class BacktestRunService {

    private static final Logger log = LoggerFactory.getLogger(BacktestRunService.class);

    /** 上海时区：业务日与「昨天」都按它算，不能用服务器本地时区。 */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final BacktestRunMapper runMapper;
    private final BacktestCaseMapper caseMapper;
    private final OddsHistoryArchiveMapper archiveMapper;
    private final BacktestCandidateMapper candidateMapper;
    private final BacktestProperties properties;
    private final AsianModelProperties modelProperties;
    private final PaginationProperties paginationProperties;

    public BacktestRunService(
            BacktestRunMapper runMapper,
            BacktestCaseMapper caseMapper,
            OddsHistoryArchiveMapper archiveMapper,
            BacktestCandidateMapper candidateMapper,
            BacktestProperties properties,
            AsianModelProperties modelProperties,
            PaginationProperties paginationProperties
    ) {
        this.runMapper = runMapper;
        this.caseMapper = caseMapper;
        this.archiveMapper = archiveMapper;
        this.candidateMapper = candidateMapper;
        this.properties = properties;
        this.modelProperties = modelProperties;
        this.paginationProperties = paginationProperties;
    }

    /**
     * 创建前的成本预估。
     *
     * <p>与 {@link #create} 共用 {@link #validate}，因此「预估说的场次数」与「真正入队的场次数」
     * 必然一致。曾出现过两处各写一份过滤条件的写法，结果是预估 300 场、实跑 2400 场，
     * 而用户是在看到预估之后才点的确认。</p>
     */
    public BacktestEstimateVo estimate(BacktestRunCreateDto dto) {
        Prepared prepared = validate(dto);
        List<ParamGroup> groups = BacktestParameterSets.fromRequests(dto.parameterSets(), modelProperties);
        int matchCount = prepared.candidates().size();
        long reusable = archiveMapper.countReusableSuccess(
                properties.providerCode(), dto.dateFrom(), dto.dateTo());
        int reusableCount = (int) Math.min(reusable, matchCount);
        int quota = Math.max(matchCount - reusableCount, 0);
        List<String> warnings = new ArrayList<>();
        if (reusableCount == 0 && matchCount > 0) {
            warnings.add("该区间尚无可用归档，全部场次都会发起历史赔率调用");
        }
        if (quota > properties.limits().budgetConfirmThreshold()) {
            warnings.add("预计调用 " + quota + " 次，超过阈值 "
                    + properties.limits().budgetConfirmThreshold() + "，需二次确认");
        }
        // 结论可比性提示：历史赔率只有 7~8 家庄，σ 分布与线上系统性不同。
        warnings.add("历史赔率仅约 7~8 家庄，σ 与线上不可直接比较，结论不能反推线上阈值");
        return new BacktestEstimateVo(
                dto.dateFrom(),
                dto.dateTo(),
                matchCount,
                reusableCount,
                quota,
                // NovaAPI 固定 2s/次的全局限速，这是墙钟时间的下限（不含判定与结算耗时）。
                quota * 2,
                quota > properties.limits().budgetConfirmThreshold(),
                groups.size(),
                warnings
        );
    }

    /** 入队一次回测。 */
    public BacktestRunVo create(BacktestRunCreateDto dto, String operatorUsername) {
        Prepared prepared = validate(dto);
        List<ParamGroup> groups = BacktestParameterSets.fromRequests(dto.parameterSets(), modelProperties);

        BacktestEstimateVo estimate = estimate(dto);
        if (estimate.budgetConfirmRequired() && !Boolean.TRUE.equals(dto.confirmBudget())) {
            throw new BusinessException(
                    ErrorCode.BACKTEST_BUDGET_UNCONFIRMED,
                    "预计调用 " + estimate.estimatedQuotaCost() + " 次（阈值 "
                            + properties.limits().budgetConfirmThreshold() + "），请确认后重试"
            );
        }

        BacktestRunEntity entity = new BacktestRunEntity();
        entity.setRunKey(UUID.randomUUID().toString().replace("-", ""));
        entity.setRunName(dto.runName().trim());
        entity.setRunStatus(BacktestRunStatusEnum.QUEUED);
        entity.setRunStage(BacktestStageEnum.NONE);
        entity.setOperatorUsername(operatorUsername);
        entity.setPredictionType("ASIAN");
        entity.setDateFrom(dto.dateFrom());
        entity.setDateTo(dto.dateTo());
        entity.setLeagueNames(dto.leagueNames());
        entity.setDecisionLeadMinutes(prepared.decisionLeadMinutes());
        entity.setParameterSets(BacktestParameterSets.toStored(groups));
        entity.setProviderCode(properties.providerCode());
        entity.setModelVersion(BaselinePredictionGenerationServiceImpl.MODEL_VERSION);
        entity.setSelectorVersion(AsianFreezeSnapshotSelector.SELECTOR_VERSION);
        entity.setAggregationVersion(AsianMarketConsensusAggregator.AGGREGATION_VERSION);
        entity.setBuilderVersion(OddsHistorySnapshotBuilder.BUILDER_VERSION);
        entity.setGitSha(gitSha());
        entity.setTotalMatchCount(prepared.candidates().size());
        entity.setEstimateQuotaCost(estimate.estimatedQuotaCost());
        runMapper.insert(entity);

        log.info("回测已入队 runKey={} {}~{} 场次={} 参数组={} 预估调用={}",
                entity.getRunKey(), dto.dateFrom(), dto.dateTo(),
                entity.getTotalMatchCount(), groups.size(), estimate.estimatedQuotaCost());
        return toVo(entity);
    }

    /** 分页列表（不限状态）。 */
    public PageResult<BacktestRunVo> list(BacktestRunListQueryDto query) {
        return list(query, null);
    }

    /**
     * 分页列表，可限定允许暴露的状态集合。
     *
     * <p>状态过滤必须下到 SQL：在内存里过滤会让 {@code total} 与分页游标都对不上，
     * 表现是「共 37 条、每页 20，翻到第二页只看到 3 条」。</p>
     *
     * @param allowedStatuses 非空时只返回这些状态的行；null 表示不限
     */
    public PageResult<BacktestRunVo> list(
            BacktestRunListQueryDto query,
            List<BacktestRunStatusEnum> allowedStatuses
    ) {
        long pageNo = pageNo(query == null ? null : query.page());
        long pageSize = pageSize(query == null ? null : query.pageSize());
        LambdaQueryWrapper<BacktestRunEntity> wrapper = new LambdaQueryWrapper<>();
        if (allowedStatuses != null && !allowedStatuses.isEmpty()) {
            wrapper.in(BacktestRunEntity::getRunStatus, allowedStatuses);
        }
        if (query != null) {
            if (query.runStatus() != null && !query.runStatus().isBlank()) {
                wrapper.eq(BacktestRunEntity::getRunStatus, BacktestRunStatusEnum.valueOf(query.runStatus().trim()));
            }
            if (query.dateFrom() != null) {
                wrapper.ge(BacktestRunEntity::getDateTo, query.dateFrom());
            }
            if (query.dateTo() != null) {
                wrapper.le(BacktestRunEntity::getDateFrom, query.dateTo());
            }
        }
        wrapper.orderByDesc(BacktestRunEntity::getId);
        Page<BacktestRunEntity> page = runMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
        List<BacktestRunVo> records = page.getRecords().stream().map(BacktestRunService::toVo).toList();
        return new PageResult<>(records, page.getCurrent(), page.getSize(), page.getTotal());
    }

    /** 详情（含进度）。 */
    public BacktestRunVo detail(Long runId) {
        return toVo(requireRun(runId));
    }

    /** 联赛候选。 */
    public List<BacktestLeagueVo> leagues(LocalDate dateFrom, LocalDate dateTo) {
        LocalDate from = dateFrom == null ? properties.limits().minLotteryDate() : dateFrom;
        LocalDate to = dateTo == null ? LocalDate.now(BUSINESS_ZONE).minusDays(1) : dateTo;
        if (to.isBefore(from)) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "dateTo 不能早于 dateFrom");
        }
        return candidateMapper.selectLeagues(from, to, properties.providerCode());
    }

    /** 请求取消：只置标志，已写样本保留。 */
    public BacktestRunVo cancel(Long runId) {
        BacktestRunEntity run = requireRun(runId);
        if (!isInFlight(run.getRunStatus())) {
            throw new BusinessException(
                    ErrorCode.BACKTEST_RUN_STATE_INVALID,
                    "当前状态 " + run.getRunStatus() + " 不允许取消");
        }
        runMapper.requestCancel(runId);
        return toVo(requireRun(runId));
    }

    /** 续跑：{@code INTERRUPTED → QUEUED}。 */
    public BacktestRunVo resume(Long runId) {
        BacktestRunEntity run = requireRun(runId);
        if (run.getRunStatus() != BacktestRunStatusEnum.INTERRUPTED) {
            throw new BusinessException(
                    ErrorCode.BACKTEST_RUN_STATE_INVALID,
                    "只有 INTERRUPTED 状态可续跑，当前 " + run.getRunStatus());
        }
        if (runMapper.resumeInterrupted(runId) != 1) {
            throw new BusinessException(ErrorCode.BACKTEST_RUN_STATE_INVALID, "续跑未生效，请刷新后重试");
        }
        return toVo(requireRun(runId));
    }

    /** 已写入的样本数，供进度页展示「实际产出」。 */
    public long countCases(Long runId) {
        return caseMapper.countDistinctMatches(runId);
    }

    /**
     * 逐场明细分页。
     *
     * <p>{@code marketScope} 过滤的是<b>有方向的腿</b>而不是样本：只筛让球时，
     * 「让球无方向但大小球有方向」的样本应当被排除，而 {@code handicap_pick IS NULL}
     * 恰好满足这个语义。{@code onlyPicked} 与 {@code skipReason} 互斥使用时以
     * {@code onlyPicked} 优先——它是「只看发了方向的」这一个更强的约束。</p>
     */
    public PageResult<BacktestCaseVo> cases(Long runId, BacktestCaseQueryDto query) {
        requireRun(runId);
        long pageNo = pageNo(query == null ? null : query.page());
        long pageSize = pageSize(query == null ? null : query.pageSize());

        LambdaQueryWrapper<BacktestCaseEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BacktestCaseEntity::getRunId, runId);
        if (query != null) {
            if (query.groupKey() != null && !query.groupKey().isBlank()) {
                wrapper.eq(BacktestCaseEntity::getGroupKey, query.groupKey().trim());
            }
            if (Boolean.TRUE.equals(query.onlyPicked())) {
                wrapper.isNull(BacktestCaseEntity::getSkipReason);
            } else if (query.skipReason() != null && !query.skipReason().isBlank()) {
                wrapper.eq(BacktestCaseEntity::getSkipReason,
                        BaselinePredictionSkipReasonEnum.valueOf(query.skipReason().trim()));
            }
            if (query.marketScope() != null && !query.marketScope().isBlank()) {
                BacktestMarketScopeEnum scope = BacktestMarketScopeEnum.valueOf(query.marketScope().trim());
                if (!scope.includesHandicap()) {
                    wrapper.isNull(BacktestCaseEntity::getHandicapPick);
                }
                if (!scope.includesTotals()) {
                    wrapper.isNull(BacktestCaseEntity::getTotalsPick);
                }
                wrapper.and(inner -> inner.isNotNull(BacktestCaseEntity::getHandicapPick)
                        .or()
                        .isNotNull(BacktestCaseEntity::getTotalsPick));
            }
        }
        wrapper.orderByAsc(BacktestCaseEntity::getGroupKey)
                .orderByAsc(BacktestCaseEntity::getExternalMatchId);
        Page<BacktestCaseEntity> page = caseMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
        List<BacktestCaseVo> records = page.getRecords().stream()
                .map(BacktestRunService::toCaseVo)
                .toList();
        return new PageResult<>(records, page.getCurrent(), page.getSize(), page.getTotal());
    }

    private static BacktestCaseVo toCaseVo(BacktestCaseEntity entity) {
        return new BacktestCaseVo(
                entity.getId(),
                entity.getRunId(),
                entity.getGroupKey(),
                entity.getExternalMatchId(),
                entity.getMatchId(),
                entity.getLotteryMatchNo(),
                entity.getLeagueName(),
                entity.getDecisionAt(),
                entity.getKickoffTime(),
                entity.getDecisionStage(),
                entity.getSkipReason(),
                entity.getFrozenBookmakerCode(),
                entity.getFrozenHandicapLine(),
                entity.getFrozenHomeOdds(),
                entity.getFrozenAwayOdds(),
                entity.getFrozenTotalLine(),
                entity.getFrozenOverOdds(),
                entity.getFrozenUnderOdds(),
                entity.getFrozenCapturedAt(),
                entity.getHandicapPick(),
                entity.getHandicapConfidence(),
                entity.getTotalsPick(),
                entity.getTotalsConfidence(),
                entity.getHandicapDispersion(),
                entity.getTotalsDispersion(),
                entity.getLambdaHome(),
                entity.getLambdaAway(),
                entity.getModelCoverProb(),
                entity.getMarketCoverProb(),
                entity.getEdgeCover(),
                entity.getModelOverProb(),
                entity.getMarketOverProb(),
                entity.getEdgeOver(),
                entity.getNeighborMaxDeviation(),
                entity.getMaxResidual(),
                entity.getInfeasibilityReason(),
                entity.getHandicapGatePass(),
                entity.getTotalsGatePass(),
                entity.getResultStatus(),
                entity.getHomeScore(),
                entity.getAwayScore(),
                entity.getHandicapStatus(),
                entity.getHandicapProfit(),
                entity.getTotalsStatus(),
                entity.getTotalsProfit(),
                entity.getSettledAt()
        );
    }

    private BacktestRunEntity requireRun(Long runId) {
        BacktestRunEntity run = runId == null ? null : runMapper.selectById(runId);
        if (run == null) {
            throw new BusinessException(ErrorCode.BACKTEST_RUN_NOT_FOUND, "回测运行不存在：" + runId);
        }
        return run;
    }

    private static boolean isInFlight(BacktestRunStatusEnum status) {
        return status == BacktestRunStatusEnum.QUEUED
                || status == BacktestRunStatusEnum.ARCHIVING
                || status == BacktestRunStatusEnum.EVALUATING
                || status == BacktestRunStatusEnum.SETTLING
                || status == BacktestRunStatusEnum.AGGREGATING;
    }

    /**
     * 校验并解析出候选场次。
     *
     * <p>四条边界的顺序有讲究：先比大小再比跨度，否则「dateFrom > dateTo」会被报成
     * 「跨度 -3 天超限」，让人以为要缩小范围。</p>
     */
    private Prepared validate(BacktestRunCreateDto dto) {
        if (dto == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "请求体不能为空");
        }
        LocalDate from = dto.dateFrom();
        LocalDate to = dto.dateTo();
        if (from == null || to == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "dateFrom 与 dateTo 必须提供");
        }
        if (from.isAfter(to)) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "dateFrom 不能晚于 dateTo");
        }
        if (from.isBefore(properties.limits().minLotteryDate())) {
            throw new BusinessException(
                    ErrorCode.INVALID_PARAMETER,
                    "dateFrom 不能早于 " + properties.limits().minLotteryDate()
                            + "（历史赔率只回溯约 4 个月，更早的场次必然无数据）");
        }
        LocalDate yesterday = LocalDate.now(BUSINESS_ZONE).minusDays(1);
        if (to.isAfter(yesterday)) {
            throw new BusinessException(
                    ErrorCode.INVALID_PARAMETER, "dateTo 不能晚于 " + yesterday + "（当日场次尚无最终赛果）");
        }
        long span = to.toEpochDay() - from.toEpochDay() + 1;
        if (span > properties.limits().maxSpanDays()) {
            throw new BusinessException(
                    ErrorCode.INVALID_PARAMETER,
                    "日期跨度 " + span + " 天超过上限 " + properties.limits().maxSpanDays() + " 天");
        }
        if (dto.parameterSets().size() > properties.limits().maxParameterSets()) {
            throw new BusinessException(
                    ErrorCode.INVALID_PARAMETER,
                    "参数组数量 " + dto.parameterSets().size() + " 超过上限 " + properties.limits().maxParameterSets());
        }
        int leadMinutes = dto.decisionLeadMinutes() == null
                ? properties.decisionLeadMinutes()
                : dto.decisionLeadMinutes();
        if (leadMinutes < 1 || leadMinutes > 2880) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "decisionLeadMinutes 必须在 1~2880 之间");
        }

        List<BacktestCandidateDto> candidates =
                candidateMapper.selectCandidates(from, to, dto.leagueNames());
        if (candidates.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.INVALID_PARAMETER, "该区间没有可回测的场次（需已同步赛程且具备外部比赛 ID）");
        }
        return new Prepared(candidates, leadMinutes);
    }

    private long pageNo(Integer value) {
        return value == null || value < 1 ? 1 : value;
    }

    private long pageSize(Integer value) {
        if (value == null || value < 1) {
            return Math.min(20, paginationProperties.maxPageSize());
        }
        return Math.min(value, paginationProperties.maxPageSize());
    }

    /**
     * 构建版本号。
     *
     * <p>取环境变量而不是 Maven 资源过滤：本项目不打 jar 到生产以外的地方，
     * 过滤插件在本地 {@code spring-boot:run} 下不会重新生成资源，读到的会是上一次构建的值——
     * 那比「unknown」更危险，因为它看起来是可信的。</p>
     */
    private static String gitSha() {
        String sha = System.getenv("GIT_SHA");
        if (sha == null || sha.isBlank()) {
            sha = System.getenv("BUILD_GIT_SHA");
        }
        return sha == null || sha.isBlank() ? null : sha.trim();
    }

    static BacktestRunVo toVo(BacktestRunEntity entity) {
        return new BacktestRunVo(
                entity.getId(),
                entity.getRunKey(),
                entity.getRunName(),
                entity.getRunStatus(),
                entity.getRunStage(),
                entity.getOperatorUsername(),
                entity.getDateFrom(),
                entity.getDateTo(),
                entity.getLeagueNames(),
                entity.getDecisionLeadMinutes(),
                entity.getParameterSets(),
                entity.getProviderCode(),
                entity.getModelVersion(),
                entity.getSelectorVersion(),
                entity.getAggregationVersion(),
                entity.getBuilderVersion(),
                entity.getGitSha(),
                entity.getTotalMatchCount(),
                entity.getProcessedMatchCount(),
                entity.getArchiveReusedCount(),
                entity.getArchiveFetchedCount(),
                entity.getArchiveFailedCount(),
                entity.getCaseCount(),
                entity.getEstimateQuotaCost(),
                entity.getQuotaCost(),
                entity.getCancelRequested(),
                entity.getResumeCount(),
                entity.getErrorCode(),
                entity.getErrorMessage(),
                entity.getCreatedAt(),
                entity.getStartedAt(),
                entity.getFinishedAt(),
                entity.getLastHeartbeatAt(),
                BacktestRunVo.ratio(entity.getProcessedMatchCount(), entity.getTotalMatchCount())
        );
    }

    /** 校验产物：候选场次与决策截面。 */
    private record Prepared(List<BacktestCandidateDto> candidates, int decisionLeadMinutes) {
    }
}
