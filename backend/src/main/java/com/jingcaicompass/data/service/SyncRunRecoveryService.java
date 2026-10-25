package com.jingcaicompass.data.service;

import com.jingcaicompass.data.mapper.DataSyncRunMapper;
import java.time.Duration;
import java.time.Instant;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

/**
 * 把「进程退出时没来得及收尾」的同步运行记录补成 FAILED。
 *
 * <p>为什么必须有这一层：{@code data_sync_runs} 的收尾写在业务代码里，进程被强杀、OOM、
 * IDE 重启都不会执行 {@code finally}，那条记录就永久停在 RUNNING。管理端没有任何心跳列
 * 可以区分「真的在跑」和「上次崩了」，只能一直显示「运行中」——2026-10-25 的 5 条孤儿
 * （id 64/68/70/72/76）就是这样留下的。</p>
 *
 * <p>为什么不做任务级超时：Spring 的 {@code @Scheduled} 本身没有 timeout 属性，而在进程内
 * 用另一个线程「到点判失败」并不能真正中断正在阻塞的 socket 读或 JDBC 写——只会制造
 * 「记录已失败、任务还在写库」的更坏状态。启动时扫描是唯一没有这种副作用的收尾点：
 * 此刻上一个进程确已死亡，任何残留的 RUNNING 都是孤儿。</p>
 */
@Service
@ConditionalOnBean(DataSource.class)
public class SyncRunRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(SyncRunRecoveryService.class);

    /**
     * 早于该时长的 RUNNING 才视为孤儿。
     *
     * <p>单实例部署时启动扫到的必然都是孤儿，这个阈值只为多实例共存时放过「另一实例刚
     * 创建的行」。取 10 分钟：一轮亚盘全量同步实测 4~7 分钟，不会误伤。</p>
     */
    static final Duration STALE_AFTER = Duration.ofMinutes(10);

    static final String RECOVERED_REASON =
            "STALE_RUN_RECOVERED：进程退出时未收尾（强杀 / OOM / 重启），由启动恢复任务置为 FAILED，"
                    + "该轮实际执行结果未知";

    private final DataSyncRunMapper dataSyncRunMapper;

    public SyncRunRecoveryService(DataSyncRunMapper dataSyncRunMapper) {
        this.dataSyncRunMapper = dataSyncRunMapper;
    }

    /**
     * 收尾全部超时 RUNNING 记录。
     *
     * @return 被改为 FAILED 的行数；0 表示没有孤儿
     */
    public int recoverStaleRuns() {
        return recoverStaleRuns(Instant.now());
    }

    int recoverStaleRuns(Instant now) {
        Instant threshold = now.minus(STALE_AFTER);
        int recovered = dataSyncRunMapper.failStaleRunning(now, RECOVERED_REASON, threshold);
        if (recovered > 0) {
            log.warn("event=sync_run_stale_recovered count={} threshold={} reason={}",
                    recovered, threshold, RECOVERED_REASON);
        }
        return recovered;
    }
}
