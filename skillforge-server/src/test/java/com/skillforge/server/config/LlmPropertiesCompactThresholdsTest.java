package com.skillforge.server.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CTX-1 — verify the {@link LlmProperties.CompactThresholds} → core
 * {@link com.skillforge.core.llm.CompactThresholds} mapping.
 *
 * <p>Lives in server because LlmProperties is a Spring config class; pure unit-level
 * test — no SpringBootTest required.
 */
class LlmPropertiesCompactThresholdsTest {

    @Test
    @DisplayName("toCore: all three ratios specified are passed through")
    void toCore_allThree_passedThrough() {
        LlmProperties.CompactThresholds yaml = new LlmProperties.CompactThresholds();
        yaml.setSoftRatio(0.50);
        yaml.setHardRatio(0.70);
        yaml.setPreemptiveRatio(0.80);

        com.skillforge.core.llm.CompactThresholds core = yaml.toCore();
        assertThat(core.getSoftRatio()).isEqualTo(0.50);
        assertThat(core.getHardRatio()).isEqualTo(0.70);
        assertThat(core.getPreemptiveRatio()).isEqualTo(0.80);
    }

    @Test
    @DisplayName("toCore: nulls fall back to DEFAULTS")
    void toCore_nullsFallBackToDefaults() {
        LlmProperties.CompactThresholds yaml = new LlmProperties.CompactThresholds();
        // all three left null
        com.skillforge.core.llm.CompactThresholds core = yaml.toCore();
        com.skillforge.core.llm.CompactThresholds defaults =
                com.skillforge.core.llm.CompactThresholds.DEFAULTS;

        assertThat(core.getSoftRatio()).isEqualTo(defaults.getSoftRatio());
        assertThat(core.getHardRatio()).isEqualTo(defaults.getHardRatio());
        assertThat(core.getPreemptiveRatio()).isEqualTo(defaults.getPreemptiveRatio());
    }

    @Test
    @DisplayName("toCore: partial config keeps configured value, defaults the rest")
    void toCore_partialConfig_mixesYamlAndDefaults() {
        LlmProperties.CompactThresholds yaml = new LlmProperties.CompactThresholds();
        yaml.setSoftRatio(0.45);
        // leave hard / preemptive null

        com.skillforge.core.llm.CompactThresholds core = yaml.toCore();
        com.skillforge.core.llm.CompactThresholds defaults =
                com.skillforge.core.llm.CompactThresholds.DEFAULTS;

        assertThat(core.getSoftRatio()).isEqualTo(0.45);
        assertThat(core.getHardRatio()).isEqualTo(defaults.getHardRatio());
        assertThat(core.getPreemptiveRatio()).isEqualTo(defaults.getPreemptiveRatio());
    }

    @Test
    @DisplayName("ProviderConfig: getCompactThresholds defaults to null (use defaults at wire time)")
    void providerConfig_compactThresholdsField_defaultsNull() {
        LlmProperties.ProviderConfig pc = new LlmProperties.ProviderConfig();
        assertThat(pc.getCompactThresholds()).isNull();
    }

    @Test
    @DisplayName("ProviderConfig: setter round-trip works")
    void providerConfig_setterRoundTrip() {
        LlmProperties.ProviderConfig pc = new LlmProperties.ProviderConfig();
        LlmProperties.CompactThresholds t = new LlmProperties.CompactThresholds();
        t.setSoftRatio(0.55);
        pc.setCompactThresholds(t);
        assertThat(pc.getCompactThresholds()).isSameAs(t);
        assertThat(pc.getCompactThresholds().getSoftRatio()).isEqualTo(0.55);
    }
}
