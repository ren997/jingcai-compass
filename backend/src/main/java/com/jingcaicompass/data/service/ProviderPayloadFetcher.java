package com.jingcaicompass.data.service;

import com.jingcaicompass.data.dto.ProviderFetchResult;

/**
 * Provider 拉取回调。
 */
@FunctionalInterface
public interface ProviderPayloadFetcher {

    ProviderFetchResult fetch();
}
