package com.jingcaicompass.match.service;

import com.jingcaicompass.data.dto.ProviderFetchResult;
import com.jingcaicompass.match.dto.SportteryMatchDto;
import com.jingcaicompass.match.dto.SportteryMatchResultDto;
import com.jingcaicompass.match.dto.SportteryPoolSyncItemDto;
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
     * 将已归档的比赛池原始响应解析为同步条目。
     *
     * <p>解析必须留在 Provider 侧：不同数据源的包装结构不同（体彩是
     * {@code value.matchInfoList} 分组，NovaAPI 是 {@code data[].matchMain}），
     * 而写库路径应完全一致。把解析放进 Provider，同步服务就与具体数据源解耦，
     * 删除旧数据源时解析器也随之一起消失，不会留下跨源分发分支。</p>
     */
    List<SportteryPoolSyncItemDto> parseMatchPool(String rawJson, LocalDate businessDate);

    /**
     * 按竞彩销售日区间拉取官方赛果原始响应，供同步模板存档和解析。
     */
    ProviderFetchResult fetchMatchResultsRaw(LocalDate startDate, LocalDate endDate);

    /** 将已归档的供应商原始赛果响应解析为内部事实输入，绝不改写 raw。 */
    List<SportteryMatchResultDto> parseMatchResults(
            String rawJson,
            LocalDate startDate,
            LocalDate endDate
    );
}
