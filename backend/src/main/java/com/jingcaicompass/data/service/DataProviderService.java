package com.jingcaicompass.data.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.jingcaicompass.data.entity.DataProvider;
import java.util.Optional;

public interface DataProviderService extends IService<DataProvider> {

    Optional<DataProvider> findByProviderCode(String providerCode);
}
