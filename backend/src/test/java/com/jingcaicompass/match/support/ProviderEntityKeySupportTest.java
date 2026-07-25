package com.jingcaicompass.match.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ProviderEntityKeySupportTest {

    @Test
    void equivalentNamesProduceSameBoundedKey() {
        String first = ProviderEntityKeySupport.nameKey(" Manchester  United ");
        String second = ProviderEntityKeySupport.nameKey("manchester united");

        assertThat(first).isEqualTo(second);
        assertThat(first).startsWith("NAME:");
        assertThat(first).hasSize(69);
    }

    @Test
    void blankNameIsRejected() {
        assertThatThrownBy(() -> ProviderEntityKeySupport.nameKey("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
