package com.jingcaicompass.backtest.controller;

import com.jingcaicompass.backtest.dto.BacktestCaseQueryDto;
import com.jingcaicompass.backtest.dto.BacktestRunListQueryDto;
import com.jingcaicompass.backtest.enums.BacktestRunStatusEnum;
import com.jingcaicompass.backtest.service.BacktestMetricsService;
import com.jingcaicompass.backtest.service.BacktestRunService;
import com.jingcaicompass.backtest.vo.BacktestCaseVo;
import com.jingcaicompass.backtest.vo.BacktestComparisonVo;
import com.jingcaicompass.backtest.vo.BacktestMetricsVo;
import com.jingcaicompass.backtest.vo.BacktestRunVo;
import com.jingcaicompass.system.api.ApiResponse;
import com.jingcaicompass.system.api.PageResult;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开的回测结果只读接口。
 *
 * <p><b>只暴露 {@code SUCCEEDED} 与 {@code PARTIAL}</b>：其余状态（排队、补采中、失败、取消、中断）
 * 的 run 要么没有完整样本、要么口径还没定型，把它们放出去等于让人读一个还在变的结论。
 * 这是「公开只读」与「管理端全量」的核心差别，不能靠前端隐藏来保证。</p>
 *
 * <p>不返回原始载荷，也不返回 {@code gitSha}：前者体积大且含第三方机构名，后者是部署内部信息。</p>
 */
@RestController
@RequestMapping("/api/public/backtest")
public class PublicBacktestController {

    private final ObjectProvider<BacktestRunService> runServiceProvider;
    private final ObjectProvider<BacktestMetricsService> metricsServiceProvider;

    public PublicBacktestController(
            ObjectProvider<BacktestRunService> runServiceProvider,
            ObjectProvider<BacktestMetricsService> metricsServiceProvider
    ) {
        this.runServiceProvider = runServiceProvider;
        this.metricsServiceProvider = metricsServiceProvider;
    }

    /** 无数据源的上下文里视为不存在：公开接口不该在缺库时抛 500。 */
    private BacktestRunService runService() {
        BacktestRunService service = runServiceProvider.getIfAvailable();
        if (service == null) {
            throw new BusinessException(ErrorCode.BACKTEST_RUN_NOT_FOUND, "回测运行不存在");
        }
        return service;
    }

    private BacktestMetricsService metricsService() {
        BacktestMetricsService service = metricsServiceProvider.getIfAvailable();
        if (service == null) {
            throw new BusinessException(ErrorCode.BACKTEST_RUN_NOT_FOUND, "回测运行不存在");
        }
        return service;
    }

    /** 分页列表，只含已产出结论的 run。 */
    @PostMapping("/runs/list")
    public ApiResponse<PageResult<BacktestRunVo>> list(
            @RequestBody(required = false) BacktestRunListQueryDto query
    ) {
        // 状态白名单下到 SQL：在内存里过滤会让 total 与分页游标都对不上。
        return ApiResponse.success(runService().list(query, PUBLISHABLE_STATUSES));
    }

    /** 详情；未产出结论的 run 一律 404，不透露它存在。 */
    @PostMapping("/runs/detail")
    public ApiResponse<BacktestRunVo> detail(@RequestBody BacktestRunIdDto query) {
        return ApiResponse.success(requirePublishable(query == null ? null : query.runId()));
    }

    /** 指标。 */
    @PostMapping("/metrics")
    public ApiResponse<List<BacktestMetricsVo>> metrics(@RequestBody BacktestRunIdDto query) {
        BacktestRunVo run = requirePublishable(query == null ? null : query.runId());
        return ApiResponse.success(metricsService().compare(run.id()).metrics());
    }

    /** 参数组对比。 */
    @PostMapping("/compare")
    public ApiResponse<BacktestComparisonVo> compare(@RequestBody BacktestRunIdDto query) {
        BacktestRunVo run = requirePublishable(query == null ? null : query.runId());
        return ApiResponse.success(metricsService().compare(run.id()));
    }

    /** 逐场明细。 */
    @PostMapping("/cases")
    public ApiResponse<PageResult<BacktestCaseVo>> cases(@RequestBody PublicBacktestCaseQueryDto query) {
        BacktestRunVo run = requirePublishable(query == null ? null : query.runId());
        return ApiResponse.success(runService().cases(run.id(), query == null ? null : query.toCaseQuery()));
    }

    private BacktestRunVo requirePublishable(Long runId) {
        if (runId == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "runId 不能为空");
        }
        BacktestRunVo run = runService().detail(runId);
        if (!publishable(run)) {
            // 用「不存在」而不是「未完成」：公开接口不该成为「某次实验正在跑」的观测探针。
            throw new BusinessException(ErrorCode.BACKTEST_RUN_NOT_FOUND, "回测运行不存在：" + runId);
        }
        return run;
    }

    /** 公开可见的状态集合。任何新增状态都要显式决定是否公开，默认不公开。 */
    private static final java.util.List<BacktestRunStatusEnum> PUBLISHABLE_STATUSES =
            java.util.List.of(BacktestRunStatusEnum.SUCCEEDED, BacktestRunStatusEnum.PARTIAL);

    private static boolean publishable(BacktestRunVo run) {
        return run.runStatus() == BacktestRunStatusEnum.SUCCEEDED
                || run.runStatus() == BacktestRunStatusEnum.PARTIAL;
    }

    /** 只带 id 的请求体。 */
    public record BacktestRunIdDto(Long runId) {
    }

    /** 明细查询（自带 runId）。 */
    public record PublicBacktestCaseQueryDto(
            Long runId,
            String groupKey,
            String marketScope,
            String skipReason,
            Boolean onlyPicked,
            Integer page,
            Integer pageSize
    ) {

        BacktestCaseQueryDto toCaseQuery() {
            return new BacktestCaseQueryDto(groupKey, marketScope, skipReason, onlyPicked, page, pageSize);
        }
    }
}
