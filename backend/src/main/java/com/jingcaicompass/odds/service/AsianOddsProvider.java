package com.jingcaicompass.odds.service;

import com.jingcaicompass.data.dto.ProviderFetchResult;
import com.jingcaicompass.odds.dto.AsianOddsLeagueDto;
import com.jingcaicompass.odds.dto.AsianOddsMatchOddsDto;
import com.jingcaicompass.odds.dto.AsianOddsQueryDto;

import java.util.List;

/**
 * 亚盘数据 Provider 契约；实现由 Stub / 真实适配器提供。
 */
public interface AsianOddsProvider {

    String providerCode();

    List<AsianOddsLeagueDto> fetchLeagues();

    List<AsianOddsMatchOddsDto> fetchPreMatchOdds(AsianOddsQueryDto query);

    /** 赛前盘口原始 JSON，供 ProviderSyncTemplate 幂等入库。 */
    ProviderFetchResult fetchPreMatchOddsRaw(AsianOddsQueryDto query);
}
