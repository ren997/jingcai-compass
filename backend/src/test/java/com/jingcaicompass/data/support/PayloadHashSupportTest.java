package com.jingcaicompass.data.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PayloadHashSupportTest {

    @Test
    void computesStableSha256HexDigest() {
        String hash = PayloadHashSupport.sha256Hex("{\"ok\":true}");

        assertThat(hash).hasSize(64);
        assertThat(hash).matches("[0-9a-f]{64}");
        assertThat(PayloadHashSupport.sha256Hex("{\"ok\":true}")).isEqualTo(hash);
        assertThat(PayloadHashSupport.sha256Hex("{\"ok\":false}")).isNotEqualTo(hash);
    }

    @Test
    void rejectsNullPayload() {
        assertThatThrownBy(() -> PayloadHashSupport.sha256Hex(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload");
    }
}
