package com.skillforge.observability.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderNameTest {

    @Test
    @DisplayName("ark is a canonical provider name (not coerced to unknown)")
    void ark_isCanonical() {
        assertThat(ProviderName.isCanonical("ark")).isTrue();
        assertThat(ProviderName.coerce("ark")).isEqualTo("ark");
    }

    @Test
    @DisplayName("existing canonical names still recognized")
    void existing_stillCanonical() {
        assertThat(ProviderName.isCanonical("claude")).isTrue();
        assertThat(ProviderName.isCanonical("xiaomi-mimo")).isTrue();
        assertThat(ProviderName.isCanonical("deepseek")).isTrue();
    }

    @Test
    @DisplayName("unknown / null / out-of-set coerce to unknown")
    void nonCanonical_coercesToUnknown() {
        assertThat(ProviderName.isCanonical("made-up")).isFalse();
        assertThat(ProviderName.isCanonical(null)).isFalse();
        assertThat(ProviderName.coerce("made-up")).isEqualTo("unknown");
        assertThat(ProviderName.coerce(null)).isEqualTo("unknown");
    }
}
