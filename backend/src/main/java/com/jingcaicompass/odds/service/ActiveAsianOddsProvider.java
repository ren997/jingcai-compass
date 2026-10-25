package com.jingcaicompass.odds.service;

/**
 * 当前生效的亚盘数据来源编码，供查询侧按 {@code provider_code} 隔离样本。
 *
 * <p>为什么需要一个显式对象而不是让每个调用方自己去读配置：快照表里会长期并存多个来源的行
 * （历史 STUB、单源改造期间的过渡数据、新 provider），而读取方此刻应当只看见<b>当前在产</b>
 * 的那一份。把「当前是谁」收敛成一个注入点，换源时只改这里，不会漏掉某个查询。</p>
 *
 * <p>刻意做成不可变的简单记录：它只是一次配置快照，不需要生命周期，也方便测试直接构造。</p>
 *
 * @param providerCode 与 {@code asian_odds_snapshots.provider_code} 同口径（如 {@code NOVA_API}）
 */
public record ActiveAsianOddsProvider(String providerCode) {
}
