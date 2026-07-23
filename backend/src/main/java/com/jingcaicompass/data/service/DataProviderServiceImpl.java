package com.jingcaicompass.data.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jingcaicompass.data.entity.DataProvider;
import com.jingcaicompass.data.mapper.DataProviderMapper;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnBean(DataSource.class)
public class DataProviderServiceImpl extends ServiceImpl<DataProviderMapper, DataProvider>
        implements DataProviderService {

    @Override
    public Optional<DataProvider> findByProviderCode(String providerCode) {
        return Optional.ofNullable(lambdaQuery()
                .eq(DataProvider::getProviderCode, providerCode)
                .one());
    }
}
