package com.skillforge.server.controller;

import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.service.SkillService;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    @GetMapping
    public ResponseEntity<List<SkillEntity>> listSkills(
            @RequestParam(value = "ownerId", required = false) Long ownerId) {
        List<SkillEntity> skills;
        if (ownerId != null) {
            skills = skillService.listSkills(ownerId);
        } else {
            skills = skillService.listPublicSkills();
        }
        return ResponseEntity.ok(skills);
    }

    @GetMapping("/builtin")
    public ResponseEntity<List<Map<String, Object>>> listBuiltinSkills() {
        List<Map<String, Object>> builtins = skillRegistry.getAllSkills().stream()
                .map(skill -> {
                    Map<String, Object> info = new HashMap<>();
                    info.put("name", skill.getName());
                    info.put("description", skill.getDescription());
                    info.put("readOnly", skill.isReadOnly());
                    info.put("type", "builtin");
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

    /**
     * 获取 Skill 详情：SKILL.md 内容 + reference 文件 + scripts 列表
     */
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

    /**
     * 启用/禁用 Skill
     */
    @PutMapping("/{id}/toggle")
    public ResponseEntity<SkillEntity> toggleSkill(@PathVariable Long id,
                                                    @RequestParam("enabled") boolean enabled) {
        return ResponseEntity.ok(skillService.toggleSkill(id, enabled));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSkill(@PathVariable Long id) {
        skillService.deleteSkill(id);
        return ResponseEntity.ok().build();
    }
}
