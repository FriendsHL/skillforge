package com.skillforge.core.llm.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Builder of Anthropic {@code cache_control} JSON nodes (PROMPT-CACHE-MVP Phase 2).
 *
 * <p>MVP only emits 5-minute ephemeral breakpoints. The 1-hour TTL field
 * ({@code cache_creation.ephemeral_1h_input_tokens}) is V2.
 */
public final class CacheControlMarker {

    private CacheControlMarker() {}

    /**
     * Build a fresh {@code {"type":"ephemeral"}} node. A new node is returned each call so
     * the caller can safely attach it via {@code parent.set("cache_control", node)} without
     * worrying about Jackson reparenting (calling set with an already-attached node moves
     * it instead of cloning).
     */
    public static ObjectNode ephemeral(ObjectMapper mapper) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "ephemeral");
        return node;
    }
}
