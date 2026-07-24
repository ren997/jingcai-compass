package com.jingcaicompass.match.service;

import com.jingcaicompass.data.dto.ProviderFetchResult;
import com.jingcaicompass.match.dto.SportteryMatchDto;
import com.jingcaicompass.match.dto.SportteryMatchResultDto;
import java.time.LocalDate;
import java.util.List;

/**
 * 体彩数据源契约：日赛列表、比赛池 raw、赛果拉取。
 * 实现位于 {@code match.client}（真实/Stub）。
 */
public interface SportteryProvider {

    /** Provider 业务编码，写入同步运行与摘要 VO。 */
    String providerCode();

    /** 查询指定竞彩业务日的比赛列表（查询链路用）。 */
    List<SportteryMatchDto> findDailyMatches(LocalDate lotteryDate);

    /**
     * 拉取比赛池原始响应，供同步模板写入 raw payload。
     */
    ProviderFetchResult fetchMatchPoolRaw(LocalDate lotteryDate);

    /**
     * 按竞彩销售日区间拉取官方赛果；真实体彩适配在赛果任务前可返回空列表。
     */
    List<SportteryMatchResultDto> fetchMatchResults(LocalDate startDate, LocalDate endDate);
}
