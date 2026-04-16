package com.skillforge.server.controller;

import com.skillforge.core.context.BehaviorRuleDefinition;
import com.skillforge.core.context.BehaviorRuleRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/behavior-rules")
public class BehaviorRuleController {

    private final BehaviorRuleRegistry registry;

    public BehaviorRuleController(BehaviorRuleRegistry registry) {
        this.registry = registry;
    }

    /**
     * Returns all built-in behavior rule definitions.
     * Frontend uses this as the single source of truth for rule metadata.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listRules() {
        List<BehaviorRuleDto> dtos = registry.getAllRules().stream()
                .map(BehaviorRuleDto::from)
                .toList();
        return ResponseEntity.ok(Map.of("version", "1.0", "rules", dtos));
    }

    /**
     * Returns the recommended rule IDs for a given execution mode preset.
     */
    @GetMapping("/presets")
    public ResponseEntity<Map<String, Object>> getPreset(
            @RequestParam(defaultValue = "ask") String executionMode) {
        String presetName = registry.getPresetName(executionMode);
        List<String> ruleIds = registry.getPresetRuleIds(executionMode);
        return ResponseEntity.ok(Map.of(
                "presetName", presetName,
                "ruleIds", ruleIds));
    }

    /**
     * DTO that hides promptText/promptTextZh from the API response.
     * Frontend only needs labels and metadata for rendering the UI.
     */
    record BehaviorRuleDto(
            String id,
            String category,
            String severity,
            String label,
            String labelZh,
            boolean deprecated,
            String replacedBy,
            List<String> presets
    ) {
        static BehaviorRuleDto from(BehaviorRuleDefinition r) {
            return new BehaviorRuleDto(
                    r.id(), r.category(), r.severity(),
                    r.label(), r.labelZh(),
                    r.deprecated(), r.replacedBy(), r.presets());
        }
    }
}
