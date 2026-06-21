package com.skillforge.server.controller;

import com.skillforge.core.model.SkillDefinition;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.entity.SkillAbRunEntity;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.entity.SkillEvolutionRunEntity;
import com.skillforge.server.improve.SkillAbEvalService;
import com.skillforge.server.improve.SkillEvolutionService;
import com.skillforge.server.service.SkillService;
import com.skillforge.server.skill.BatchImportResult;
import com.skillforge.server.skill.RescanReport;
import com.skillforge.server.skill.SkillCatalogReconciler;
import com.skillforge.server.skill.SkillBatchImporter;
import com.skillforge.server.skill.SkillSource;
import com.skillforge.server.skill.UserSkillLoader;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/skills")
public class SkillController {

    /**
     * SKILL-IMPORT-BATCH — only marketplace-style sources are accepted by
     * {@link #rescanMarketplace}. Internal sources (UPLOAD / SKILL_CREATOR /
     * DRAFT_APPROVE / EVOLUTION_FORK) reach the catalog through other paths
     * and must not be addressable via this endpoint or they would corrupt the
     * {@code t_skill.source} column with a wire form that does not correspond
     * to a marketplace install.
     */
    private static final Set<SkillSource> MARKETPLACE_SOURCES = EnumSet.of(
            SkillSource.CLAWHUB,
            SkillSource.GITHUB,
            SkillSource.SKILLHUB,
            SkillSource.FILESYSTEM);

    private final SkillService skillService;
    private final SkillRegistry skillRegistry;
    private final SkillAbEvalService skillAbEvalService;
    private final SkillEvolutionService skillEvolutionService;
    private final SkillCatalogReconciler reconciler;
    private final UserSkillLoader userSkillLoader;
    private final SkillBatchImporter skillBatchImporter;

    public SkillController(SkillService skillService, SkillRegistry skillRegistry,
                           SkillAbEvalService skillAbEvalService,
                           SkillEvolutionService skillEvolutionService,
                           SkillCatalogReconciler reconciler,
                           UserSkillLoader userSkillLoader,
                           SkillBatchImporter skillBatchImporter) {
        this.skillService = skillService;
        this.skillRegistry = skillRegistry;
        this.skillAbEvalService = skillAbEvalService;
        this.skillEvolutionService = skillEvolutionService;
        this.reconciler = reconciler;
        this.userSkillLoader = userSkillLoader;
        this.skillBatchImporter = skillBatchImporter;
    }

    /**
     * 返回所有 Skill（system + user），合并 SkillRegistry 中的 system SkillDefinition
     * 和数据库中的 user skill。System skill 同名时优先，跳过数据库中的重复。
     * <p>Plan r2 §8 W-1：支持 {@code ?isSystem=true|false} 过滤，DB 直查。
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listSkills(
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "isSystem", required = false) Boolean isSystem) {

        // Plan r2 §8 W-1 — explicit isSystem filter goes through DB directly.
        if (Boolean.TRUE.equals(isSystem)) {
            List<Map<String, Object>> sys = skillService.listSystemSkills().stream()
                    .map(SkillController::toMapForSystemRow).collect(Collectors.toList());
            return ResponseEntity.ok(sys);
        }
        if (Boolean.FALSE.equals(isSystem)) {
            List<SkillEntity> userSkills = userId != null
                    ? skillService.listSkills(userId)
                    : skillService.listAllSkills().stream()
                            .filter(s -> !s.isSystem()).collect(Collectors.toList());
            List<Map<String, Object>> result = userSkills.stream()
                    .map(SkillController::toMapForUserRow).collect(Collectors.toList());
            return ResponseEntity.ok(result);
        }

        // Default behavior — same as before (registry + DB merged).
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
                // Built-in skills are never curator-archived.
                item.put("archived", false);
                item.put("archivedAt", null);
                item.put("archiveReason", null);
                item.put("curatorExempt", false);
                result.add(item);
            }
        }

        List<SkillEntity> dbSkills;
        if (userId != null) {
            dbSkills = skillService.listSkills(userId);
        } else {
            dbSkills = skillService.listAllSkills();
        }
        for (SkillEntity entity : dbSkills) {
            if (entity.isSystem()) {
                continue; // already covered by registry pass above
            }
            if (systemSkillNames.contains(entity.getName())) {
                continue; // system 版本优先，跳过数据库中的同名 user skill
            }
            result.add(toMapForUserRow(entity));
        }

        return ResponseEntity.ok(result);
    }

    private static Map<String, Object> toMapForUserRow(SkillEntity entity) {
        Map<String, Object> item = new HashMap<>();
        item.put("id", entity.getId());
        item.put("name", entity.getName());
        item.put("description", entity.getDescription());
        item.put("requiredTools", entity.getRequiredTools());
        item.put("enabled", entity.isEnabled());
        item.put("system", entity.isSystem());
        // SKILL-DASHBOARD-POLISH §A — needed by FE SkillTable to bucket rows by
        // (ownerId, name) for version aggregation. Without this, multi-tenant
        // instances would collapse different users' same-name skills into one row.
        item.put("ownerId", entity.getOwnerId());
        // Plan r2 §8 W-1 — alias for FE that prefers `isSystem` (matches DTO field name).
        item.put("isSystem", entity.isSystem());
        item.put("source", entity.getSource());
        item.put("semver", entity.getSemver());
        item.put("parentSkillId", entity.getParentSkillId());
        item.put("usageCount", entity.getUsageCount());
        item.put("successCount", entity.getSuccessCount());
        item.put("failureCount", entity.getFailureCount());
        // P1-D §T8 — governance fields for FE catalog UI (artifactStatus, skillPath,
        // shadowedBy, lastScannedAt). artifactStatus defaults to "active" when null
        // (legacy rows pre-V33 may not have it set).
        item.put("artifactStatus", entity.getArtifactStatus() != null
                ? entity.getArtifactStatus() : "active");
        item.put("skillPath", entity.getSkillPath());
        item.put("shadowedBy", entity.getShadowedBy());
        item.put("lastScannedAt", entity.getLastScannedAt());
        // SKILL-CURATOR human-in-loop — archival visibility for the FE (archived
        // tag + restore button). `archived` is the derived boolean the FE keys off;
        // archivedAt is the ISO instant (or null), archiveReason the machine tag.
        item.put("archived", entity.getArchivedAt() != null);
        item.put("archivedAt", entity.getArchivedAt());
        item.put("archiveReason", entity.getArchiveReason());
        item.put("curatorExempt", entity.isCuratorExempt());
        return item;
    }

    private static Map<String, Object> toMapForSystemRow(SkillEntity entity) {
        Map<String, Object> item = toMapForUserRow(entity);
        item.put("system", true);
        item.put("isSystem", true);
        item.put("source", "system");
        return item;
    }

    /**
     * 返回所有工具（Bash, Read 等 Java Skill），不包含 ClawHub 等已迁移为文件 Skill 的。
     */
    @GetMapping("/builtin")
    public ResponseEntity<List<Map<String, Object>>> listBuiltinSkills() {
        List<Map<String, Object>> builtins = skillRegistry.getAllTools().stream()
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

    /**
     * Plan r2 §8 — userId required (no defaultValue=0 fallback).
     *
     * <p>SKILL-CREATOR-PHASE-1.6 F2 (2026-05-19): optional
     * {@code targetAgentId} param lets operators (typically via the
     * dashboard upload form) explicitly select the agent the skill-eval gate
     * should run against. When present AND the uploaded zip carries
     * {@code evals/evals.json}, the upload still completes synchronously
     * (legacy: skill registered + persisted), and additionally fires the
     * eval gate against the chosen agent. When omitted, the legacy upload
     * path runs unchanged (no eval); operators can re-trigger evaluation
     * later via {@code POST /api/skill-drafts/{id}/evaluate}.
     */
    @PostMapping("/upload")
    public ResponseEntity<?> uploadSkill(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "userId", required = true) Long userId,
            @RequestParam(value = "targetAgentId", required = false) Long targetAgentId) {
        try {
            // BE writes ownerId from the validated userId — never accepts ownerId from FE.
            SkillEntity saved = skillService.uploadSkill(file, userId, targetAgentId);
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
     * SKILL-CURATOR human-in-loop — manually restore a curator-archived skill.
     * Re-enables the row, clears archive bookkeeping, and marks it
     * {@code curatorExempt=true} so the curator won't re-archive it. Returns the
     * updated skill item in the same Map shape as {@link #listSkills} so the FE can
     * patch its list in place. No-op (returns the row unchanged) when not archived.
     *
     * <p>{@code userId} mirrors the other write endpoints (upload / delete / toggle):
     * the BE uses it for audit and ownership; the FE injects it from {@code useAuth()}.
     */
    @PostMapping("/{id}/restore")
    public ResponseEntity<?> restoreSkill(@PathVariable Long id,
                                          @RequestParam(value = "userId", required = false) Long userId) {
        try {
            SkillEntity restored = skillService.restoreArchivedSkill(id, userId);
            return ResponseEntity.ok(toMapForUserRow(restored));
        } catch (RuntimeException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("not found")) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.badRequest().body(Map.of("error", msg));
        }
    }

    /**
     * 删除 Skill。System skill 禁止删除，返回 403。
     * <p>Plan r2 §8 — both the legacy "system-{name}" path AND any numeric id whose row has
     * {@code is_system=true} must return 403.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteSkill(@PathVariable String id) {
        if (id.startsWith("system-")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "System skills cannot be deleted"));
        }
        try {
            Long numericId = Long.parseLong(id);
            // Defense in depth: even with numeric id, refuse delete if row is is_system.
            if (skillService.findById(numericId).map(SkillEntity::isSystem).orElse(false)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "System skills cannot be deleted"));
            }
            skillService.deleteSkill(numericId);
            return ResponseEntity.ok().build();
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid skill id: " + id));
        }
    }

    /**
     * SKILL-DASHBOARD-POLISH-V2 §I — version tree (ancestors + current + descendants)
     * for the drawer's "Version Tree" tab. Bounded depth (max 10 each direction)
     * for cycle safety. {@code userId} is required to enforce ownership; system
     * skills are not exposed via this endpoint.
     */
    @GetMapping("/{id}/version-tree")
    public ResponseEntity<?> getVersionTree(@PathVariable Long id,
                                            @RequestParam(value = "userId", required = true) Long userId,
                                            @RequestParam(value = "format", required = false) String format) {
        try {
            // SKILL-DASHBOARD-POLISH-V2.5 §7 — `?format=tree` returns a single
            // recursive root tree (FE-friendly) instead of the V2 default
            // {ancestors, current, descendants} 3-segment shape (kept default
            // for V2 FE compat).
            if ("tree".equalsIgnoreCase(format)) {
                return ResponseEntity.ok(skillService.getVersionTreeRoot(id, userId));
            }
            return ResponseEntity.ok(skillService.getVersionTree(id, userId));
        } catch (RuntimeException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("not found")) {
                return ResponseEntity.notFound().build();
            }
            if (msg.contains("does not own") || msg.contains("system skill")) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", msg));
            }
            return ResponseEntity.badRequest().body(Map.of("error", msg));
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

    /**
     * Fork a skill to create a new disabled variant for A/B testing (P1-3).
     * Plan r2 §8 — userId required.
     */
    @PostMapping("/{id}/fork")
    public ResponseEntity<?> forkSkill(@PathVariable Long id,
                                       @RequestParam(value = "userId", required = true) Long userId) {
        try {
            SkillEntity forked = skillService.forkSkill(id, userId);
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

    /**
     * Start an A/B test between parentSkillId (baseline) and candidateSkillId (fork).
     * Plan r2 §8 — agentId required (validated below; missing → 400).
     */
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
                ? Long.parseLong(body.get("triggeredByUserId").toString()) : null;
        // EVAL-DATASET-LAYER V1 r2 mandatory fix (V113): accept optional
        // datasetVersionId so the SkillAbPanel's dataset selection actually
        // pins the run (pipeline.md severity B silent-failure guard: previously
        // Jackson silently dropped this field).
        String datasetVersionId = body.containsKey("datasetVersionId") && body.get("datasetVersionId") != null
                ? body.get("datasetVersionId").toString() : null;
        if (candidateSkillId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "candidateSkillId is required"));
        }
        if (agentId == null || agentId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "agentId is required"));
        }
        if (triggeredByUserId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "triggeredByUserId is required"));
        }
        try {
            SkillAbRunEntity abRun = skillAbEvalService.createAndTrigger(
                    id, candidateSkillId, agentId, baselineEvalRunId, triggeredByUserId,
                    datasetVersionId);
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

    /**
     * FLYWHEEL-VISUAL-STATUS Phase 2 — paginated global A/B run listing for
     * the observability panel. All filters optional:
     * <ul>
     *   <li>{@code agentId} — exact-match filter on the run's owning agent;
     *       null = cross-agent listing</li>
     *   <li>{@code status} — exact match against {@code t_skill_ab_run.status}
     *       (e.g. {@code PENDING} / {@code RUNNING} / {@code COMPLETED} /
     *       {@code FAILED} / {@code SKIPPED})</li>
     *   <li>{@code surfaceType} — guard parameter. {@code t_skill_ab_run} has
     *       no surface_type column today (all rows are skill-surface), so the
     *       controller accepts only {@code skill} / null / blank and returns
     *       400 for any other value. Future migrations may add the column;
     *       wiring the param now keeps the FE contract forward-compatible.</li>
     *   <li>{@code page} / {@code size} — 1-based page (matches
     *       {@link SkillDraftController#listDraftsPaged}); size clamped 1-100</li>
     * </ul>
     *
     * <p>Response envelope mirrors {@code listDraftsPaged} so the FE can reuse
     * the same paging shape (items / page / pageSize / total / totalPages).
     */
    @GetMapping("/abtest")
    public ResponseEntity<?> listAbTestsGlobal(
            @RequestParam(name = "agentId", required = false) String agentId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "surfaceType", required = false) String surfaceType,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize) {
        // Surface guard — only `skill` surface has rows in t_skill_ab_run today.
        // Reject explicit non-skill values loudly so FE bug isn't hidden by an
        // empty list. null / blank / "skill" all map to the same query.
        if (surfaceType != null && !surfaceType.isBlank() && !"skill".equals(surfaceType)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "surfaceType must be 'skill' or omitted "
                            + "(got '" + surfaceType + "'); t_skill_ab_run has no surface_type column"));
        }
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, pageSize));
        org.springframework.data.domain.Pageable pageable =
                org.springframework.data.domain.PageRequest.of(safePage - 1, safeSize);
        org.springframework.data.domain.Page<SkillAbRunEntity> result =
                skillAbEvalService.getAbRunsByFilters(agentId, status, pageable);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("items", result.getContent().stream()
                .map(this::toAbRunMap)
                .collect(Collectors.toList()));
        body.put("page", safePage);
        body.put("pageSize", safeSize);
        body.put("total", result.getTotalElements());
        body.put("totalPages", result.getTotalPages());
        return ResponseEntity.ok(body);
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
        m.put("failureReason", r.getFailureReason());
        m.put("abScenarioResultsJson", r.getAbScenarioResultsJson());
        // SKILL-DASHBOARD-POLISH §C — derived from skipReason prefix; BE doesn't
        // store a dedicated column. FE renders distinct "Promoted (manual)" tag.
        boolean manuallyPromoted = r.getSkipReason() != null
                && r.getSkipReason().startsWith("manual override");
        m.put("manuallyPromoted", manuallyPromoted);
        m.put("startedAt", r.getStartedAt());
        m.put("completedAt", r.getCompletedAt());
        // EVAL-DATASET-LAYER V1 r2 (V113): emit the pinned dataset version id
        // so the FE can render "Dataset: <name>@v<n>" by lazy-fetching
        // /api/eval/dataset-versions/{id}. null = legacy/ephemeral run.
        m.put("datasetVersionId", r.getDatasetVersionId());
        return m;
    }

    /**
     * Start skill evolution — generates improved SKILL.md and triggers A/B test.
     * Plan r2 §8 — agentId / triggeredByUserId required.
     */
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
        if (triggeredByUserId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "triggeredByUserId is required"));
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

    /**
     * SKILL-EVOLVE-LOOP Phase 2 — single-skill direct evaluation. Runs held_out
     * scenarios against the current skill (no fork, no candidate, no delta) and
     * persists a row in {@code t_skill_eval_history}. Synchronous: caller waits
     * for composite_score (~30s/scenario × N scenarios — typical = 1-3 minutes).
     *
     * <p>{@code agentId} is required because runBaselineOnly drives the eval
     * via AgentLoopEngine which needs an AgentDefinition. Pick any agent that
     * uses this skill (the FE picks the first owner agent by default).
     */
    @PostMapping("/{id}/evaluate")
    public ResponseEntity<?> evaluateSkill(@PathVariable Long id,
                                           @RequestParam(value = "userId", required = true) Long userId,
                                           @RequestParam(value = "agentId", required = true) String agentId,
                                           @RequestParam(value = "datasetId", required = false) String datasetId) {
        if (agentId == null || agentId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "agentId is required"));
        }
        try {
            // W1 r1 — Service owns the run + DTO mapping, controller stays thin
            // (delegates to runBaselineOnly + getEvalHistoryForSkill once persisted).
            skillAbEvalService.runBaselineOnly(id, agentId, userId, datasetId, "manual");
            List<Map<String, Object>> latest = skillAbEvalService.getEvalHistoryForSkill(id, 1);
            return ResponseEntity.ok(latest.isEmpty() ? Map.of() : latest.get(0));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * SKILL-EVOLVE-LOOP Phase 2 — eval history curve for a skill. Returns the
     * most recent {@code limit} rows (default 20, capped at 100) ordered newest first.
     *
     * <p>W1 r1 — query + DTO mapping live in {@link SkillAbEvalService} so the
     * controller has no Repository dependency.
     */
    @GetMapping("/{id}/eval-history")
    public ResponseEntity<List<Map<String, Object>>> getEvalHistory(
            @PathVariable Long id,
            @RequestParam(value = "userId", required = true) Long userId,
            @RequestParam(value = "limit", required = false, defaultValue = "20") Integer limit) {
        int requested = limit == null ? 20 : limit;
        return ResponseEntity.ok(skillAbEvalService.getEvalHistoryForSkill(id, requested));
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

    /**
     * P1-D §T7 — Manual rescan: force a full reconcile of system + runtime roots
     * with t_skill, then re-register enabled rows in {@link SkillRegistry}. Returns
     * a {@link RescanReport} (created/updated/missing/invalid/shadowed/disabledDuplicates).
     *
     * <p>Idempotent: safe to call multiple times; running after the periodic
     * startup reconcile is the supported way to pick up out-of-band disk writes
     * without restarting the server.
     */
    @PostMapping("/rescan")
    public ResponseEntity<Map<String, Object>> rescan() {
        RescanReport report = reconciler.fullRescan();
        // Re-register registry. UserSkillLoader.loadAll triggers reconcile internally,
        // but since we just ran fullRescan, the second reconcile is a near-no-op (hash
        // unchanged). We still call it to ensure registry is in sync with t_skill.
        try {
            userSkillLoader.loadAll();
        } catch (Exception ignored) {
            // logged inside loadAll
        }
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("created", report.created());
        body.put("updated", report.updated());
        body.put("missing", report.missing());
        body.put("invalid", report.invalid());
        body.put("shadowed", report.shadowed());
        body.put("disabledDuplicates", report.disabledDuplicates());
        return ResponseEntity.ok(body);
    }

    /**
     * SKILL-IMPORT-BATCH — scan every {@code allowed-source-roots} root for
     * first-level subdirs and call {@code SkillBatchImporter.batchImportFromMarketplace}
     * to register each into the catalog. The same {@link SkillSource} is
     * applied to every subdir; the wire form (e.g. {@code clawhub} /
     * {@code github} / {@code skillhub} / {@code filesystem}) maps to
     * {@link SkillSource} via uppercase + dash→underscore.
     *
     * <p>Auth: mirrors {@code /upload}, {@code /fork}, etc. — the BE writes
     * {@code ownerId} from the validated {@code userId} query param and never
     * accepts an {@code ownerId} parameter from the FE.
     *
     * @param sourceWire wire-format source name; one of {@code clawhub} /
     *                   {@code github} / {@code skillhub} / {@code filesystem}
     * @param userId     calling user id; used as {@code ownerId} for every
     *                   imported row (P1 §B-1, "BE writes ownerId from the
     *                   validated userId — never accepts ownerId from FE")
     * @return 200 with {@link BatchImportResult} JSON; 400 if {@code source}
     *         is not a recognised {@link SkillSource} wire name
     */
    /**
     * SKILL-DASHBOARD-POLISH B — return the raw SKILL.md content for a user skill.
     * Used by the Evolution Diff tab to show "before" (parent) and "after" (improved)
     * SKILL.md side-by-side. Returns:
     * <ul>
     *   <li>200 + {@code {content, path}} when SKILL.md exists</li>
     *   <li>200 + {@code {content:"", path}} (with optional {@code error}) when path is missing
     *       or the file is not on disk — FE renders "no SKILL.md" rather than crashing.</li>
     *   <li>403 when caller is not the owner and the skill is not public</li>
     *   <li>500 on transient I/O failure</li>
     * </ul>
     */
    /**
     * V2.5 — recursive file tree of the skill's package directory.
     *
     * <p>Skills are file packages (SKILL.md + scripts/ + references/ + assets/ + hooks/),
     * not just a single .md. {@link #getSkillDetail} only collects a fixed set of names
     * (SKILL.md, reference.md, docs/*.md, scripts/*); files under {@code references/} or
     * {@code assets/} were silently dropped. This endpoint walks the directory generically.
     *
     * <p>Skips conventionally-internal artifacts: {@code .clawhub/}, {@code _meta.json},
     * {@code .DS_Store}.
     *
     * <p>Response:
     * <pre>{ "path": "/abs/skill/dir", "files": [ {"path": "SKILL.md", "size": 1234,
     * "mtime": "2026-..."}, ... ] }</pre>
     */
    @GetMapping("/{id}/files")
    public ResponseEntity<?> listSkillFiles(@PathVariable String id,
                                            @RequestParam(value = "userId", required = false) Long userId) {
        Path skillDir = skillService.resolveSkillDir(id, userId);
        if (skillDir == null) {
            return ResponseEntity.notFound().build();
        }
        if (!Files.isDirectory(skillDir)) {
            return ResponseEntity.ok(Map.of(
                    "path", skillDir.toString(),
                    "files", List.of(),
                    "error", "skill directory not found on disk"));
        }
        List<Map<String, Object>> files = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(skillDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        Path rel = skillDir.relativize(p);
                        String first = rel.getNameCount() > 0 ? rel.getName(0).toString() : "";
                        // Skip internal / metadata / OS junk.
                        if (".clawhub".equals(first)) return false;
                        String fileName = p.getFileName().toString();
                        if ("_meta.json".equals(fileName) || ".DS_Store".equals(fileName)) return false;
                        return true;
                    })
                    .sorted()
                    .forEach(p -> {
                        Map<String, Object> entry = new java.util.LinkedHashMap<>();
                        entry.put("path", skillDir.relativize(p).toString().replace('\\', '/'));
                        try {
                            entry.put("size", Files.size(p));
                        } catch (IOException ignored) {
                            entry.put("size", -1L);
                        }
                        try {
                            entry.put("mtime", Files.getLastModifiedTime(p).toInstant().toString());
                        } catch (IOException ignored) {
                            // mtime best-effort
                        }
                        files.add(entry);
                    });
        } catch (IOException e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to walk skill dir: " + e.getMessage()));
        }
        return ResponseEntity.ok(Map.of(
                "path", skillDir.toString(),
                "files", files));
    }

    /**
     * V2.5 — read content of a specific file inside the skill's package directory.
     * Path traversal is rejected (file must resolve under skillDir).
     *
     * <p>Returns {@code {content, path, size, mtime}}.
     */
    @GetMapping("/{id}/files/content")
    public ResponseEntity<?> readSkillFile(@PathVariable String id,
                                           @RequestParam("path") String relPath,
                                           @RequestParam(value = "userId", required = false) Long userId) {
        Path skillDir = skillService.resolveSkillDir(id, userId);
        if (skillDir == null) {
            return ResponseEntity.notFound().build();
        }
        if (relPath == null || relPath.isBlank() || relPath.contains("..")) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid path"));
        }
        Path target = skillDir.resolve(relPath).normalize();
        if (!target.startsWith(skillDir)) {
            return ResponseEntity.badRequest().body(Map.of("error", "path traversal rejected"));
        }
        if (!Files.exists(target) || !Files.isRegularFile(target)) {
            return ResponseEntity.notFound().build();
        }
        try {
            String content = Files.readString(target);
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("path", relPath);
            body.put("content", content);
            body.put("size", Files.size(target));
            try {
                body.put("mtime", Files.getLastModifiedTime(target).toInstant().toString());
            } catch (IOException ignored) {}
            return ResponseEntity.ok(body);
        } catch (java.nio.charset.MalformedInputException e) {
            return ResponseEntity.ok(Map.of(
                    "path", relPath,
                    "content", "[Binary file — preview not supported]",
                    "binary", true));
        } catch (IOException e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to read file: " + e.getMessage()));
        }
    }

    @GetMapping("/{id}/skill-md")
    public ResponseEntity<?> getSkillMd(@PathVariable Long id,
                                        @RequestParam(value = "userId", required = false) Long userId) {
        SkillService.SkillMdReadResult result = skillService.readSkillMd(id, userId);
        if (result instanceof SkillService.SkillMdReadResult.Forbidden) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        if (result instanceof SkillService.SkillMdReadResult.NoPath) {
            Map<String, Object> body = new HashMap<>();
            body.put("content", "");
            body.put("path", null);
            return ResponseEntity.ok(body);
        }
        if (result instanceof SkillService.SkillMdReadResult.NotOnDisk notOnDisk) {
            Map<String, Object> body = new HashMap<>();
            body.put("content", "");
            body.put("path", notOnDisk.path());
            body.put("error", "SKILL.md not found");
            return ResponseEntity.ok(body);
        }
        if (result instanceof SkillService.SkillMdReadResult.Loaded loaded) {
            Map<String, Object> body = new HashMap<>();
            body.put("content", loaded.content());
            body.put("path", loaded.path());
            if (loaded.updatedAt() != null) {
                body.put("updatedAt", loaded.updatedAt());
            }
            return ResponseEntity.ok(body);
        }
        if (result instanceof SkillService.SkillMdReadResult.ReadFailed readFailed) {
            return ResponseEntity.internalServerError().body(Map.of("error", readFailed.message()));
        }
        throw new IllegalStateException("unhandled SkillMdReadResult variant: " + result);
    }

    /**
     * V2.5 manual edit — write SKILL.md content to a candidate skill's isolated
     * skillPath. Gated to disabled candidates (parentSkillId != null && !enabled)
     * so users can't bypass the A/B path by editing the active version directly.
     *
     * <p>After write, re-registers the candidate's SkillDefinition in SkillRegistry
     * so subsequent A/B runs see the updated content.
     *
     * <p>Body: {@code { "content": "...", "userId": Long }}
     */
    @PutMapping("/{id}/skill-md")
    public ResponseEntity<?> updateSkillMd(@PathVariable Long id,
                                           @RequestBody Map<String, Object> body) {
        Long userId = body.get("userId") instanceof Number n ? n.longValue() : null;
        String content = body.get("content") instanceof String s ? s : null;
        SkillService.SkillMdWriteResult result = skillService.writeSkillMd(id, content, userId);
        if (result instanceof SkillService.SkillMdWriteResult.NotFound) {
            return ResponseEntity.notFound().build();
        }
        if (result instanceof SkillService.SkillMdWriteResult.SystemForbidden) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Cannot edit system skill SKILL.md"));
        }
        if (result instanceof SkillService.SkillMdWriteResult.NotEditableCandidate) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error",
                            "SKILL.md edit only allowed on disabled candidate (parentSkillId != null && enabled=false). "
                            + "Use Fork & A/B Test to create an editable candidate first."));
        }
        if (result instanceof SkillService.SkillMdWriteResult.OwnerForbidden) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        if (result instanceof SkillService.SkillMdWriteResult.ContentRequired) {
            return ResponseEntity.badRequest().body(Map.of("error", "content (string) required"));
        }
        if (result instanceof SkillService.SkillMdWriteResult.NoPath) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Candidate has no skillPath. Re-fork from parent first."));
        }
        if (result instanceof SkillService.SkillMdWriteResult.Written written) {
            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "id", written.id(),
                    "path", written.path(),
                    "bytes", written.bytes()));
        }
        if (result instanceof SkillService.SkillMdWriteResult.WriteFailed writeFailed) {
            return ResponseEntity.internalServerError().body(Map.of("error", writeFailed.message()));
        }
        throw new IllegalStateException("unhandled SkillMdWriteResult variant: " + result);
    }

    /**
     * SKILL-DASHBOARD-POLISH D — manual promote override. The auto-promote thresholds
     * (delta ≥ 15pp + candidate ≥ 40pp, see {@link SkillAbEvalService}) sometimes
     * reject candidates the operator wants to ship anyway; this endpoint reuses the
     * same {@code promoteCandidate} path but is gated on {@code status==COMPLETED}
     * and not-already-promoted.
     */
    @PostMapping("/abrun/{abRunId}/promote-manual")
    public ResponseEntity<?> manualPromote(@PathVariable String abRunId,
                                           @RequestBody(required = false) Map<String, Object> body) {
        Long triggeredByUserId = body != null && body.get("triggeredByUserId") != null
                ? Long.parseLong(body.get("triggeredByUserId").toString()) : null;
        try {
            SkillAbRunEntity result = skillAbEvalService.manualPromote(abRunId, triggeredByUserId);
            return ResponseEntity.ok(toAbRunMap(result));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * SKILL-DASHBOARD-POLISH D — rollback a promoted candidate (v2) back to its parent (v1).
     * Reuses {@link SkillService#rollbackToParent}, which honors the V64 partial unique
     * order (disable candidate first + flush, then enable parent).
     */
    @PostMapping("/{id}/rollback")
    public ResponseEntity<?> rollbackSkill(@PathVariable Long id,
                                           @RequestBody(required = false) Map<String, Object> body) {
        Long triggeredByUserId = body != null && body.get("triggeredByUserId") != null
                ? Long.parseLong(body.get("triggeredByUserId").toString()) : null;
        try {
            SkillEntity result = skillService.rollbackToParent(id, triggeredByUserId);
            return ResponseEntity.ok(toMapForUserRow(result));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/rescan-marketplace")
    public ResponseEntity<?> rescanMarketplace(
            @RequestParam("source") String sourceWire,
            @RequestParam(value = "userId", required = true) Long userId) {
        SkillSource source;
        try {
            source = SkillSource.valueOf(sourceWire.toUpperCase().replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "invalid source: " + sourceWire));
        }
        // Defence in depth: SkillSource.valueOf would otherwise accept
        // server-internal allocation sources (upload / skill_creator /
        // draft_approve / evolution_fork) which must NOT route through the
        // marketplace rescan endpoint. PRD F1 limits this endpoint to
        // {clawhub, github, skillhub, filesystem}.
        if (!MARKETPLACE_SOURCES.contains(source)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error",
                            "source must be one of: clawhub, github, skillhub, filesystem (got: "
                                    + sourceWire + ")"));
        }
        BatchImportResult result = skillBatchImporter.batchImportFromMarketplace(source, userId);
        return ResponseEntity.ok(result);
    }
}
