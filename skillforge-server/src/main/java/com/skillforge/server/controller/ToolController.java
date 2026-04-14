package com.skillforge.server.controller;

import com.skillforge.core.skill.Skill;
import com.skillforge.core.skill.SkillRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Canonical endpoint for listing Java Tool definitions (Bash, FileRead, etc.).
 * Separate from SkillController which handles SKILL.md-based skills.
 */
@RestController
@RequestMapping("/api/tools")
public class ToolController {

    private final SkillRegistry skillRegistry;

    public ToolController(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    /**
     * Returns all registered Java Tool definitions with their schemas.
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listTools() {
        List<Map<String, Object>> tools = skillRegistry.getAllSkills().stream()
                .map(skill -> {
                    Map<String, Object> info = new HashMap<>();
                    info.put("name", skill.getName());
                    info.put("description", skill.getDescription());
                    info.put("readOnly", skill.isReadOnly());
                    info.put("type", "builtin");
                    if (skill.getToolSchema() != null) {
                        info.put("toolSchema", skill.getToolSchema());
                    }
                    return info;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(tools);
    }
}
