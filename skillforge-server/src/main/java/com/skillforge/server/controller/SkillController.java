package com.skillforge.server.controller;

import com.skillforge.core.model.SkillDefinition;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.service.SkillService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillService skillService;
    private final SkillRegistry skillRegistry;

    public SkillController(SkillService skillService, SkillRegistry skillRegistry) {
        this.skillService = skillService;
        this.skillRegistry = skillRegistry;
    }

    /**
     * 返回所有 Skill（system + user），合并 SkillRegistry 中的 system SkillDefinition
     * 和数据库中的 user skill。System skill 同名时优先，跳过数据库中的重复。
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listSkills(
            @RequestParam(value = "ownerId", required = false) Long ownerId) {

        // 1. 收集 system skill definitions
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> systemSkillNames = new java.util.HashSet<>();

        for (SkillDefinition def : skillRegistry.getAllSkillDefinitions()) {
            if (def.isSystem()) {
                systemSkillNames.add(def.getName());
                Map<String, Object> item = new HashMap<>();
                item.put("id", "system-" + def.getName());
                item.put("name", def.getName());
                item.put("description", def.getDescription());
                item.put("requiredTools", String.join(",", def.getRequiredTools()));
                item.put("enabled", true);
                item.put("system", true);
                item.put("source", "system");
                result.add(item);
            }
        }

        // 2. 收集 user skills from DB，跳过与 system skill 同名的
        List<SkillEntity> dbSkills;
        if (ownerId != null) {
            dbSkills = skillService.listSkills(ownerId);
        } else {
            dbSkills = skillService.listAllSkills();
        }
        for (SkillEntity entity : dbSkills) {
            if (systemSkillNames.contains(entity.getName())) {
                continue; // system 版本优先，跳过数据库中的同名 skill
            }
            Map<String, Object> item = new HashMap<>();
            item.put("id", entity.getId());
            item.put("name", entity.getName());
            item.put("description", entity.getDescription());
            item.put("requiredTools", entity.getRequiredTools());
            item.put("enabled", entity.isEnabled());
            item.put("system", false);
            item.put("source", entity.getSource());
            result.add(item);
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 返回所有工具（Bash, FileRead 等 Java Skill），不包含 ClawHub 等已迁移为文件 Skill 的。
     */
    @GetMapping("/builtin")
    public ResponseEntity<List<Map<String, Object>>> listBuiltinSkills() {
        List<Map<String, Object>> builtins = skillRegistry.getAllSkills().stream()
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
        return ResponseEntity.ok(builtins);
    }

    @GetMapping("/{id}")
    public ResponseEntity<SkillEntity> getSkill(@PathVariable Long id) {
        return ResponseEntity.ok(
                skillService.listPublicSkills().stream()
                        .filter(s -> s.getId().equals(id))
                        .findFirst()
                        .orElseThrow(() -> new RuntimeException("Skill not found: " + id)));
    }

    @GetMapping("/{id}/detail")
    public ResponseEntity<Map<String, Object>> getSkillDetail(@PathVariable Long id) {
        return ResponseEntity.ok(skillService.getSkillDetail(id));
    }

    @GetMapping("/{id}/prompt")
    public ResponseEntity<String> getSkillPrompt(@PathVariable Long id) {
        return ResponseEntity.ok(skillService.getSkillPromptContent(id));
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadSkill(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "ownerId", defaultValue = "0") Long ownerId) {
        try {
            SkillEntity saved = skillService.uploadSkill(file, ownerId);
            return ResponseEntity.ok(saved);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{id}/toggle")
    public ResponseEntity<SkillEntity> toggleSkill(@PathVariable Long id,
                                                    @RequestParam("enabled") boolean enabled) {
        return ResponseEntity.ok(skillService.toggleSkill(id, enabled));
    }

    /**
     * 删除 Skill。System skill 禁止删除，返回 403。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteSkill(@PathVariable String id) {
        // System skill 的 id 格式为 "system-xxx"
        if (id.startsWith("system-")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "System skills cannot be deleted"));
        }
        try {
            skillService.deleteSkill(Long.parseLong(id));
            return ResponseEntity.ok().build();
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid skill id: " + id));
        }
    }
}
