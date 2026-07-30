package com.jingcaicompass.match.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProviderEntityKeySupportTest {

    @Test
    void keepsSameTeamNameIsolatedAcrossTheOddsSportKeys() {
        String premierLeague = ProviderEntityKeySupport.scopedNameKey("soccer_epl", "United");
        String championsLeague = ProviderEntityKeySupport.scopedNameKey("soccer_uefa_champs_league", "United");

        assertThat(premierLeague).startsWith("SCOPED_NAME:");
        assertThat(championsLeague).startsWith("SCOPED_NAME:");
        assertThat(premierLeague).isNotEqualTo(championsLeague);
    }
}
