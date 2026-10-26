package com.jingcaicompass.odds.service;

import java.util.Locale;
import java.util.Map;

/**
 * NovaAPI 的中文庄家名 → 稳定 ASCII 编码。
 *
 * <p>低抽水白名单（{@code pinnacle,matchbook,betfair}）按<b>编码</b>匹配（见
 * {@link com.jingcaicompass.odds.client.AsianOddsAggregationProperties#normalizedWhitelist()}），
 * 中文名永远不命中并静默回退全量中位数——不是报错，是「看起来照常工作但置信度口径悄悄降级」。
 * 未登记的庄家回退为小写原文，保证不会误命中白名单。</p>
 *
 * <p>映射表只此一份：实时同步（写快照）与历史回测（还原快照）若各写一份，白名单命中集合会静默分叉。</p>
 */
public final class BookmakerCodes {

    private static final Map<String, String> CODES = Map.of(
            "平博", "pinnacle",
            "必发", "betfair",
            "威廉希尔", "williamhill",
            "Bet365", "bet365",
            "1xBet", "1xbet",
            "韦德", "betvictor",
            "利记", "liji"
    );

    private BookmakerCodes() {
    }

    /**
     * 归一化庄家名。
     *
     * @param providerName 供应商侧庄家名，允许带首尾空白
     * @return 稳定编码；未登记时返回去空白后的小写原文
     */
    public static String of(String providerName) {
        if (providerName == null) {
            return "";
        }
        String trimmed = providerName.trim();
        return CODES.getOrDefault(trimmed, trimmed.toLowerCase(Locale.ROOT));
    }
}
