package com.skillforge.core.llm.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PROMPT-CACHE-MVP Phase 1 — INV-2 / INV-12 unit tests for
 * {@link ToolNormalizer}. Aims:
 * <ol>
 *   <li>Trailing whitespace / CRLF normalization is bit-stable.</li>
 *   <li>Tools sort by name; insertion order does not matter.</li>
 *   <li>{@code input_schema} key order does not matter — output JSON is sorted.</li>
 *   <li>{@link ToolNormalizer#hashTool} is deterministic for unchanged input.</li>
 * </ol>
 */
@DisplayName("ToolNormalizer — INV-2 / INV-12 stability (Phase 1)")
class ToolNormalizerTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    @DisplayName("trims trailing whitespace and normalizes CRLF in description")
    void trimsAndNormalizesDescription() {
        ToolSchema raw = new ToolSchema("Bash",
                "Run shell commands.\r\nDangerous if untrusted.   \r\n",
                Map.of());

        Map<String, Object> normalized = ToolNormalizer.normalizeOne(raw);

        assertThat(normalized).containsEntry("description",
                "Run shell commands.\nDangerous if untrusted.");
    }

    @Test
    @DisplayName("tools list is sorted by name regardless of insertion order")
    void sortByName() {
        ToolSchema z = new ToolSchema("Zoo", "z desc", Map.of());
        ToolSchema a = new ToolSchema("Apple", "a desc", Map.of());
        ToolSchema m = new ToolSchema("Mango", "m desc", Map.of());

        List<ToolSchema> sorted = ToolNormalizer.sortByName(List.of(z, a, m));

        assertThat(sorted).extracting(ToolSchema::getName)
                .containsExactly("Apple", "Mango", "Zoo");
    }

    @Test
    @DisplayName("hashTool: deterministic for same logical input regardless of map order")
    void hashToolStableAcrossMapOrder() {
        Map<String, Object> orderA = new LinkedHashMap<>();
        orderA.put("type", "object");
        orderA.put("properties", Map.of("path", Map.of("type", "string")));

        Map<String, Object> orderB = new LinkedHashMap<>();
        orderB.put("properties", Map.of("path", Map.of("type", "string")));
        orderB.put("type", "object");

        ToolSchema a = new ToolSchema("Read", "Read a file", orderA);
        ToolSchema b = new ToolSchema("Read", "Read a file", orderB);

        String hashA = ToolNormalizer.hashTool(a, mapper);
        String hashB = ToolNormalizer.hashTool(b, mapper);

        assertThat(hashA).isEqualTo(hashB).isNotEmpty();
    }

    @Test
    @DisplayName("hashTool: differs when description has a real semantic change")
    void hashToolChangesOnDescriptionEdit() {
        ToolSchema base = new ToolSchema("Read", "Read a file.", Map.of());
        ToolSchema edited = new ToolSchema("Read", "Read a file please.", Map.of());

        assertThat(ToolNormalizer.hashTool(base, mapper))
                .isNotEqualTo(ToolNormalizer.hashTool(edited, mapper));
    }

    @Test
    @DisplayName("INV-1: full tools array hash is stable across 3 builds with the same logical input")
    void hashToolsIsStableAcrossThreeBuilds() {
        // Use TreeMap so iteration order matches lexical order (defensive — the helper
        // also sorts but we want to prove cross-call stability.)
        TreeMap<String, Object> schema = new TreeMap<>();
        schema.put("type", "object");
        schema.put("required", List.of("path"));

        ToolSchema r = new ToolSchema("Read", "Read a file", schema);
        ToolSchema w = new ToolSchema("Write", "Write a file", schema);

        // Build the input list in different orders each time — sortByName must equalize.
        String h1 = ToolNormalizer.hashTools(List.of(r, w), mapper);
        String h2 = ToolNormalizer.hashTools(List.of(w, r), mapper);
        String h3 = ToolNormalizer.hashTools(List.of(r, w), mapper);

        assertThat(h1).isEqualTo(h2).isEqualTo(h3).isNotEmpty();
    }

    @Test
    @DisplayName("normalizeSchema preserves nested map key ordering via TreeMap")
    void normalizeSchemaKeyOrder() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("properties", Map.of("z", "string", "a", "string"));
        input.put("type", "object");

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) ToolNormalizer.normalizeSchema(input);

        // TreeMap sorts keys lexically — assertion via iteration order on the value Map.
        assertThat(out.keySet()).containsExactly("properties", "type");
    }
}
