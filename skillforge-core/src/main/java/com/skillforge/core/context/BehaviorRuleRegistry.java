package com.skillforge.core.context;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Registry for built-in behavior rules. Loads rules from classpath resource
 * {@code behavior-rules.json} at construction time.
 * <p>
 * Thread-safe: all data is immutable after construction.
 */
public class BehaviorRuleRegistry {

    private static final Logger log = LoggerFactory.getLogger(BehaviorRuleRegistry.class);
    private static final String RESOURCE_PATH = "behavior-rules.json";

    private final List<BehaviorRuleDefinition> allRules;
    private final Map<String, BehaviorRuleDefinition> rulesById;
    private final Map<String, List<String>> presetRuleIds;

    public BehaviorRuleRegistry(ObjectMapper objectMapper) {
        List<BehaviorRuleDefinition> loaded = loadRules(objectMapper);
        this.allRules = Collections.unmodifiableList(loaded);
        this.rulesById = loaded.stream()
                .collect(Collectors.toUnmodifiableMap(BehaviorRuleDefinition::id, r -> r));

        // Build preset -> rule ID lists
        Map<String, List<String>> presets = new java.util.HashMap<>();
        for (String preset : List.of("autonomous", "cautious", "full")) {
            List<String> ids = loaded.stream()
                    .filter(r -> !r.deprecated() && r.presets().contains(preset))
                    .map(BehaviorRuleDefinition::id)
                    .toList();
            presets.put(preset, ids);
        }
        this.presetRuleIds = Collections.unmodifiableMap(presets);
    }

    /**
     * Returns all rule definitions (including deprecated, for API completeness).
     */
    public List<BehaviorRuleDefinition> getAllRules() {
        return allRules;
    }

    /**
     * Find a rule by ID.
     */
    public Optional<BehaviorRuleDefinition> findById(String id) {
        return Optional.ofNullable(rulesById.get(id));
    }

    /**
     * Get the preset rule IDs for a given execution mode.
     *
     * @param executionMode "ask" maps to "cautious", "auto" maps to "autonomous"
     * @return list of rule IDs in the preset
     */
    public List<String> getPresetRuleIds(String executionMode) {
        String presetName = mapExecutionModeToPreset(executionMode);
        return presetRuleIds.getOrDefault(presetName, List.of());
    }

    /**
     * Get the preset name for a given execution mode.
     */
    public String getPresetName(String executionMode) {
        return mapExecutionModeToPreset(executionMode);
    }

    /**
     * Resolve a list of builtin rule IDs + custom rules into prompt texts.
     * Handles deprecated rules (follows replacedBy chain) and unknown IDs (skips with warning).
     *
     * @param builtinRuleIds list of builtin rule IDs selected by user
     * @param systemPrompt   the agent's system prompt (used for language detection)
     * @return ordered list of prompt texts ready for injection
     */
    public List<String> resolvePromptTexts(List<String> builtinRuleIds, String systemPrompt) {
        if (builtinRuleIds == null || builtinRuleIds.isEmpty()) {
            return List.of();
        }
        boolean useChinese = isPrimarilyChinese(systemPrompt);
        List<String> result = new ArrayList<>();
        for (String ruleId : builtinRuleIds) {
            BehaviorRuleDefinition rule = resolveRule(ruleId);
            if (rule == null) {
                log.warn("Unknown behavior rule ID '{}', skipping", ruleId);
                continue;
            }
            String text = useChinese ? rule.promptTextZh() : rule.promptText();
            if (text != null && !text.isBlank()) {
                result.add(text);
            }
        }
        return result;
    }

    /**
     * Validate rule IDs and return warnings for unknown or deprecated IDs.
     */
    public List<String> validateRuleIds(List<String> ruleIds) {
        if (ruleIds == null) return List.of();
        List<String> warnings = new ArrayList<>();
        for (String id : ruleIds) {
            BehaviorRuleDefinition rule = rulesById.get(id);
            if (rule == null) {
                warnings.add("Unknown behavior rule: '" + id + "'");
            } else if (rule.deprecated()) {
                String msg = "Behavior rule '" + id + "' is deprecated";
                if (rule.replacedBy() != null) {
                    msg += ", replaced by '" + rule.replacedBy() + "'";
                }
                warnings.add(msg);
            }
        }
        return warnings;
    }

    /**
     * Resolve a rule ID, following the deprecated -> replacedBy chain (max 3 hops).
     */
    private BehaviorRuleDefinition resolveRule(String ruleId) {
        BehaviorRuleDefinition rule = rulesById.get(ruleId);
        int hops = 0;
        while (rule != null && rule.deprecated() && rule.replacedBy() != null && hops < 3) {
            rule = rulesById.get(rule.replacedBy());
            hops++;
        }
        return rule;
    }

    private static String mapExecutionModeToPreset(String executionMode) {
        if ("auto".equalsIgnoreCase(executionMode)) return "autonomous";
        return "cautious"; // default for "ask" or unknown
    }

    /**
     * Detect if text is primarily Chinese (CJK characters > 30%).
     */
    static boolean isPrimarilyChinese(String text) {
        if (text == null || text.isBlank()) return false;
        long total = text.codePoints().count();
        if (total == 0) return false;
        long cjkCount = text.codePoints()
                .filter(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)
                .count();
        return (double) cjkCount / total > 0.3;
    }

    private static List<BehaviorRuleDefinition> loadRules(ObjectMapper objectMapper) {
        try (InputStream is = BehaviorRuleRegistry.class.getClassLoader()
                .getResourceAsStream(RESOURCE_PATH)) {
            if (is == null) {
                log.warn("behavior-rules.json not found on classpath, no built-in rules available");
                return List.of();
            }
            JsonNode root = objectMapper.readTree(is);
            JsonNode rulesNode = root.get("rules");
            if (rulesNode == null || !rulesNode.isArray()) {
                log.warn("behavior-rules.json has no 'rules' array");
                return List.of();
            }
            return objectMapper.readValue(
                    rulesNode.traverse(),
                    new TypeReference<List<BehaviorRuleDefinition>>() {});
        } catch (IOException e) {
            log.error("Failed to load behavior-rules.json", e);
            return List.of();
        }
    }
}
