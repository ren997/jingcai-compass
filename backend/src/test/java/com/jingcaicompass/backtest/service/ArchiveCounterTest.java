package com.jingcaicompass.backtest.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.jingcaicompass.backtest.enums.BacktestArchiveStatusEnum;
import com.jingcaicompass.backtest.service.BacktestArchiveService.ArchiveOutcome;
import com.jingcaicompass.backtest.service.BacktestRunnerService.ArchiveCounter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 归档计数器的三桶不变量。
 *
 * <p>对应 DB 约束 {@code ck_backtest_runs_counts}：
 * {@code archive_reused_count + archive_fetched_count + archive_failed_count = processed_match_count}。
 * 这条等式不是风格问题——它一旦不成立，run 会在批末 flush 时抛
 * {@code DataIntegrityViolationException} 变成 FAILED，而现场已经归档好了几十场，
 * 看起来像「归档阶段崩了」。</p>
 *
 * <p>这个缺陷的隐蔽处在于**只在真有失败时出现**：全复用（run #4 的 10/10）与全成功的批次
 * 三个桶天然吻合，怎么跑都对。所以这里的用例必须包含「新采失败」这一支。</p>
 */
class ArchiveCounterTest {

    private static ArchiveOutcome reusedSuccess() {
        return new ArchiveOutcome(BacktestArchiveStatusEnum.SUCCESS, 1L, true, 0, null);
    }

    /** 负缓存复用：状态不是 SUCCESS，但仍然没有发请求。 */
    private static ArchiveOutcome reusedNegativeCache() {
        return new ArchiveOutcome(BacktestArchiveStatusEnum.NO_HISTORY, 2L, true, 0, "上游无记录");
    }

    private static ArchiveOutcome fetchedSuccess() {
        return new ArchiveOutcome(BacktestArchiveStatusEnum.SUCCESS, 3L, false, 1, null);
    }

    private static ArchiveOutcome fetchedFailure() {
        return new ArchiveOutcome(BacktestArchiveStatusEnum.RETRYABLE_FAILURE, null, false, 1, "网络抖动");
    }

    @Test
    @DisplayName("三个桶恰好划分 processed，而不是各自独立累加")
    void bucketsPartitionProcessed() {
        ArchiveCounter counter = new ArchiveCounter();
        counter.add(reusedSuccess());
        counter.add(reusedSuccess());
        counter.add(reusedNegativeCache());
        counter.add(fetchedSuccess());
        counter.add(fetchedSuccess());
        counter.add(fetchedSuccess());
        counter.add(fetchedFailure());

        assertThat(counter.processed()).isEqualTo(7);
        assertThat(counter.reused()).isEqualTo(3);
        assertThat(counter.fetched()).isEqualTo(3);
        assertThat(counter.failed()).isEqualTo(1);
        // 这条就是 DB 约束本身。
        assertThat(counter.reused() + counter.fetched() + counter.failed()).isEqualTo(counter.processed());
    }

    @Test
    @DisplayName("失败的补采只进 failed，不再同时进 fetched")
    void failedFetchIsNotCountedAsFetched() {
        ArchiveCounter counter = new ArchiveCounter();
        counter.add(fetchedFailure());

        // 回归断言：旧实现里这里是 fetched=1 且 failed=1，两者之和 2 > processed 1。
        assertThat(counter.processed()).isEqualTo(1);
        assertThat(counter.fetched()).isZero();
        assertThat(counter.failed()).isEqualTo(1);
    }

    @Test
    @DisplayName("负缓存复用算复用、不进 failed，但会被记为「无可用序列」")
    void negativeCacheReuseIsNotAFailure() {
        ArchiveCounter counter = new ArchiveCounter();
        counter.add(reusedNegativeCache());

        assertThat(counter.reused()).isEqualTo(1);
        assertThat(counter.failed()).isZero();
        // 终态靠它把「这批没有可用序列」判成 PARTIAL，而不是 SUCCEEDED。
        assertThat(counter.unavailable()).isEqualTo(1);
        assertThat(counter.reused() + counter.fetched() + counter.failed()).isEqualTo(counter.processed());
    }

    @Test
    @DisplayName("额度按场累加，与桶的归属无关")
    void quotaCountsEveryCall() {
        ArchiveCounter counter = new ArchiveCounter();
        counter.add(reusedSuccess());
        counter.add(fetchedSuccess());
        counter.add(fetchedFailure());

        // 复用不花钱；新采成功与新采失败都真的发了一次请求。
        assertThat(counter.quota()).isEqualTo(2);
    }

    @Test
    @DisplayName("清零后重新计一批，不把上一批的数字带过去")
    void resetStartsAFreshBatch() {
        ArchiveCounter counter = new ArchiveCounter();
        counter.add(fetchedSuccess());
        counter.add(reusedNegativeCache());
        counter.resetQuotaAndCounts();

        assertThat(counter.processed()).isZero();
        assertThat(counter.reused()).isZero();
        assertThat(counter.fetched()).isZero();
        assertThat(counter.failed()).isZero();
        assertThat(counter.unavailable()).isZero();
        assertThat(counter.quota()).isZero();
    }
}
