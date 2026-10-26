package com.jingcaicompass.match.client;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * NovaAPI 的 RestClient。赛程或赔率任一使用 NovaAPI 时装配（见 {@link NovaApiEnabledCondition}）。
 *
 * <p>与 {@code ChinaSportteryRestClientConfig} 结构一致，差别是不再设置 Referer
 * （NovaAPI 走签名鉴权，不校验来源页），且读超时更长：单次 {@code /nova/tf/odds}
 * 要返回几十家机构的全部玩法，比体彩单个比赛池接口重得多。</p>
 */
@Configuration(proxyBeanMethods = false)
@Conditional(NovaApiEnabledCondition.class)
public class NovaApiRestClientConfig {

    @Bean
    @Qualifier("novaApiRestClient")
    RestClient novaApiRestClient(
            RestClient.Builder restClientBuilder,
            NovaApiProperties properties
    ) {
        return buildClient(restClientBuilder, properties, properties.readTimeout());
    }

    /**
     * 历史赔率专用客户端：读超时更长。
     *
     * <p>单独的 bean 而不是给同一个客户端加 per-request 超时，是因为 {@code JdkClientHttpRequestFactory}
     * 的读超时是<b>工厂级</b>的，Spring 6.1 的 {@code RestClient} 没有 per-call 覆盖入口。
     * 反向也考虑过——把全局 {@code read-timeout} 调到 60s：那会让实时同步的卡死检测一起变钝，
     * 而实时接口实测都在 1~2s 内返回，20s 已经是很宽的容忍。</p>
     */
    @Bean
    @Qualifier("novaApiHistoryRestClient")
    RestClient novaApiHistoryRestClient(
            RestClient.Builder restClientBuilder,
            NovaApiProperties properties
    ) {
        return buildClient(restClientBuilder, properties, properties.effectiveHistoryReadTimeout());
    }

    private RestClient buildClient(
            RestClient.Builder restClientBuilder,
            NovaApiProperties properties,
            Duration readTimeout
    ) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);

        return restClientBuilder
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .defaultHeader(HttpHeaders.USER_AGENT, "JingCai-Compass/0.1")
                .build();
    }
}
