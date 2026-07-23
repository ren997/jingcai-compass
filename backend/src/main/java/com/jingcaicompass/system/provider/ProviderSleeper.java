package com.jingcaicompass.system.provider;

import java.time.Duration;

/**
 * 可注入的休眠接口，便于测试跳过真实等待。
 */
@FunctionalInterface
public interface ProviderSleeper {

    void sleep(Duration duration) throws InterruptedException;
}
