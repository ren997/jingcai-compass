package com.jingcaicompass.data.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DataEnumsTest {

    @Test
    void resolvesCodesForProviderAndSyncEnums() {
        assertThat(DataProviderCategoryEnum.fromCode("SPORTTERY")).isEqualTo(DataProviderCategoryEnum.SPORTTERY);
        assertThat(ProviderDataTypeEnum.fromCode("ASIAN_ODDS")).isEqualTo(ProviderDataTypeEnum.ASIAN_ODDS);
        assertThat(ParseStatusEnum.fromCode("FAILED")).isEqualTo(ParseStatusEnum.FAILED);
        assertThat(SyncStatusEnum.fromCode("PARTIAL")).isEqualTo(SyncStatusEnum.PARTIAL);
        assertThat(DataPipelineStatusEnum.fromCode("PARTIAL"))
                .isEqualTo(DataPipelineStatusEnum.PARTIAL);
        assertThat(ParseStatusEnum.SUCCESS.getDesc()).isEqualTo("解析成功");
        assertThat(DataPipelineStatusEnum.SUCCESS.getDesc()).isEqualTo("全部阶段成功");
    }
}
