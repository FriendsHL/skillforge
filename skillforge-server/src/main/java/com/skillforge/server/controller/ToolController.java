package com.skillforge.server.controller;

import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.core.skill.Tool;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Canonical endpoint for listing Java Tool definitions (Bash, Read, etc.).
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
        List<Map<String, Object>> tools = skillRegistry.getAllTools().stream()
                .map(tool -> {
                    Map<String, Object> info = new HashMap<>();
                    info.put("name", tool.getName());
                    info.put("description", tool.getDescription());
                    info.put("readOnly", tool.isReadOnly());
                    info.put("type", "builtin");
                    if (tool.getToolSchema() != null) {
                        info.put("toolSchema", tool.getToolSchema());
                    }
                    return info;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(tools);
    }
}
