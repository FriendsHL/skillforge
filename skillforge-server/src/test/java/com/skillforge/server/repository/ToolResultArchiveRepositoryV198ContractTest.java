package com.skillforge.server.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ToolResultArchiveRepository V198 compatibility")
class ToolResultArchiveRepositoryV198ContractTest {

    @Test
    @DisplayName("legacy insert no longer names the deleted full session/toolUse conflict target")
    void legacyInsert_usesTargetlessConflictHandling() throws Exception {
        Method insert = ToolResultArchiveRepository.class.getMethod(
                "insertIgnoreConflict",
                String.class, String.class, Long.class, String.class, String.class,
                int.class, String.class, String.class, Instant.class);
        Query query = insert.getAnnotation(Query.class);

        assertThat(query).isNotNull();
        assertThat(query.nativeQuery()).isTrue();
        assertThat(query.value()).contains("ON CONFLICT DO NOTHING");
        assertThat(query.value()).doesNotContain("ON CONFLICT (session_id, tool_use_id)");
    }
}
