package com.skillforge.server.service;

import com.skillforge.server.entity.SessionMessageEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Session message write-batch identity contract")
class SessionMessageBatchIdentityContractTest {

    @Test
    @DisplayName("entity and rewrite carriers expose the complete write-batch pair")
    void carriers_exposeCompleteWriteBatchPair() {
        assertThat(Arrays.stream(SessionMessageEntity.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName))
                .contains("writeBatchId", "writeBatchOrdinal");

        assertThat(recordComponentNames(SessionService.AppendMessage.class))
                .containsSubsequence("writeBatchId", "writeBatchOrdinal");
        assertThat(recordComponentNames(SessionService.StoredMessage.class))
                .containsSubsequence("writeBatchId", "writeBatchOrdinal");
    }

    @Test
    @DisplayName("AppendMessage rejects either half of a write-batch pair")
    void appendMessage_halfPair_failsClosed() throws Exception {
        Constructor<?> constructor = Arrays.stream(SessionService.AppendMessage.class.getDeclaredConstructors())
                .filter(candidate -> candidate.getParameterCount() == 9)
                .findFirst()
                .orElseThrow(() -> new AssertionError("9-argument write-batch constructor is missing"));

        assertThatThrownByConstructor(constructor, "batch-1", null);
        assertThatThrownByConstructor(constructor, null, 0);
        assertThatThrownByConstructor(constructor, "batch-1", 0);
    }

    @Test
    @DisplayName("StoredMessage rejects corrupt half-pairs read from persistence")
    void storedMessage_halfPair_failsClosed() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new SessionService.StoredMessage(
                        0L,
                        SessionService.MSG_TYPE_NORMAL,
                        SessionService.MESSAGE_TYPE_NORMAL,
                        null,
                        null,
                        Collections.emptyMap(),
                        com.skillforge.core.model.Message.user("payload"),
                        null,
                        null,
                        null,
                        "batch-1",
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must both be null or both be non-null");
    }

    private static String[] recordComponentNames(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName)
                .toArray(String[]::new);
    }

    private static void assertThatThrownByConstructor(Constructor<?> constructor,
                                                       String writeBatchId,
                                                       Integer writeBatchOrdinal) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> constructor.newInstance(
                        com.skillforge.core.model.Message.user("payload"),
                        SessionService.MSG_TYPE_NORMAL,
                        SessionService.MESSAGE_TYPE_NORMAL,
                        null,
                        null,
                        java.util.Collections.emptyMap(),
                        null,
                        writeBatchId,
                        writeBatchOrdinal))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }
}
