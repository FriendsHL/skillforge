package com.skillforge.core.llm.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.core.model.ToolSchema;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Tool serialization normalization for prompt-cache stability (PROMPT-CACHE-MVP Phase 1).
 *
 * <p>Invariants (INV-2 / INV-12):
 * <ul>
 *   <li>Tools list sorted by {@link ToolSchema#getName()} (case-sensitive, natural order).
 *       Sort is explicit here — never trust upstream registry insertion order.</li>
 *   <li>{@code description} trimmed of trailing whitespace and {@code \r\n} normalized to
 *       {@code \n} (some skill loaders pull descriptions from CRLF text files).</li>
 *   <li>{@code input_schema} serialization uses
 *       {@link SerializationFeature#ORDER_MAP_ENTRIES_BY_KEYS} so map insertion order
 *       cannot drift hashing.</li>
 * </ul>
 *
 * <p>Output is the canonical JSON Anthropic should see; for OpenAI-compatible families the
 * caller still wraps each tool in the {@code {"type":"function","function":{...}}} envelope.
 */
public final class ToolNormalizer {

    private ToolNormalizer() {}

    /**
     * Normalize a single ToolSchema into a key-ordered Map suitable for JSON serialization.
     * Returned map uses {@link TreeMap} so iteration order matches sorted keys regardless of
     * what the {@link ObjectMapper} feature flags do (defense in depth).
     *
     * <p>Map keys produced: {@code name}, {@code description}, {@code input_schema}.
     */
    public static Map<String, Object> normalizeOne(ToolSchema tool) {
        Map<String, Object> out = new TreeMap<>();
        out.put("name", tool.getName() == null ? "" : tool.getName());
        out.put("description", normalizeDescription(tool.getDescription()));
        out.put("input_schema", normalizeSchema(tool.getInputSchema()));
        return out;
    }

    /**
     * Sort tools by name (case-sensitive). Returned list is a fresh ArrayList — the caller
     * may iterate it freely.
     */
    public static List<ToolSchema> sortByName(List<ToolSchema> tools) {
        if (tools == null || tools.isEmpty()) return List.of();
        List<ToolSchema> copy = new ArrayList<>(tools);
        copy.sort(Comparator.comparing(t -> t.getName() == null ? "" : t.getName()));
        return copy;
    }

    /**
     * Compute a stable SHA-256 of a single normalized tool. Used by ToolHashTracker to
     * detect drift across calls within a session (INV-3).
     */
    public static String hashTool(ToolSchema tool, ObjectMapper mapper) {
        try {
            byte[] json = canonicalMapper(mapper).writeValueAsBytes(normalizeOne(tool));
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(json));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            // SHA-256 is always available in JDK 17; JSON fail means a non-serializable
            // schema — return empty string so caller treats as unknown (no false drift).
            return "";
        }
    }

    /**
     * Compute a stable SHA-256 of an entire normalized + sorted tools array. Used by the
     * stability test (INV-1) to assert prefix invariance across calls.
     */
    public static String hashTools(List<ToolSchema> tools, ObjectMapper mapper) {
        try {
            List<Map<String, Object>> normalized = new ArrayList<>(tools == null ? 0 : tools.size());
            for (ToolSchema t : sortByName(tools)) {
                normalized.add(normalizeOne(t));
            }
            byte[] json = canonicalMapper(mapper).writeValueAsBytes(normalized);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(json));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            return "";
        }
    }

    /**
     * Trim trailing whitespace + normalize CRLF → LF. Internal whitespace preserved as-is.
     */
    public static String normalizeDescription(String desc) {
        if (desc == null) return "";
        // CRLF / CR → LF first, then strip trailing whitespace.
        return desc.replace("\r\n", "\n").replace("\r", "\n").stripTrailing();
    }

    /**
     * Defensive copy of the input schema into a TreeMap so any nested Maps inherit
     * key-sorted iteration. Lists are walked recursively (keeps nested object orderings
     * stable). Non-Map / non-List leaves are returned as-is.
     */
    @SuppressWarnings("unchecked")
    public static Object normalizeSchema(Object schema) {
        if (schema == null) return null;
        if (schema instanceof Map<?, ?> map) {
            Map<String, Object> out = new TreeMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                out.put(key, normalizeSchema(e.getValue()));
            }
            return out;
        }
        if (schema instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object v : list) out.add(normalizeSchema(v));
            return out;
        }
        if (schema instanceof String s) {
            // Description-like nested fields may also carry CRLF; cheap normalization.
            return s.replace("\r\n", "\n").replace("\r", "\n");
        }
        return schema;
    }

    /**
     * Provide an ObjectMapper variant with ORDER_MAP_ENTRIES_BY_KEYS enabled. We never
     * mutate the caller's mapper (which may be a singleton bean); we copy it once and
     * reuse the canonical clone — copying on every tool / every call would be a hot-path
     * allocation regression (each LLM call hashes N tools, often 10-30 of them).
     *
     * <p>Cache keyed by {@code System.identityHashCode(mapper)}: cheap O(1) hash, never
     * leaks across distinct mapper instances (e.g. tests using a private mapper). The
     * cache is lazy-initialized via {@link ConcurrentHashMap#computeIfAbsent} for thread
     * safety.
     */
    private static final java.util.concurrent.ConcurrentHashMap<Integer, ObjectMapper>
            CANONICAL_MAPPER_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private static ObjectMapper canonicalMapper(ObjectMapper mapper) {
        return CANONICAL_MAPPER_CACHE.computeIfAbsent(
                System.identityHashCode(mapper),
                k -> {
                    ObjectMapper copy = mapper.copy();
                    copy.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
                    return copy;
                });
    }

    /** Convenience overload: assume a default mapper is fine (used by tests). */
    public static String hashTools(List<ToolSchema> tools) {
        return hashTools(tools, defaultMapper());
    }

    /** Convenience: serialize the whole sorted+normalized tools array as canonical JSON. */
    public static String canonicalJson(List<ToolSchema> tools, ObjectMapper mapper) {
        try {
            List<Map<String, Object>> normalized = new ArrayList<>(tools == null ? 0 : tools.size());
            for (ToolSchema t : sortByName(tools)) {
                normalized.add(normalizeOne(t));
            }
            return canonicalMapper(mapper).writeValueAsString(normalized);
        } catch (JsonProcessingException e) {
            return "";
        }
    }

    private static ObjectMapper defaultMapper() {
        ObjectMapper m = new ObjectMapper();
        m.findAndRegisterModules();
        return m;
    }

    /**
     * For stability tests: produce a SHA-256 over an arbitrary string (e.g. stable section
     * of system prompt). Centralized here so tests never re-roll a digest helper.
     */
    public static String sha256(String input) {
        if (input == null) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }
}
