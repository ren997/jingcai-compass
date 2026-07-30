package com.jingcaicompass.match.service;

import com.jingcaicompass.system.exception.BusinessException;
import com.jingcaicompass.system.exception.ErrorCode;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** 在单个应用进程内串行化手动与定时的体彩赛果同步。 */
@Component
public class MatchResultSyncCoordinator {

    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 仅一个赛果拉取可执行；完成后总会释放占用。 */
    public <T> T execute(Supplier<T> action) {
        if (!running.compareAndSet(false, true)) {
            throw new BusinessException(ErrorCode.SYNC_IN_PROGRESS);
        }
        try {
            return action.get();
        } finally {
            running.set(false);
        }
    }
}
