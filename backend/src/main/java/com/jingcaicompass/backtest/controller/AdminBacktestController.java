package com.jingcaicompass.backtest.controller;

import com.jingcaicompass.backtest.dto.BacktestCaseQueryDto;
import com.jingcaicompass.backtest.dto.BacktestRunCreateDto;
import com.jingcaicompass.backtest.dto.BacktestRunListQueryDto;
import com.jingcaicompass.backtest.service.BacktestMetricsService;
import com.jingcaicompass.backtest.service.BacktestRunService;
import com.jingcaicompass.backtest.vo.BacktestCaseVo;
import com.jingcaicompass.backtest.vo.BacktestComparisonVo;
import com.jingcaicompass.backtest.vo.BacktestEstimateVo;
import com.jingcaicompass.backtest.vo.BacktestLeagueVo;
import com.jingcaicompass.backtest.vo.BacktestMetricsVo;
import com.jingcaicompass.backtest.vo.BacktestRunVo;
import com.jingcaicompass.system.api.ApiResponse;
import com.jingcaicompass.system.api.PageResult;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 受管理员 JWT 保护的历史回测接口。
 *
 * <p>{@code SecurityConfig} 不需要为新路径加规则：{@code /api/admin/**} 已要求 ADMIN 角色。
 * 这一点是有意的——回测的控制面（创建、取消）与观察面（指标、对比）都在同一个前缀下，
 * 权限模型不需要为回测单独开分支。</p>
 */
@RestController
@RequestMapping("/api/admin/backtest")
public class AdminBacktestController {

    private final ObjectProvider<BacktestRunService> runServiceProvider;
    private final ObjectProvider<BacktestMetricsService> metricsServiceProvider;

    public AdminBacktestController(
            ObjectProvider<BacktestRunService> runServiceProvider,
            ObjectProvider<BacktestMetricsService> metricsServiceProvider
    ) {
        this.runServiceProvider = runServiceProvider;
        this.metricsServiceProvider = metricsServiceProvider;
    }

    /**
     * 取服务实例。
     *
     * <p>用 {@code ObjectProvider} 而不是构造注入：这两个服务都带
     * {@code @ConditionalOnBean(DataSource)}，而无数据源的上下文（{@code @SpringBootTest} 里
     * 排除了 {@code DataSourceAutoConfiguration}）会让它们不存在。直接注入会让整个上下文
     * 起不来——一个可选功能的依赖把主流程拖死，代价远大于这里的两个方法。</p>
     */
    private BacktestRunService runService() {
        BacktestRunService service = runServiceProvider.getIfAvailable();
        if (service == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "回测功能需要数据库支持，当前上下文未启用");
        }
        return service;
    }

    private BacktestMetricsService metricsService() {
        BacktestMetricsService service = metricsServiceProvider.getIfAvailable();
        if (service == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "回测功能需要数据库支持，当前上下文未启用");
        }
        return service;
    }

    /** 创建前的成本预估；创建必须能先看到这个结果。 */
    @PostMapping("/runs/estimate")
    public ApiResponse<BacktestEstimateVo> estimate(@Valid @RequestBody BacktestRunCreateDto request) {
        return ApiResponse.success(runService().estimate(request));
    }

    /** 入队一次回测。 */
    @PostMapping("/runs")
    public ApiResponse<BacktestRunVo> create(
            @Valid @RequestBody BacktestRunCreateDto request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(runService().create(request, requireUsername(jwt)));
    }

    /** 分页列表。 */
    @PostMapping("/runs/list")
    public ApiResponse<PageResult<BacktestRunVo>> list(
            @RequestBody(required = false) BacktestRunListQueryDto query
    ) {
        return ApiResponse.success(runService().list(query));
    }

    /** 详情（含进度）。 */
    @GetMapping("/runs/{runId}")
    public ApiResponse<BacktestRunVo> detail(@PathVariable Long runId) {
        return ApiResponse.success(runService().detail(runId));
    }

    /**
     * 进度轮询端点。
     *
     * <p>与详情返回同一个对象：前端 3s 轮询时只关心计数字段，多返回几十个字段的代价
     * 远小于维护两个几乎相同的映射代码——两份映射一定会有一份忘了补字段。</p>
     */
    @GetMapping("/runs/{runId}/progress")
    public ApiResponse<BacktestRunVo> progress(@PathVariable Long runId) {
        return ApiResponse.success(runService().detail(runId));
    }

    /** 请求取消。只置标志，已写样本保留。 */
    @PostMapping("/runs/{runId}/cancel")
    public ApiResponse<BacktestRunVo> cancel(@PathVariable Long runId) {
        return ApiResponse.success(runService().cancel(runId));
    }

    /** 续跑中断的 run。 */
    @PostMapping("/runs/{runId}/resume")
    public ApiResponse<BacktestRunVo> resume(@PathVariable Long runId) {
        return ApiResponse.success(runService().resume(runId));
    }

    /** 逐场明细分页。 */
    @PostMapping("/runs/{runId}/cases")
    public ApiResponse<PageResult<BacktestCaseVo>> cases(
            @PathVariable Long runId,
            @RequestBody(required = false) BacktestCaseQueryDto query
    ) {
        return ApiResponse.success(runService().cases(runId, query));
    }

    /** 全部参数组 × 口径的指标。 */
    @GetMapping("/runs/{runId}/metrics")
    public ApiResponse<List<BacktestMetricsVo>> metrics(@PathVariable Long runId) {
        return ApiResponse.success(metricsService().compare(runId).metrics());
    }

    /** 参数组对比（含最优组判定与理由）。 */
    @GetMapping("/runs/{runId}/compare")
    public ApiResponse<BacktestComparisonVo> compare(@PathVariable Long runId) {
        return ApiResponse.success(metricsService().compare(runId));
    }

    /** 联赛候选。 */
    @PostMapping("/leagues")
    public ApiResponse<List<BacktestLeagueVo>> leagues(
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo
    ) {
        return ApiResponse.success(runService().leagues(parseDate(dateFrom), parseDate(dateTo)));
    }

    private static LocalDate parseDate(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "日期格式应为 yyyy-MM-dd：" + value);
        }
    }

    private static String requireUsername(Jwt jwt) {
        if (jwt == null || !StringUtils.hasText(jwt.getClaimAsString("username"))) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
        }
        return jwt.getClaimAsString("username");
    }
}
