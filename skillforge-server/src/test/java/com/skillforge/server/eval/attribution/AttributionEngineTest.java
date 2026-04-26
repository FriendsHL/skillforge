package com.skillforge.server.eval.attribution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AttributionEngineTest {

    private final AttributionEngine engine = new AttributionEngine();

    @Test
    @DisplayName("memoryResultEmpty attributes failed runs to missing memory")
    void compute_memoryResultEmpty_returnsMemoryMissing() {
        EvalSignals signals = EvalSignals.builder()
                .taskCompletionOraclePass(false)
                .outputFormatCorrect(true)
                .memorySkillCalled(true)
                .memoryResultEmpty(true)
                .build();

        FailureAttribution result = engine.compute(signals);

        assertThat(result).isEqualTo(FailureAttribution.MEMORY_MISSING);
    }

    @Test
    @DisplayName("memorySkillCalled without empty result attributes failed runs to memory interference")
    void compute_memorySkillCalled_returnsMemoryInterference() {
        EvalSignals signals = EvalSignals.builder()
                .taskCompletionOraclePass(false)
                .outputFormatCorrect(true)
                .memorySkillCalled(true)
                .build();

        FailureAttribution result = engine.compute(signals);

        assertThat(result).isEqualTo(FailureAttribution.MEMORY_INTERFERENCE);
    }

    @Test
    @DisplayName("engine exception still vetoes memory attribution")
    void compute_engineException_vetoesMemorySignals() {
        EvalSignals signals = EvalSignals.builder()
                .engineThrewException(true)
                .memorySkillCalled(true)
                .memoryResultEmpty(true)
                .build();

        FailureAttribution result = engine.compute(signals);

        assertThat(result).isEqualTo(FailureAttribution.VETO_EXCEPTION);
    }
}
