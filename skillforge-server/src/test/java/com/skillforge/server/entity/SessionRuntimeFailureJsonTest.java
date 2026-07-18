package com.skillforge.server.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionRuntimeFailureJsonTest {

    @Test
    void restJson_keepsRuntimeFieldsAndAddsGenericFailureAliases() throws Exception {
        SessionEntity session = new SessionEntity();
        session.setRuntimeStatus("error");
        session.setRuntimeError("The model connection timed out.");
        session.setRuntimeFailureSource("network");
        session.setRuntimeFailureCode("NETWORK_TIMEOUT");
        session.setRuntimeRetryable(true);
        session.setRuntimeSideEffects("none");

        JsonNode json = new ObjectMapper().findAndRegisterModules().valueToTree(session);

        assertThat(json.get("runtimeFailureSource").asText()).isEqualTo("network");
        assertThat(json.get("runtimeFailureCode").asText()).isEqualTo("NETWORK_TIMEOUT");
        assertThat(json.get("runtimeRetryable").asBoolean()).isTrue();
        assertThat(json.get("runtimeSideEffects").asText()).isEqualTo("none");
        assertThat(json.get("failureSource").asText()).isEqualTo("network");
        assertThat(json.get("failureCode").asText()).isEqualTo("NETWORK_TIMEOUT");
        assertThat(json.get("retryable").asBoolean()).isTrue();
        assertThat(json.get("sideEffects").asText()).isEqualTo("none");
    }
}
