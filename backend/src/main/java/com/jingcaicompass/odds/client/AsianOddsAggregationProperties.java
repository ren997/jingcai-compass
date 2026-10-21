package com.jingcaicompass.odds.client;

import jakarta.validation.constraints.Min;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 亚盘跨庄聚合范围：低抽水白名单与报价家数门槛。
 *
 * <p>白名单用于还原「主盘定价」，因为实测抽水与报价家数呈反相关：只报主盘的低抽水庄被大量
 * 全档报价的高抽水庄稀释，全量中位数会系统性高估抽水。白名单按线判定，家数不足时回退全量。</p>
 */
@Validated
@ConfigurationProperties("app.asian-odds.aggregation")
public record AsianOddsAggregationProperties(
        List<String> bookmakerWhitelist,
        @Min(2) Integer minBookmakers,
        @Min(2) Integer minWhitelistBookmakers
) {

    /** 默认低抽水白名单，与设计文档 §4.5.1 的实测结论一致。 */
    public static final List<String> DEFAULT_BOOKMAKER_WHITELIST = List.of("pinnacle", "matchbook", "betfair");

    /** 单条盘口线参与聚合的最少报价家数；低于该值的线不具备共识资格。 */
    public static final int DEFAULT_MIN_BOOKMAKERS = 2;

    /** 单条盘口线启用白名单样本所需的最少白名单家数。 */
    public static final int DEFAULT_MIN_WHITELIST_BOOKMAKERS = 2;

    public AsianOddsAggregationProperties {
        bookmakerWhitelist = bookmakerWhitelist == null
                ? DEFAULT_BOOKMAKER_WHITELIST
                : List.copyOf(bookmakerWhitelist);
        minBookmakers = minBookmakers == null ? DEFAULT_MIN_BOOKMAKERS : minBookmakers;
        minWhitelistBookmakers = minWhitelistBookmakers == null
                ? DEFAULT_MIN_WHITELIST_BOOKMAKERS
                : minWhitelistBookmakers;
    }

    /** 未配置时的默认值，供单元测试与非 Spring 装配路径使用。 */
    public static AsianOddsAggregationProperties defaults() {
        return new AsianOddsAggregationProperties(
                DEFAULT_BOOKMAKER_WHITELIST, DEFAULT_MIN_BOOKMAKERS, DEFAULT_MIN_WHITELIST_BOOKMAKERS);
    }

    /**
     * 白名单统一小写去空白。
     *
     * <p>不同数据源给出的庄家编码大小写与空白不一致，未归一化会导致白名单永远不命中并静默回退
     * 到全量中位数，因此比较前必须归一。</p>
     */
    public Set<String> normalizedWhitelist() {
        Set<String> normalized = new LinkedHashSet<>();
        for (String code : bookmakerWhitelist) {
            if (code != null && !code.isBlank()) {
                normalized.add(code.trim().toLowerCase(Locale.ROOT));
            }
        }
        return Collections.unmodifiableSet(normalized);
    }

    @Override
    public String toString() {
        return "AsianOddsAggregationProperties["
                + "bookmakerWhitelist=" + bookmakerWhitelist
                + ", minBookmakers=" + minBookmakers
                + ", minWhitelistBookmakers=" + minWhitelistBookmakers
                + "]";
    }
}
