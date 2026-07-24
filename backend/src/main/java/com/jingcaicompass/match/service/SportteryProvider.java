package com.jingcaicompass.match.service;

import com.jingcaicompass.data.dto.ProviderFetchResult;
import com.jingcaicompass.match.dto.SportteryMatchDto;
import com.jingcaicompass.match.dto.SportteryMatchResultDto;

import java.time.LocalDate;
import java.util.List;

public interface SportteryProvider {

    String providerCode();

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
