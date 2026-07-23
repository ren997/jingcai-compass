package com.jingcaicompass.odds.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * The Odds API RestClient 超时与鉴权头装配；业务映射留待后续任务。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.asian-odds.provider", havingValue = "THE_ODDS_API")
public class AsianOddsRestClientConfig {

    public static final String ASIAN_ODDS_REST_CLIENT = "asianOddsRestClient";

    @Bean
    @Qualifier(ASIAN_ODDS_REST_CLIENT)
    RestClient asianOddsRestClient(
            RestClient.Builder restClientBuilder,
            AsianOddsProviderProperties properties
    ) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        RestClient.Builder builder = restClientBuilder
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.ACCEPT, "application/json");
        if (StringUtils.hasText(properties.apiKey())) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey());
        }
        return builder.build();
    }
}
