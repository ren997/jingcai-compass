package com.jingcaicompass.prediction.enums;

import static org.assertj.core.api.Assertions.assertThat;

import com.jingcaicompass.snapshot.enums.PredictionSnapshotStatusEnum;
import org.junit.jupiter.api.Test;

class PredictionEnumsTest {

    @Test
    void resolvesPredictionEnumsByPersistentCode() {
        assertThat(PredictionStatusEnum.fromCode("PUBLISHED"))
                .isEqualTo(PredictionStatusEnum.PUBLISHED);
        assertThat(ConfidenceLevelEnum.fromCode("HIGH"))
                .isEqualTo(ConfidenceLevelEnum.HIGH);
        assertThat(HandicapPickEnum.fromCode("AWAY_WIN"))
                .isEqualTo(HandicapPickEnum.AWAY_WIN);
        assertThat(PredictionSnapshotStatusEnum.fromCode("FAILED"))
                .isEqualTo(PredictionSnapshotStatusEnum.FAILED);
    }

    @Test
    void exposesReadableDescriptionsAndUnknownCodesAsNull() {
        assertThat(PredictionStatusEnum.LOCKED.getDesc()).isEqualTo("已锁定");
        assertThat(ConfidenceLevelEnum.MEDIUM.getDesc()).isEqualTo("中");
        assertThat(HandicapPickEnum.HOME_WIN.getDesc()).isEqualTo("主胜");
        assertThat(PredictionSnapshotStatusEnum.PUBLISHED.getDesc()).isEqualTo("已发布");
        assertThat(PredictionStatusEnum.fromCode("UNKNOWN")).isNull();
    }
}
