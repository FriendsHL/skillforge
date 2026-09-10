package com.skillforge.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.core.context.runtime.ContextRuntimeSnapshot;
import com.skillforge.core.context.runtime.SkillInvocationRef;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Closed JSON codec for the content-free ContextRuntimeSnapshot persistence shape. */
@Component
public class ContextRuntimeSnapshotCodec {

    private static final Set<String> ROOT_FIELDS = Set.of(
            "version", "discoveredToolSchemaHashes", "invokedSkills");
    private static final Set<String> SKILL_FIELDS = Set.of(
            "skillId", "versionHash", "invocationSequence", "estimatedTokens");
    private static final int MAX_REFERENCES = 512;
    private static final int MAX_ID_CHARS = 512;
    private static final int MAX_HASH_CHARS = 512;
    private static final int MAX_JSON_CHARS = 256_000;

    private final ObjectMapper objectMapper;

    public ContextRuntimeSnapshotCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encode(ContextRuntimeSnapshot snapshot) {
        ContextRuntimeSnapshot safe = structurallySafe(snapshot)
                ? snapshot
                : ContextRuntimeSnapshot.empty();
        ObjectNode root = objectMapper.createObjectNode();
        root.put("version", ContextRuntimeSnapshot.CURRENT_VERSION);
        ObjectNode tools = root.putObject("discoveredToolSchemaHashes");
        safe.discoveredToolSchemaHashes().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> tools.put(entry.getKey(), entry.getValue()));
        ArrayNode skills = root.putArray("invokedSkills");
        safe.invokedSkills().stream()
                .sorted(java.util.Comparator.comparingLong(SkillInvocationRef::invocationSequence)
                        .thenComparing(SkillInvocationRef::skillId))
                .forEach(ref -> {
                    ObjectNode value = skills.addObject();
                    value.put("skillId", ref.skillId());
                    value.put("versionHash", ref.versionHash());
                    value.put("invocationSequence", ref.invocationSequence());
                    value.put("estimatedTokens", ref.estimatedTokens());
                });
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception impossibleTreeFailure) {
            throw new IllegalStateException("Cannot serialize context runtime snapshot",
                    impossibleTreeFailure);
        }
    }

    public ContextRuntimeSnapshot decodeOrEmpty(String json) {
        if (json == null || json.isBlank() || json.length() > MAX_JSON_CHARS) {
            return ContextRuntimeSnapshot.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isObject() || !hasExactFields(root, ROOT_FIELDS)) {
                return ContextRuntimeSnapshot.empty();
            }
            JsonNode version = root.get("version");
            JsonNode toolNode = root.get("discoveredToolSchemaHashes");
            JsonNode skillNode = root.get("invokedSkills");
            if (version == null || !version.isIntegralNumber()
                    || version.intValue() != ContextRuntimeSnapshot.CURRENT_VERSION
                    || toolNode == null || !toolNode.isObject()
                    || toolNode.size() > MAX_REFERENCES
                    || skillNode == null || !skillNode.isArray()
                    || skillNode.size() > MAX_REFERENCES) {
                return ContextRuntimeSnapshot.empty();
            }

            Map<String, String> tools = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = toolNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (!validId(entry.getKey(), true)
                        || !entry.getValue().isTextual()
                        || !validHash(entry.getValue().textValue())) {
                    return ContextRuntimeSnapshot.empty();
                }
                tools.put(entry.getKey(), entry.getValue().textValue());
            }

            List<SkillInvocationRef> skills = new ArrayList<>();
            Set<String> skillIds = new HashSet<>();
            for (JsonNode value : skillNode) {
                if (!value.isObject() || !hasExactFields(value, SKILL_FIELDS)
                        || !value.path("skillId").isTextual()
                        || !value.path("versionHash").isTextual()
                        || !value.path("invocationSequence").isIntegralNumber()
                        || !value.path("estimatedTokens").isIntegralNumber()) {
                    return ContextRuntimeSnapshot.empty();
                }
                String skillId = value.path("skillId").textValue();
                String hash = value.path("versionHash").textValue();
                long sequence = value.path("invocationSequence").longValue();
                long estimatedTokens = value.path("estimatedTokens").longValue();
                if (!validId(skillId, false) || !validHash(hash)
                        || sequence < 0L || estimatedTokens < 0L
                        || estimatedTokens > Integer.MAX_VALUE
                        || !skillIds.add(skillId)) {
                    return ContextRuntimeSnapshot.empty();
                }
                skills.add(new SkillInvocationRef(
                        skillId, hash, sequence, (int) estimatedTokens));
            }
            return new ContextRuntimeSnapshot(
                    ContextRuntimeSnapshot.CURRENT_VERSION, tools, skills);
        } catch (Exception malformed) {
            return ContextRuntimeSnapshot.empty();
        }
    }

    private static boolean structurallySafe(ContextRuntimeSnapshot snapshot) {
        if (snapshot == null
                || snapshot.version() != ContextRuntimeSnapshot.CURRENT_VERSION
                || snapshot.discoveredToolSchemaHashes().size() > MAX_REFERENCES
                || snapshot.invokedSkills().size() > MAX_REFERENCES) {
            return false;
        }
        for (Map.Entry<String, String> entry
                : snapshot.discoveredToolSchemaHashes().entrySet()) {
            if (!validId(entry.getKey(), true) || !validHash(entry.getValue())) return false;
        }
        Set<String> skillIds = new HashSet<>();
        for (SkillInvocationRef ref : snapshot.invokedSkills()) {
            if (ref == null || !validId(ref.skillId(), false) || !validHash(ref.versionHash())
                    || ref.invocationSequence() < 0L || !skillIds.add(ref.skillId())) {
                return false;
            }
        }
        return true;
    }

    private static boolean validId(String value, boolean tool) {
        return value != null && !value.isBlank() && value.length() <= MAX_ID_CHARS
                && (!tool || value.startsWith("tool:"));
    }

    private static boolean validHash(String value) {
        return value != null && !value.isBlank() && value.length() <= MAX_HASH_CHARS;
    }

    private static boolean hasExactFields(JsonNode value, Set<String> expected) {
        Set<String> actual = new HashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        return actual.equals(expected);
    }
}
