package com.skillforge.server.session;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UnknownOutcomeResolutionRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    void deserialize_unknownOuterField_rejectsEvenWhenGlobalMapperIgnoresUnknowns() {
        assertThatThrownBy(() -> objectMapper.readValue("""
                {
                  "resolutionRequestId":"%s",
                  "expectedHistoryEpoch":1,
                  "expectedExecutionGeneration":2,
                  "expectedExecutionFence":3,
                  "action":"CONTINUE_CURRENT_TIMELINE",
                  "reason":"checked externally",
                  "inboxDispositions":[],
                  "actorId":99
                }
                """.formatted(UUID.randomUUID()), UnknownOutcomeResolutionRequest.class))
                .hasMessageContaining("Unknown resolution field");
    }

    @Test
    void deserialize_unknownNestedDispositionField_rejectsClosedNestedBody() {
        assertThatThrownBy(() -> objectMapper.readValue("""
                {
                  "resolutionRequestId":"%s",
                  "expectedHistoryEpoch":1,
                  "expectedExecutionGeneration":2,
                  "expectedExecutionFence":3,
                  "action":"PREPARE_RESTORE",
                  "reason":"checked externally",
                  "inboxDispositions":[{
                    "inboxId":"%s",
                    "disposition":"KEEP_FOR_RESTORE",
                    "message":"must not be accepted"
                  }]
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID()),
                UnknownOutcomeResolutionRequest.class))
                .hasMessageContaining("Unknown inbox disposition field");
    }
}
