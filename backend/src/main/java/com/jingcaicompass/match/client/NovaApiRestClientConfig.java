package com.jingcaicompass.match.client;

import java.net.http.HttpClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * NovaAPI 的 RestClient。仅在 {@code app.sporttery.provider=nova} 时装配。
 *
 * <p>与 {@code ChinaSportteryRestClientConfig} 结构一致，差别是不再设置 Referer
 * （NovaAPI 走签名鉴权，不校验来源页），且读超时更长：单次 {@code /nova/tf/odds}
 * 要返回几十家机构的全部玩法，比体彩单个比赛池接口重得多。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.sporttery.provider", havingValue = "nova")
public class NovaApiRestClientConfig {

    @Bean
    @Qualifier("novaApiRestClient")
    RestClient novaApiRestClient(
            RestClient.Builder restClientBuilder,
            NovaApiProperties properties
    ) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        return restClientBuilder
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .defaultHeader(HttpHeaders.USER_AGENT, "JingCai-Compass/0.1")
                .build();
    }
}
