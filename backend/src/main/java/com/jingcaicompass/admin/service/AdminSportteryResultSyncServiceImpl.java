package com.jingcaicompass.admin.service;

import com.jingcaicompass.admin.dto.AdminSportteryResultSyncDto;
import com.jingcaicompass.admin.vo.AdminSportteryResultSyncVo;
import com.jingcaicompass.match.dto.MatchResultSyncRequestDto;
import com.jingcaicompass.match.dto.MatchResultSyncResultDto;
import com.jingcaicompass.match.service.MatchResultSyncService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

/** 校验人工请求窗口并委派赛果同步，不触发结算。 */
@Service
@ConditionalOnBean(DataSource.class)
public class AdminSportteryResultSyncServiceImpl implements AdminSportteryResultSyncService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final int MAX_DAYS = 7;

    private final MatchResultSyncService matchResultSyncService;
    private final Clock clock;

    public AdminSportteryResultSyncServiceImpl(
            MatchResultSyncService matchResultSyncService
    ) {
        this(matchResultSyncService, Clock.system(SHANGHAI));
    }

    AdminSportteryResultSyncServiceImpl(
            MatchResultSyncService matchResultSyncService, Clock clock
    ) {
        this.matchResultSyncService = matchResultSyncService;
        this.clock = clock;
    }

    @Override
    public AdminSportteryResultSyncVo sync(AdminSportteryResultSyncDto request) {
        DateRange range = resolveRange(request);
        return toVo(range, matchResultSyncService.sync(
                new MatchResultSyncRequestDto(range.startDate(), range.endDate())
        ));
    }

    private DateRange resolveRange(AdminSportteryResultSyncDto request) {
        LocalDate start = request == null ? null : request.startDate();
        LocalDate end = request == null ? null : request.endDate();
        LocalDate today = LocalDate.now(clock.withZone(SHANGHAI));
        if (start == null && end == null) {
            LocalDate yesterday = today.minusDays(1);
            return new DateRange(yesterday, yesterday);
        }
        if (start == null || end == null) {
            throw new IllegalArgumentException("startDate 和 endDate 必须同时提供");
        }
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("startDate 不得晚于 endDate");
        }
        if (end.isAfter(today)) {
            throw new IllegalArgumentException("赛果同步不允许未来日期");
        }
        if (start.plusDays(MAX_DAYS - 1L).isBefore(end)) {
            throw new IllegalArgumentException("赛果同步最多连续 7 天");
        }
        return new DateRange(start, end);
    }

    private AdminSportteryResultSyncVo toVo(DateRange range, MatchResultSyncResultDto result) {
        var run = result.outcome().syncRun();
        return new AdminSportteryResultSyncVo(
                run.getId(), range.startDate(), range.endDate(), result.outcome().status(),
                value(run.getFetchedCount()), value(run.getSuccessCount()), value(run.getFailureCount()),
                value(run.getRetryCount()), value(run.getQuotaCost()), result.appendedFactCount(),
                result.supersededFactCount(), result.unchangedFactCount(), result.outcome().duplicatePayload(),
                run.getErrorMessage()
        );
    }

    private int value(Integer number) {
        return number == null ? 0 : number;
    }

    private record DateRange(LocalDate startDate, LocalDate endDate) {
    }
}
