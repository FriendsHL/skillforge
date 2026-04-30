package com.skillforge.core.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CTX-1 — verify the value-object contract for {@link CompactThresholds}.
 */
class CompactThresholdsTest {

    @Test
    @DisplayName("DEFAULTS matches the historical 0.60 / 0.80 / 0.85 thresholds")
    void defaults_matchHistoricalRatios() {
        assertThat(CompactThresholds.DEFAULTS.getSoftRatio()).isEqualTo(0.60);
        assertThat(CompactThresholds.DEFAULTS.getHardRatio()).isEqualTo(0.80);
        assertThat(CompactThresholds.DEFAULTS.getPreemptiveRatio()).isEqualTo(0.85);
    }

    @Test
    @DisplayName("constructor stores ratios as provided")
    void constructor_storesValues() {
        CompactThresholds t = new CompactThresholds(0.50, 0.70, 0.80);
        assertThat(t.getSoftRatio()).isEqualTo(0.50);
        assertThat(t.getHardRatio()).isEqualTo(0.70);
        assertThat(t.getPreemptiveRatio()).isEqualTo(0.80);
    }

    @Test
    @DisplayName("LlmProvider default returns DEFAULTS")
    void llmProvider_defaultThresholds_areDefaults() {
        LlmProvider p = new LlmProvider() {
            @Override public String getName() { return "fake"; }
            @Override public LlmResponse chat(LlmRequest request) { return null; }
            @Override public void chatStream(LlmRequest request, LlmStreamHandler handler) { /* noop */ }
        };
        assertThat(p.getCompactThresholds()).isSameAs(CompactThresholds.DEFAULTS);
    }
}
