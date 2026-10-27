package com.jingcaicompass.admin.service;

import com.jingcaicompass.admin.dto.AdminSportteryPoolBackfillDto;
import com.jingcaicompass.admin.vo.AdminSportteryPoolBackfillVo;
import com.jingcaicompass.data.entity.DataSyncRun;
import com.jingcaicompass.match.dto.SportteryPoolSyncRequestDto;
import com.jingcaicompass.match.dto.SportteryPoolSyncResultDto;
import com.jingcaicompass.match.service.SportteryPoolSyncService;
import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 赛程池按日期段回填：逐日串行调用生产同步实现，每天一条 {@code sync_runs} 记录。
 *
 * <p>四个设计取舍，都是踩过或明确预判过的：</p>
 * <ul>
 *   <li><b>不复刻拉取逻辑</b>：直接调 {@link SportteryPoolSyncService}，队伍/联赛解析、
 *       幂等 upsert、跨零点场次的业务日归属全部复用同一份实现。手工写库会让
 *       {@code external_match_id} 的写法分叉，而回测候选条件正是靠它。</li>
 *   <li><b>逐日串行，不并发</b>：{@code NovaApiClient} 内部按 2s/次匀速放行，
 *       并发只会把请求堆到限速队列里，还让 429 落到重试上白白多扣额度。</li>
 *   <li><b>单次跨度 ≤ 7 天</b>：赛程接口只接受单日参数，7 天 ≈ 14s 上游耗时，
 *       仍在浏览器与网关的等待窗口内。放行 90 天会让请求必然超时，而超时后
 *       服务端还在继续扣额度——操作者却以为失败了。</li>
 *   <li><b>某天失败不中断</b>：一天失败（上游抖动/单日结构异常）不该让整段重跑。
 *       失败日单列原因，其余日照常写入；赛程是幂等 upsert，补跑那次不会产生重复行。</li>
 * </ul>
 *
 * <p><b>依赖用 {@link ObjectProvider} 惰性取，且类上不写 {@code @ConditionalOnBean}。</b>
 * 两个坑叠在一起：①该注解在组件扫描阶段按同包字母序求值，本类名排在
 * {@code AdminSportteryResultSyncServiceImpl} 之前，扫描到它时自动配置的 DataSource
 * 还没注册，于是 bean 被静默跳过、Controller 直接「No qualifying bean」启动失败；
 * ②测试 profile 排除了 {@code DataSourceAutoConfiguration}，无条件创建又会因为
 * {@code SportteryPoolSyncService} 不存在而让整个 ApplicationContext 起不来。
 * 惰性取同时满足两边：无库时端点存在但返回 {@code DATA_SOURCE_UNAVAILABLE}，
 * 与 {@code NoPersistenceAdminAutoConfiguration} 的降级语义一致。</p>
 */
@Service
public class AdminSportteryPoolBackfillServiceImpl implements AdminSportteryPoolBackfillService {

    private static final Logger log = LoggerFactory.getLogger(AdminSportteryPoolBackfillServiceImpl.class);

    /** 单次回合的天数上限。见类注释：不是上游限制，是等待窗口限制。 */
    private static final int MAX_DAYS = 7;

    private final ObjectProvider<SportteryPoolSyncService> sportteryPoolSyncServiceProvider;

    public AdminSportteryPoolBackfillServiceImpl(
            ObjectProvider<SportteryPoolSyncService> sportteryPoolSyncServiceProvider
    ) {
        this.sportteryPoolSyncServiceProvider = sportteryPoolSyncServiceProvider;
    }

    @Override
    public AdminSportteryPoolBackfillVo backfill(AdminSportteryPoolBackfillDto request) {
        DateRange range = resolveRange(request);
        SportteryPoolSyncService syncService = sportteryPoolSyncServiceProvider.getIfAvailable();
        if (syncService == null) {
            throw new BusinessException(ErrorCode.DATA_SOURCE_UNAVAILABLE);
        }
        List<AdminSportteryPoolBackfillVo.DayResult> days = new ArrayList<>();
        int successDays = 0;
        int failedDays = 0;
        int upsertTotal = 0;
        int snapshotTotal = 0;
        int quotaTotal = 0;

        for (LocalDate date = range.startDate(); !date.isAfter(range.endDate()); date = date.plusDays(1)) {
            try {
                SportteryPoolSyncResultDto result = syncService.sync(
                        new SportteryPoolSyncRequestDto(date)
                );
                DataSyncRun run = result.outcome().syncRun();
                int quota = value(run.getQuotaCost());
                upsertTotal += result.matchUpsertCount();
                snapshotTotal += result.snapshotInsertCount();
                quotaTotal += quota;
                successDays++;
                days.add(new AdminSportteryPoolBackfillVo.DayResult(
                        date, true, result.matchUpsertCount(), result.snapshotInsertCount(),
                        quota, run.getErrorMessage()
                ));
            } catch (RuntimeException exception) {
                // 捕获到 Exception 边界为止：一天失败要继续跑后面几天，否则「补一个季度」
                // 会退化成「每天点一次、坏一天全停」。
                failedDays++;
                days.add(new AdminSportteryPoolBackfillVo.DayResult(
                        date, false, 0, 0, 0, summarize(exception)
                ));
                log.warn("赛程回填单日失败 date={} reason={}", date, summarize(exception));
            }
        }

        log.info("赛程回填完成 {}~{} 成功 {} 天 失败 {} 天 落库 {} 场 额度 {}",
                range.startDate(), range.endDate(), successDays, failedDays, upsertTotal, quotaTotal);

        return new AdminSportteryPoolBackfillVo(
                range.startDate(),
                range.endDate(),
                days.size(),
                successDays,
                failedDays,
                upsertTotal,
                snapshotTotal,
                quotaTotal,
                List.copyOf(days),
                failedDays == 0 ? null : "%d 天失败，逐日原因见 days".formatted(failedDays)
        );
    }

    private DateRange resolveRange(AdminSportteryPoolBackfillDto request) {
        LocalDate start = request == null ? null : request.startDate();
        LocalDate end = request == null ? null : request.endDate();
        if (start == null || end == null) {
            throw new IllegalArgumentException("startDate 和 endDate 必须同时提供");
        }
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("startDate 不得晚于 endDate");
        }
        long span = ChronoUnit.DAYS.between(start, end) + 1;
        if (span > MAX_DAYS) {
            throw new IllegalArgumentException(
                    "赛程回填最多连续 %d 天，实际 %d 天：%s~%s".formatted(MAX_DAYS, span, start, end)
            );
        }
        // 不限制未来日期：赛程本身就是前瞻数据，定时任务每天拉的就是明天之后的场次。
        return new DateRange(start, end);
    }

    private String summarize(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }

    private int value(Integer number) {
        return number == null ? 0 : number;
    }

    private record DateRange(LocalDate startDate, LocalDate endDate) {
    }
}
