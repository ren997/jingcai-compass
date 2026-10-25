package com.jingcaicompass.match.enums;

import static org.assertj.core.api.Assertions.assertThat;

import com.jingcaicompass.odds.enums.OddsSnapshotTypeEnum;
import org.junit.jupiter.api.Test;

class MatchStatusEnumsTest {

    @Test
    void resolvesMatchStatusAndSnapshotEnumCodes() {
        assertThat(MatchStatusEnum.fromCode("LOCKED")).isEqualTo(MatchStatusEnum.LOCKED);
        assertThat(MatchStatusEnum.fromCode("IN_PROGRESS")).isEqualTo(MatchStatusEnum.IN_PROGRESS);
        assertThat(MatchStatusEnum.fromCode("ABANDONED")).isEqualTo(MatchStatusEnum.ABANDONED);
        assertThat(OddsSnapshotTypeEnum.fromCode("PRE_KICKOFF"))
                .isEqualTo(OddsSnapshotTypeEnum.PRE_KICKOFF);
        assertThat(OddsSnapshotTypeEnum.FIRST_SEEN.getDesc()).isEqualTo("首次可见");
    }
}
