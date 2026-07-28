package com.jingcaicompass.home.vo;

import java.time.Instant;

/** 当天体彩池最近采集时刻及相对汇总生成时间的数据年龄。 */
public record HomeDataFreshnessVo(
        Instant sportteryLastCapturedAt,
        Long sportteryDataAgeSeconds
) {
}
