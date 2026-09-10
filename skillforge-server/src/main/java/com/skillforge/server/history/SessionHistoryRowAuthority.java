package com.skillforge.server.history;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.SessionMessageRepository;

import java.util.Set;

/** Verifies legacy backfill against the row prefix under the caller's lock or snapshot. */
public final class SessionHistoryRowAuthority {
    private static final Set<String> ROLES = Set.of("user", "assistant", "system");

    private SessionHistoryRowAuthority() {
    }

    public static boolean isVerified(
            SessionEntity session, SessionMessageRepository repository, ObjectMapper mapper) {
        String legacyJson = session.getMessagesJson();
        if (legacyJson == null || legacyJson.isBlank()) return true;
        try {
            JsonNode legacy = mapper.readTree(legacyJson);
            if (legacy == null || !legacy.isArray()) return false;
            if (legacy.isEmpty()) return true;
            for (JsonNode message : legacy) {
                if (!message.isObject() || !ROLES.contains(message.path("role").asText())) return false;
            }
            // One PostgreSQL statement; no N+1 pages or successful-verification cache.
            // Rechecking also detects same-epoch row corruption/deletion.
            return repository.isLegacyPrefixRepresented(session.getId(), legacyJson);
        } catch (JsonProcessingException | IllegalArgumentException malformed) {
            return false;
        }
    }
}
