package com.skillforge.server.history;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Canonical, identity-free selector binding used by unsigned exact-return cursors. */
@Component
public final class HistoryCanonicalSelector {

    private final ObjectMapper objectMapper;

    public HistoryCanonicalSelector(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String hash(SessionHistorySearchInput input) {
        return sha256(canonicalJson(input));
    }

    public String hash(SessionHistoryReadInput input) {
        return sha256(canonicalJson(input));
    }

    public String canonicalJson(SessionHistorySearchInput input) {
        if (input == null) throw new IllegalArgumentException("Search selector is required");
        Map<String, Object> selector = new LinkedHashMap<>();
        put(selector, "query", input.query());
        put(selector, "seqFrom", input.seqFrom());
        put(selector, "seqTo", input.seqTo());
        put(selector, "roles", input.roles());
        put(selector, "kinds", input.kinds());
        put(selector, "toolName", input.toolName());
        put(selector, "toolUseId", input.toolUseId());
        put(selector, "compacted", input.compacted());
        put(selector, "summaryState", input.summaryState());
        put(selector, "limit", input.limit());
        return write(selector);
    }

    public String canonicalJson(SessionHistoryReadInput input) {
        if (input == null) throw new IllegalArgumentException("Read selector is required");
        Map<String, Object> selector = new LinkedHashMap<>();
        put(selector, "refs", input.refs());
        put(selector, "seqFrom", input.seqFrom());
        put(selector, "seqTo", input.seqTo());
        put(selector, "aroundSeq", input.aroundSeq());
        put(selector, "before", input.before());
        put(selector, "after", input.after());
        put(selector, "tail", input.tail());
        put(selector, "archiveRef", input.archiveRef());
        put(selector, "offset", input.offset());
        put(selector, "maxChars", input.maxChars());
        return write(selector);
    }

    private String write(Map<String, Object> selector) {
        try {
            return objectMapper.writeValueAsString(selector);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to encode canonical History selector", e);
        }
    }

    private static void put(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
