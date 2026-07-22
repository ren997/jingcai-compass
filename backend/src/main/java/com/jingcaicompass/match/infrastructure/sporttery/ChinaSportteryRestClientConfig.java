package com.jingcaicompass.match.infrastructure.sporttery;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.sporttery.provider", havingValue = "china", matchIfMissing = true)
public class ChinaSportteryRestClientConfig {

    @Bean
    @Qualifier("chinaSportteryRestClient")
    RestClient chinaSportteryRestClient(
            RestClient.Builder restClientBuilder,
            SportteryProviderProperties properties
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
                .defaultHeader(HttpHeaders.ACCEPT, "application/json, text/plain, */*")
                .defaultHeader(HttpHeaders.REFERER, "https://www.sporttery.cn/jc/jsq/zqspf/")
                .defaultHeader(HttpHeaders.USER_AGENT, "Mozilla/5.0 JingCai-Compass/0.1")
                .build();
    }
}
