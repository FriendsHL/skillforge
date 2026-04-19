package com.skillforge.server.controller;

import com.skillforge.core.model.SkillDefinition;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.entity.SkillAbRunEntity;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.entity.SkillEvolutionRunEntity;
import com.skillforge.server.improve.SkillAbEvalService;
import com.skillforge.server.improve.SkillEvolutionService;
import com.skillforge.server.service.SkillService;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final SkillAbEvalService skillAbEvalService;
    private final SkillEvolutionService skillEvolutionService;

    public SkillController(SkillService skillService, SkillRegistry skillRegistry,
                           SkillAbEvalService skillAbEvalService,
                           SkillEvolutionService skillEvolutionService) {
        this.skillService = skillService;
        this.skillRegistry = skillRegistry;
        this.skillAbEvalService = skillAbEvalService;
        this.skillEvolutionService = skillEvolutionService;
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
            item.put("semver", entity.getSemver());
            item.put("parentSkillId", entity.getParentSkillId());
            item.put("usageCount", entity.getUsageCount());
            item.put("successCount", entity.getSuccessCount());
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
    public ResponseEntity<Map<String, Object>> getSkillDetail(@PathVariable String id) {
        // System skill: id starts with "system-"
        if (id.startsWith("system-")) {
            String name = id.substring("system-".length());
            return skillRegistry.getSkillDefinition(name)
                    .map(def -> {
                        Map<String, Object> detail = new java.util.LinkedHashMap<>();
                        detail.put("id", id);
                        detail.put("name", def.getName());
                        detail.put("description", def.getDescription());
                        detail.put("promptContent", def.getPromptContent());
                        detail.put("requiredTools", def.getRequiredTools());
                        detail.put("references", def.getReferences());
                        detail.put("system", true);
                        detail.put("scripts", def.getScriptPaths());
                        detail.put("enabled", true);
                        return ResponseEntity.ok(detail);
                    })
                    .orElse(ResponseEntity.notFound().build());
        }
        // User skill: numeric id
        try {
            return ResponseEntity.ok(skillService.getSkillDetail(Long.parseLong(id)));
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().build();
        }
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

    /** Version chain: returns the current skill + its ancestors + direct children. */
    @GetMapping("/{id}/versions")
    public ResponseEntity<?> getVersionChain(@PathVariable Long id) {
        try {
            List<SkillEntity> chain = skillService.getVersionChain(id);
            List<Map<String, Object>> result = chain.stream().map(s -> {
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("id", s.getId());
                m.put("name", s.getName());
                m.put("semver", s.getSemver());
                m.put("parentSkillId", s.getParentSkillId());
                m.put("enabled", s.isEnabled());
                m.put("usageCount", s.getUsageCount());
                m.put("successCount", s.getSuccessCount());
                m.put("source", s.getSource());
                m.put("createdAt", s.getCreatedAt());
                return m;
            }).collect(Collectors.toList());
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /** Fork a skill to create a new disabled variant for A/B testing (P1-3). */
    @PostMapping("/{id}/fork")
    public ResponseEntity<?> forkSkill(@PathVariable Long id,
                                       @RequestParam(value = "ownerId", defaultValue = "0") Long ownerId) {
        try {
            SkillEntity forked = skillService.forkSkill(id, ownerId);
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", forked.getId());
            m.put("name", forked.getName());
            m.put("semver", forked.getSemver());
            m.put("parentSkillId", forked.getParentSkillId());
            m.put("enabled", forked.isEnabled());
            m.put("usageCount", forked.getUsageCount());
            m.put("successCount", forked.getSuccessCount());
            m.put("source", forked.getSource());
            return ResponseEntity.ok(m);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Start an A/B test between parentSkillId (baseline) and candidateSkillId (fork). */
    @PostMapping("/{id}/abtest")
    public ResponseEntity<?> startAbTest(@PathVariable Long id,
                                          @RequestBody Map<String, Object> body) {
        Long candidateSkillId = body.containsKey("candidateSkillId")
                ? Long.parseLong(body.get("candidateSkillId").toString()) : null;
        String agentId = body.containsKey("agentId") && body.get("agentId") != null
                ? body.get("agentId").toString() : null;
        String baselineEvalRunId = body.containsKey("baselineEvalRunId")
                ? body.get("baselineEvalRunId").toString() : null;
        Long triggeredByUserId = body.containsKey("triggeredByUserId") && body.get("triggeredByUserId") != null
                ? Long.parseLong(body.get("triggeredByUserId").toString()) : 0L;
        if (candidateSkillId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "candidateSkillId is required"));
        }
        if (agentId == null || agentId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "agentId is required"));
        }
        try {
            SkillAbRunEntity abRun = skillAbEvalService.createAndTrigger(
                    id, candidateSkillId, agentId, baselineEvalRunId, triggeredByUserId);
            return ResponseEntity.accepted().body(toAbRunMap(abRun));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{id}/abtest")
    public ResponseEntity<List<Map<String, Object>>> listAbTests(@PathVariable Long id) {
        return ResponseEntity.ok(
                skillAbEvalService.getAbRunsForSkill(id).stream()
                        .map(this::toAbRunMap).collect(Collectors.toList()));
    }

    @GetMapping("/abtest/{abRunId}")
    public ResponseEntity<?> getAbTest(@PathVariable String abRunId) {
        return skillAbEvalService.getAbRun(abRunId)
                .map(r -> ResponseEntity.ok(toAbRunMap(r)))
                .orElse(ResponseEntity.notFound().build());
    }

    private Map<String, Object> toAbRunMap(SkillAbRunEntity r) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("parentSkillId", r.getParentSkillId());
        m.put("candidateSkillId", r.getCandidateSkillId());
        m.put("agentId", r.getAgentId());
        m.put("baselineEvalRunId", r.getBaselineEvalRunId());
        m.put("status", r.getStatus());
        m.put("baselinePassRate", r.getBaselinePassRate());
        m.put("candidatePassRate", r.getCandidatePassRate());
        m.put("deltaPassRate", r.getDeltaPassRate());
        m.put("promoted", r.isPromoted());
        m.put("skipReason", r.getSkipReason());
        m.put("startedAt", r.getStartedAt());
        m.put("completedAt", r.getCompletedAt());
        return m;
    }

    /** Start skill evolution — generates improved SKILL.md and triggers A/B test. */
    @PostMapping("/{id}/evolve")
    public ResponseEntity<?> evolveSkill(@PathVariable Long id,
                                          @RequestBody Map<String, Object> body) {
        String agentId = body.containsKey("agentId") && body.get("agentId") != null
                ? body.get("agentId").toString() : null;
        Long triggeredByUserId = body.containsKey("triggeredByUserId") && body.get("triggeredByUserId") != null
                ? Long.parseLong(body.get("triggeredByUserId").toString()) : null;
        if (agentId == null || agentId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "agentId is required"));
        }
        try {
            SkillEvolutionRunEntity run = skillEvolutionService.createAndTrigger(id, agentId, triggeredByUserId);
            return ResponseEntity.accepted().body(toEvolutionRunMap(run));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{id}/evolution")
    public ResponseEntity<List<Map<String, Object>>> listEvolutionRuns(@PathVariable Long id) {
        return ResponseEntity.ok(
                skillEvolutionService.getEvolutionRuns(id).stream()
                        .map(this::toEvolutionRunMap).collect(Collectors.toList()));
    }

    @GetMapping("/evolution/{evolutionRunId}")
    public ResponseEntity<?> getEvolutionRun(@PathVariable String evolutionRunId) {
        return skillEvolutionService.getEvolutionRun(evolutionRunId)
                .map(r -> ResponseEntity.ok(toEvolutionRunMap(r)))
                .orElse(ResponseEntity.notFound().build());
    }

    private Map<String, Object> toEvolutionRunMap(SkillEvolutionRunEntity r) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("skillId", r.getSkillId());
        m.put("forkedSkillId", r.getForkedSkillId());
        m.put("abRunId", r.getAbRunId());
        m.put("agentId", r.getAgentId());
        m.put("status", r.getStatus());
        m.put("successRateBefore", r.getSuccessRateBefore());
        m.put("usageCountBefore", r.getUsageCountBefore());
        m.put("improvedSkillMd", r.getImprovedSkillMd());
        m.put("evolutionReasoning", r.getEvolutionReasoning());
        m.put("failureReason", r.getFailureReason());
        m.put("triggeredByUserId", r.getTriggeredByUserId());
        m.put("createdAt", r.getCreatedAt());
        m.put("startedAt", r.getStartedAt());
        m.put("completedAt", r.getCompletedAt());
        return m;
    }

    /** Record a usage event — called after skill execution completes. */
    @PostMapping("/{id}/usage")
    public ResponseEntity<?> recordUsage(@PathVariable Long id,
                                         @RequestParam(value = "success", defaultValue = "true") boolean success) {
        try {
            skillService.recordUsage(id, success);
            return ResponseEntity.ok().build();
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
