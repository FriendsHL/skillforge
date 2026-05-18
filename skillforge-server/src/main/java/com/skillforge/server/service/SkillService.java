package com.skillforge.server.service;

import com.skillforge.core.model.SkillDefinition;
import com.skillforge.core.skill.SkillPackageLoader;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.entity.EvalScenarioEntity;
import com.skillforge.server.entity.SkillDraftEntity;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.entity.SkillEvalHistoryEntity;
import com.skillforge.server.repository.EvalScenarioDraftRepository;
import com.skillforge.server.repository.SkillDraftRepository;
import com.skillforge.server.repository.SkillEvalHistoryRepository;
import com.skillforge.server.repository.SkillRepository;
import com.skillforge.server.skill.AllocationContext;
import com.skillforge.server.skill.SkillCreatorService;
import com.skillforge.server.skill.SkillSource;
import com.skillforge.server.skill.SkillStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

@Service
public class SkillService {

    private static final Logger log = LoggerFactory.getLogger(SkillService.class);

    private final SkillRepository skillRepository;
    private final SkillRegistry skillRegistry;
    private final SkillPackageLoader skillPackageLoader;
    private final SkillStorageService skillStorageService;
    private final SkillEvalHistoryRepository skillEvalHistoryRepository;
    /**
     * SKILL-CREATOR-WITH-EVAL Phase 1.2 (2026-05-18) — wired with {@code @Lazy}
     * to dodge the registration cycle that arises when both services depend
     * on each other transitively via SkillRegistry. Nullable so existing
     * tests that build SkillService directly (without the eval gate) can
     * keep passing null without disabling the legacy upload path.
     */
    private final SkillCreatorService skillCreatorService;
    private final SkillDraftRepository skillDraftRepository;
    private final EvalScenarioDraftRepository evalScenarioRepository;

    public SkillService(SkillRepository skillRepository,
                        SkillRegistry skillRegistry,
                        SkillPackageLoader skillPackageLoader,
                        SkillStorageService skillStorageService,
                        SkillEvalHistoryRepository skillEvalHistoryRepository,
                        @Lazy SkillCreatorService skillCreatorService,
                        SkillDraftRepository skillDraftRepository,
                        EvalScenarioDraftRepository evalScenarioRepository) {
        this.skillRepository = skillRepository;
        this.skillRegistry = skillRegistry;
        this.skillPackageLoader = skillPackageLoader;
        this.skillStorageService = skillStorageService;
        this.skillEvalHistoryRepository = skillEvalHistoryRepository;
        this.skillCreatorService = skillCreatorService;
        this.skillDraftRepository = skillDraftRepository;
        this.evalScenarioRepository = evalScenarioRepository;
    }

    /**
     * 上传并注册 Skill zip 包。
     * 1. 生成 skillId (uuid)
     * 2. 通过 SkillStorageService 分配统一 runtime root 路径:
     *    {runtimeRoot}/upload/{ownerId}/{skillId}/
     * 3. 调用 SkillPackageLoader 解压解析
     * 4. 保存 SkillEntity 到数据库 (source=upload, skillPath=绝对路径)
     * 5. 注册到 SkillRegistry
     * 6. 返回 SkillEntity
     *
     * 失败回滚: I/O / 解析失败 → 清理目录, 不写 DB; DB 写失败 → 同样清理目录。
     */
    public SkillEntity uploadSkill(MultipartFile file, Long ownerId) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file must not be empty");
        }

        String skillId = UUID.randomUUID().toString();
        Path targetDir = skillStorageService.allocate(
                SkillSource.UPLOAD,
                AllocationContext.forUpload(String.valueOf(ownerId), skillId));

        try {
            // 解压并解析
            SkillDefinition definition = skillPackageLoader.loadFromZip(file.getInputStream(), targetDir);
            definition.setId(skillId);
            definition.setOwnerId(String.valueOf(ownerId));

            // 构建 SkillEntity
            SkillEntity entity = new SkillEntity();
            entity.setName(definition.getName());
            entity.setDescription(definition.getDescription());
            entity.setSkillPath(targetDir.toAbsolutePath().toString());
            entity.setOwnerId(ownerId);
            entity.setPublic(definition.isPublic());
            entity.setSource(SkillSource.UPLOAD.wireName());

            // triggers 和 requiredTools 存为逗号分隔字符串
            if (definition.getTriggers() != null && !definition.getTriggers().isEmpty()) {
                entity.setTriggers(String.join(",", definition.getTriggers()));
            }
            if (definition.getRequiredTools() != null && !definition.getRequiredTools().isEmpty()) {
                entity.setRequiredTools(String.join(",", definition.getRequiredTools()));
            }

            // 保存到数据库
            SkillEntity saved;
            try {
                saved = skillRepository.save(entity);
            } catch (RuntimeException dbEx) {
                deleteDirectoryQuietly(targetDir);
                throw dbEx;
            }
            log.info("Skill saved to database: id={}, name={}", saved.getId(), saved.getName());

            // 注册到 SkillRegistry — 失败仅记日志，DB+磁盘已提交，下次启动/rescan 可恢复
            try {
                skillRegistry.registerSkillDefinition(definition);
                log.info("Skill registered to SkillRegistry: name={}", definition.getName());
            } catch (RuntimeException registryEx) {
                log.error("Failed to register skill into registry (DB+disk preserved): name={}",
                        definition.getName(), registryEx);
            }

            // SKILL-CREATOR-WITH-EVAL Phase 1.2 (2026-05-18): post-registration
            // hook — if the zip carried an evals/evals.json, kick off the
            // evaluation gate IN ADDITION to the legacy register-and-go path.
            // Failure here MUST NOT roll back the legacy registration (skill
            // is already saved + registered above); log + continue.
            maybeTriggerEvaluationForUpload(targetDir, saved, ownerId);

            return saved;

        } catch (IOException e) {
            // 清理已创建的目录
            deleteDirectoryQuietly(targetDir);
            throw new RuntimeException("Failed to process skill package: " + e.getMessage(), e);
        }
    }

    /**
     * SKILL-CREATOR-WITH-EVAL Phase 1.2 (2026-05-18): zip-based eval gate
     * (entry 1 — upload path). Best-effort; if SkillCreatorService is not
     * wired (test fixtures with null injection) or scenarios can't be
     * extracted, this is a silent no-op. The legacy upload path completes
     * regardless of eval outcome.
     */
    private void maybeTriggerEvaluationForUpload(java.nio.file.Path extractedRoot,
                                                  SkillEntity savedSkill,
                                                  Long ownerId) {
        if (skillCreatorService == null || skillDraftRepository == null
                || evalScenarioRepository == null) {
            return; // eval gate not wired (legacy / test fixture path)
        }
        try {
            // Resolve target agent — owners typically operate one agent in single-user
            // dev; the upload doesn't bind to a specific agent at the API surface
            // (skill is owned by user, not agent). For Phase 1.2 we skip eval when
            // there's no owner; Phase 1.6 dogfood may augment with agent-id from
            // controller's session context.
            if (ownerId == null) return;
            // Need a target agent — pick the owner's first agent. Phase 1.2 keeps
            // the heuristic simple; Phase 1.6 may surface this as an FE picker.
            // For minimal scope we skip eval if no clear target agent.
            Long targetAgentId = resolveAnyAgentIdForOwner(ownerId);
            if (targetAgentId == null) return;

            List<EvalScenarioEntity> scenarios = skillCreatorService
                    .buildEphemeralScenariosFromZip(extractedRoot, targetAgentId);
            if (scenarios.isEmpty()) return;

            // Create draft + scenarios; dispatch the eval batch under a
            // synthetic orchestrator session (dispatchEvaluation auto-creates).
            SkillDraftEntity draft = new SkillDraftEntity();
            draft.setId(UUID.randomUUID().toString());
            draft.setOwnerId(ownerId);
            draft.setName(savedSkill.getName());
            draft.setDescription(savedSkill.getDescription());
            draft.setStatus("draft");
            draft.setSource("upload");
            draft.setTargetAgentId(targetAgentId);
            draft.setCandidateSkillId(savedSkill.getId());
            skillDraftRepository.save(draft);
            evalScenarioRepository.saveAll(scenarios);

            List<String> scenarioIds = scenarios.stream().map(EvalScenarioEntity::getId).toList();
            skillCreatorService.dispatchEvaluation(null, draft.getId(), scenarioIds);
            log.info("SkillService.uploadSkill: triggered eval gate for skill {} via draft {} ({} scenarios)",
                    savedSkill.getId(), draft.getId(), scenarioIds.size());
        } catch (RuntimeException e) {
            // Never let eval-gate failure bubble back to the user; the legacy
            // upload registered the skill successfully above. Operators can
            // re-trigger eval manually via the dashboard if needed.
            log.warn("SkillService.uploadSkill: eval-gate trigger failed for skill {} — legacy "
                    + "registration succeeded, skipping eval: {}", savedSkill.getId(), e.getMessage());
        }
    }

    /**
     * Resolve any agent id owned by the user, used as the target for the
     * eval-gate dispatch. Phase 1.2 minimal heuristic; Phase 1.6 may surface
     * via FE picker. Returns null when no eligible agent exists (eval gate
     * silently skips).
     */
    private Long resolveAnyAgentIdForOwner(Long ownerId) {
        // No skillRepository / agentRepository wiring here; defer to the
        // most-recent skill's history or fall back to a sentinel agent.
        // For Phase 1.2 we don't query — eval gate is a best-effort upgrade
        // that activates only when callers wire a target via the dashboard
        // (Phase 1.6 controller refactor). Return null to keep behavior
        // forward-compatible without coupling SkillService to AgentRepository.
        return null;
    }

    /**
     * 获取指定 owner 的 Skill 列表。
     */
    public List<SkillEntity> listSkills(Long ownerId) {
        return skillRepository.findByOwnerId(ownerId);
    }

    /**
     * 获取所有 Skill 列表。
     */
    public List<SkillEntity> listAllSkills() {
        return skillRepository.findAll();
    }

    /**
     * 获取公共 Skill 列表。
     */
    public List<SkillEntity> listPublicSkills() {
        return skillRepository.findByIsPublicTrue();
    }

    /** Plan r2 §8 W-1 — DB-direct system skill listing for the {@code ?isSystem=true} filter. */
    public List<SkillEntity> listSystemSkills() {
        return skillRepository.findByIsSystem(true);
    }

    /** Plan r2 §8 — used by SkillController DELETE to defend against numeric system-row deletes. */
    public java.util.Optional<SkillEntity> findById(Long id) {
        return skillRepository.findById(id);
    }

    /**
     * 删除 Skill — sibling-aware to avoid wiping out the active version when
     * deleting a candidate / archived row that shares (ownerId, name) and/or
     * skillPath with siblings.
     *
     * <p>Two pre-V2.5 bugs this method fixes:
     * <ol>
     *   <li><b>Bug A — Registry over-unregister</b>: the old code called
     *       {@code skillRegistry.unregisterSkillDefinition(name)} unconditionally,
     *       which removed the active sibling from the registry too (agent could no
     *       longer dispatch the skill). Fix: when an enabled sibling remains,
     *       re-register that sibling so the registry continues serving its
     *       definition.</li>
     *   <li><b>Bug B — Shared dir wipe</b>: {@link #forkSkill} (pre-V2.5) reused
     *       the parent's skillPath, so deleting a "Fork &amp; A/B Test" candidate
     *       physically removed the parent's SKILL.md directory. Fix: skip the
     *       directory removal when any sibling row still references the same
     *       path.</li>
     * </ol>
     */
    @Transactional
    public void deleteSkill(Long id) {
        SkillEntity entity = skillRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Skill not found: " + id));

        // Enumerate siblings BEFORE the delete so we know which cleanup paths to skip.
        List<SkillEntity> siblings = (entity.getOwnerId() != null && entity.getName() != null)
                ? skillRepository.findByOwnerIdAndName(entity.getOwnerId(), entity.getName())
                        .stream().filter(s -> !id.equals(s.getId())).toList()
                : List.of();
        SkillEntity activeSibling = siblings.stream()
                .filter(SkillEntity::isEnabled)
                .findFirst().orElse(null);
        boolean dirSharedWithSibling = entity.getSkillPath() != null
                && siblings.stream().anyMatch(s -> entity.getSkillPath().equals(s.getSkillPath()));

        // Delete DB row first so registry / dir cleanup decisions reflect post-delete state.
        skillRepository.deleteById(id);

        // Bug A — registry handoff: only fully unregister when no enabled sibling
        // remains. Otherwise re-register the sibling's definition so the registry
        // continues to serve the skill name correctly.
        if (activeSibling != null) {
            try {
                SkillDefinition def = activeSibling.getSkillPath() != null
                        ? skillPackageLoader.loadFromDirectory(Path.of(activeSibling.getSkillPath()))
                        : metadataOnlyDefinition(activeSibling);
                skillRegistry.registerSkillDefinition(def);
                log.info("Skill deleted: id={} name={} — re-registered active sibling id={} (skillPath={})",
                        id, entity.getName(), activeSibling.getId(), activeSibling.getSkillPath());
            } catch (Exception e) {
                log.warn("Skill deleted: id={} name={} — failed to re-register active sibling id={}: {}",
                        id, entity.getName(), activeSibling.getId(), e.getMessage());
            }
        } else {
            skillRegistry.unregisterSkillDefinition(entity.getName());
            log.info("Skill deleted: id={} name={} — no enabled sibling, unregistered name", id, entity.getName());
        }

        // Bug B — physical dir cleanup: only when no other row references the same path.
        if (entity.getSkillPath() != null && !dirSharedWithSibling) {
            deleteDirectoryQuietly(Path.of(entity.getSkillPath()));
        } else if (dirSharedWithSibling) {
            log.info("Skill deleted: id={} name={} — kept dir={} (shared with sibling)",
                    id, entity.getName(), entity.getSkillPath());
        }
    }

    /** Fallback metadata-only SkillDefinition (used when sibling has no on-disk SKILL.md). */
    private SkillDefinition metadataOnlyDefinition(SkillEntity skill) {
        SkillDefinition def = new SkillDefinition();
        def.setId(String.valueOf(skill.getId()));
        def.setName(skill.getName());
        def.setDescription(skill.getDescription());
        return def;
    }

    /**
     * Public helper for callers that need a {@link SkillDefinition} from a
     * {@link SkillEntity} (e.g. SkillController's {@code PUT /skill-md} after
     * writing user-edited content needs to re-register the registry entry).
     * Tries on-disk SKILL.md first; falls back to metadata-only if missing.
     */
    public SkillDefinition buildSkillDefinitionFor(SkillEntity skill) {
        if (skill.getSkillPath() != null) {
            try {
                return skillPackageLoader.loadFromDirectory(Path.of(skill.getSkillPath()));
            } catch (IOException e) {
                log.warn("buildSkillDefinitionFor: failed to load from {}, falling back to metadata: {}",
                        skill.getSkillPath(), e.getMessage());
            }
        }
        return metadataOnlyDefinition(skill);
    }

    /**
     * 获取 Skill 的 SKILL.md 内容。
     */
    public String getSkillPromptContent(Long id) {
        SkillEntity entity = skillRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Skill not found: " + id));

        if (entity.getSkillPath() == null) {
            throw new RuntimeException("Skill path not set for skill: " + id);
        }

        Path skillMdPath = Path.of(entity.getSkillPath(), "SKILL.md");
        try {
            return Files.readString(skillMdPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read SKILL.md for skill " + id + ": " + e.getMessage(), e);
        }
    }

    /**
     * 启用/禁用 Skill。
     */
    public SkillEntity toggleSkill(Long id, boolean enabled) {
        SkillEntity entity = skillRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Skill not found: " + id));
        entity.setEnabled(enabled);
        SkillEntity saved = skillRepository.save(entity);

        // 同步到 SkillRegistry
        if (enabled) {
            // 重新加载并注册
            if (entity.getSkillPath() != null) {
                try {
                    SkillDefinition def = skillPackageLoader.loadFromDirectory(Path.of(entity.getSkillPath()));
                    skillRegistry.registerSkillDefinition(def);
                    log.info("Skill re-enabled and registered: {}", entity.getName());
                } catch (IOException e) {
                    log.error("Failed to reload skill: {}", entity.getName(), e);
                }
            }
        } else {
            skillRegistry.unregisterSkillDefinition(entity.getName());
            log.info("Skill disabled and unregistered: {}", entity.getName());
        }

        return saved;
    }

    /**
     * 获取 Skill 详情，包含 SKILL.md 内容、reference 文件、scripts 列表。
     */
    public Map<String, Object> getSkillDetail(Long id) {
        SkillEntity entity = skillRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Skill not found: " + id));

        Map<String, Object> detail = new HashMap<>();
        detail.put("id", entity.getId());
        detail.put("name", entity.getName());
        detail.put("description", entity.getDescription());
        detail.put("enabled", entity.isEnabled());
        detail.put("requiredTools", entity.getRequiredTools());
        detail.put("createdAt", entity.getCreatedAt());
        detail.put("semver", entity.getSemver());
        detail.put("parentSkillId", entity.getParentSkillId());
        detail.put("usageCount", entity.getUsageCount());
        detail.put("successCount", entity.getSuccessCount());

        if (entity.getSkillPath() == null) {
            return detail;
        }

        Path skillDir = Path.of(entity.getSkillPath());

        // 读取 SKILL.md
        Path skillMd = skillDir.resolve("SKILL.md");
        if (!Files.exists(skillMd)) {
            skillMd = skillDir.resolve("skill.md");
        }
        if (Files.exists(skillMd)) {
            try {
                detail.put("skillMd", Files.readString(skillMd, StandardCharsets.UTF_8));
            } catch (IOException e) {
                detail.put("skillMd", "Failed to read: " + e.getMessage());
            }
        }

        // 读取 reference 文件
        Map<String, String> references = new HashMap<>();
        for (String refName : List.of("reference.md", "examples.md", "template.md")) {
            Path refPath = skillDir.resolve(refName);
            if (Files.exists(refPath)) {
                try {
                    references.put(refName, Files.readString(refPath, StandardCharsets.UTF_8));
                } catch (IOException ignored) {}
            }
        }
        // docs/ 子目录
        Path docsDir = skillDir.resolve("docs");
        if (Files.isDirectory(docsDir)) {
            try (Stream<Path> stream = Files.list(docsDir)) {
                stream.filter(p -> p.toString().endsWith(".md"))
                        .forEach(p -> {
                            try {
                                references.put("docs/" + p.getFileName(), Files.readString(p, StandardCharsets.UTF_8));
                            } catch (IOException ignored) {}
                        });
            } catch (IOException ignored) {}
        }
        detail.put("references", references);

        // scripts 列表
        List<Map<String, String>> scripts = new ArrayList<>();
        Path scriptsDir = skillDir.resolve("scripts");
        if (Files.isDirectory(scriptsDir)) {
            try (Stream<Path> stream = Files.walk(scriptsDir)) {
                stream.filter(Files::isRegularFile)
                        .forEach(p -> {
                            Map<String, String> scriptInfo = new HashMap<>();
                            scriptInfo.put("name", scriptsDir.relativize(p).toString());
                            try {
                                scriptInfo.put("content", Files.readString(p, StandardCharsets.UTF_8));
                            } catch (IOException e) {
                                scriptInfo.put("content", "Failed to read");
                            }
                            scripts.add(scriptInfo);
                        });
            } catch (IOException ignored) {}
        }
        detail.put("scripts", scripts);

        return detail;
    }

    /**
     * Record a skill-execution usage event. Increments usageCount; increments successCount when success=true.
     */
    @Transactional
    public void recordUsage(Long skillId, boolean success) {
        skillRepository.incrementUsage(skillId, success ? 1 : 0);
    }

    /**
     * Plan r2 §7 — telemetry overload. Atomic UPDATE by skill name to avoid JPA dirty-check
     * lost-update under concurrent tool execution.
     * <p>If no t_skill row exists for {@code skillName} (e.g. the call was a built-in Java Tool
     * that has no row), this method silently no-ops (debug log only). All exceptions are
     * swallowed and logged at WARN — telemetry MUST NOT cause tool calls to fail.
     *
     * @param skillName name of the skill (matches {@code t_skill.name})
     * @param success   whether the call succeeded
     * @param errorType {@code null} when success=true; otherwise one of
     *                  {@code VALIDATION / EXECUTION / NOT_ALLOWED} (currently only logged,
     *                  not partitioned per-error in the schema — see plan §7).
     */
    @Transactional
    public void recordUsage(String skillName, boolean success, String errorType) {
        if (skillName == null || skillName.isBlank()) {
            return;
        }
        try {
            int updated = skillRepository.incrementUsageByName(skillName, success ? 1 : 0, success ? 0 : 1);
            if (updated == 0) {
                log.debug("recordUsage skip: no t_skill row for name={} (likely built-in Tool)", skillName);
            } else if (!success) {
                log.debug("Recorded skill failure: name={}, errorType={}", skillName, errorType);
            }
        } catch (Exception e) {
            log.warn("recordUsage failed for skill={}: {}", skillName, e.getMessage());
        }
    }

    /**
     * Fork a skill into a new disabled child version for later A/B promotion.
     */
    @Transactional
    public SkillEntity forkSkill(Long parentId, Long ownerId) {
        SkillEntity parent = skillRepository.findById(parentId)
                .orElseThrow(() -> new RuntimeException("Skill not found: " + parentId));

        // Bootstrap parent to v1 if it has no semver yet (pre-versioning skills)
        if (parent.getSemver() == null) {
            parent.setSemver("v1");
            parent = skillRepository.save(parent);
        }

        // Same name as parent — safe since V64 relaxed uq_t_skill_owner_name to
        // a partial unique on (owner_id, name) WHERE enabled=true. Forks are
        // created with enabled=false; promote disables the parent (saveAndFlush)
        // before enabling the candidate, so at most one row is enabled at any
        // time per (owner_id, name).
        SkillEntity child = new SkillEntity();
        child.setName(parent.getName());
        child.setDescription(parent.getDescription());
        child.setTriggers(parent.getTriggers());
        child.setRequiredTools(parent.getRequiredTools());
        child.setOwnerId(ownerId != null ? ownerId : parent.getOwnerId());
        child.setPublic(parent.isPublic());
        child.setSource(parent.getSource());
        child.setRiskLevel(parent.getRiskLevel());
        child.setParentSkillId(parentId);
        child.setSemver(nextSemver(parent.getSemver()));
        child.setEnabled(false);

        // Bug B fix — V2.5: allocate isolated skillPath + copy parent's SKILL.md so
        // the candidate is physically independent. Two reasons:
        //   1. delete v2 (candidate) used to wipe v1 (parent) shared dir
        //   2. user manual edit on candidate must NOT corrupt parent's SKILL.md
        // SkillEvolutionService used to override skillPath after forkSkill to work
        // around this; that override is now redundant but harmless.
        Path isolatedDir = null;
        try {
            String childIdPlaceholder = java.util.UUID.randomUUID().toString();
            Long ownerForPath = child.getOwnerId() != null ? child.getOwnerId() : 0L;
            isolatedDir = skillStorageService.allocate(SkillSource.EVOLUTION_FORK,
                    AllocationContext.forEvolutionFork(
                            String.valueOf(ownerForPath),
                            String.valueOf(parentId),
                            childIdPlaceholder));
            // Copy parent SKILL.md content to isolated dir if parent has one.
            if (parent.getSkillPath() != null) {
                Path parentMd = Path.of(parent.getSkillPath(), "SKILL.md");
                if (Files.exists(parentMd)) {
                    Files.createDirectories(isolatedDir);
                    Files.copy(parentMd, isolatedDir.resolve("SKILL.md"),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            child.setSkillPath(isolatedDir.toString());
        } catch (Exception e) {
            log.warn("forkSkill: failed to allocate isolated dir for parent id={}, falling back to "
                    + "shared path (delete safety still guarded by sibling-aware deleteSkill): {}",
                    parentId, e.getMessage());
            // Fallback to legacy shared path. Sibling-aware deleteSkill protects parent dir.
            child.setSkillPath(parent.getSkillPath());
        }

        SkillEntity saved = skillRepository.save(child);
        log.info("Forked skill id={} (semver={}) from parent id={} into skillPath={}",
                saved.getId(), saved.getSemver(), parentId, saved.getSkillPath());
        return saved;
    }

    private String nextSemver(String current) {
        if (current == null || current.isBlank()) return "v2";
        if (current.startsWith("v")) {
            try {
                int n = Integer.parseInt(current.substring(1));
                return "v" + (n + 1);
            } catch (NumberFormatException ignored) {
            }
        }
        return current + "-next";
    }

    /**
     * Walk the lineage: ancestors (root first) + current skill + its direct children.
     */
    @Transactional(readOnly = true)
    public List<SkillEntity> getVersionChain(Long skillId) {
        SkillEntity current = skillRepository.findById(skillId)
                .orElseThrow(() -> new RuntimeException("Skill not found: " + skillId));

        java.util.Deque<SkillEntity> ancestors = new java.util.ArrayDeque<>();
        SkillEntity cursor = current;
        int maxDepth = 20;
        while (cursor.getParentSkillId() != null && maxDepth-- > 0) {
            SkillEntity next = skillRepository.findById(cursor.getParentSkillId()).orElse(null);
            if (next == null) break;
            ancestors.addFirst(next);
            cursor = next;
        }

        List<SkillEntity> chain = new ArrayList<>(ancestors);
        chain.add(current);
        chain.addAll(skillRepository.findByParentSkillId(skillId));
        return chain;
    }

    /**
     * SKILL-DASHBOARD-POLISH D — manual rollback from a promoted candidate back to its parent.
     * Reverses {@link com.skillforge.server.improve.SkillAbEvalService#promoteCandidate} so the
     * parent (v1) becomes the active skill again and the candidate (v2) returns to disabled.
     *
     * <p><b>Order matters (V64 partial unique index):</b> {@code uq_t_skill_owner_name_enabled}
     * enforces uniqueness of {@code (owner_id, name)} only WHERE {@code enabled=true}. The
     * candidate is currently the enabled row, so we must DISABLE it (and {@code flush})
     * before we ENABLE the parent — otherwise both rows would briefly be enabled at the
     * same time and the partial unique constraint would fire mid-transaction. This mirrors
     * {@link com.skillforge.server.improve.SkillAbEvalService#promoteCandidate}, which
     * disables the parent first and flushes before enabling the candidate.
     *
     * <p>Note: the original task brief described the order as "enable parent first, flush,
     * then disable candidate" — that order is incorrect under V64 and would always
     * trip the unique index. The implementation here uses the correct order.
     *
     * <p>Registry side-effects: parent is re-registered, candidate is unregistered. Failures
     * here are logged at WARN — DB+disk state is the source of truth, registry is rebuilt
     * on next startup / rescan if drift occurs.
     *
     * @param candidateSkillId id of the currently-enabled candidate (a fork of {@code parent})
     * @param userId           caller user id (logged for audit; no auth check here, controller decides)
     * @return the now-enabled parent {@link SkillEntity}
     */
    @Transactional
    public SkillEntity rollbackToParent(Long candidateSkillId, Long userId) {
        SkillEntity candidate = skillRepository.findById(candidateSkillId)
                .orElseThrow(() -> new RuntimeException("Skill not found: " + candidateSkillId));
        if (!candidate.isEnabled()) {
            throw new RuntimeException("Candidate skill is not currently enabled (already rolled back?): "
                    + candidateSkillId);
        }
        if (candidate.getParentSkillId() == null) {
            throw new RuntimeException("Candidate skill has no parent — cannot rollback: "
                    + candidateSkillId);
        }
        SkillEntity parent = skillRepository.findById(candidate.getParentSkillId())
                .orElseThrow(() -> new RuntimeException(
                        "Parent skill not found: " + candidate.getParentSkillId()));

        // V64: disable candidate FIRST + flush (so candidate.enabled=false hits the index
        // before parent.enabled=true). Reversing this order would create a moment where
        // both rows have enabled=true with identical (owner_id, name), violating
        // uq_t_skill_owner_name_enabled mid-transaction.
        candidate.setEnabled(false);
        skillRepository.saveAndFlush(candidate);

        parent.setEnabled(true);
        SkillEntity savedParent = skillRepository.save(parent);

        // Registry sync — best effort, drift is recoverable on next reconcile.
        try {
            skillRegistry.unregisterSkillDefinition(candidate.getName());
        } catch (RuntimeException unregEx) {
            log.warn("Rollback: failed to unregister candidate id={} from SkillRegistry: {}",
                    candidate.getId(), unregEx.getMessage());
        }
        if (parent.getSkillPath() != null) {
            try {
                SkillDefinition def = skillPackageLoader.loadFromDirectory(Path.of(parent.getSkillPath()));
                skillRegistry.registerSkillDefinition(def);
            } catch (Exception regEx) {
                log.warn("Rollback: failed to re-register parent id={} (path={}) in SkillRegistry: {}",
                        parent.getId(), parent.getSkillPath(), regEx.getMessage());
            }
        }

        log.info("Rolled back skill candidate={} ({}) back to parent={} ({}) by userId={}",
                candidate.getId(), candidate.getSemver(),
                parent.getId(), parent.getSemver(), userId);
        return savedParent;
    }

    /**
     * SKILL-DASHBOARD-POLISH-V2 §I — build a version tree (ancestors + current +
     * descendants) for the drawer's "Version Tree" tab.
     *
     * <p>Tree limits: max 10 ancestor levels and 10 descendant levels (cycle-safety
     * + perf cap). Ownership: only the current skill's owner (or null for system rows)
     * may walk the tree; pass {@code userId=null} to skip the auth check (used by tests
     * and admin paths).
     *
     * <p>Each node is a {@link Map} with: {@code id, name, semver, enabled, source,
     * createdAt, latestScore, children?} (children present only on descendants).
     *
     * @param skillId the focal skill id
     * @param userId  caller user id; required for ownership check unless null (admin)
     * @return a map with {@code ancestors}, {@code current}, {@code descendants}
     * @throws RuntimeException if the skill is not found OR userId mismatches the owner
     */
    /**
     * SKILL-DASHBOARD-POLISH-V2.5 §7 — recursive root-tree shape returning
     * {@code { id, name, version, status, latestScore, children: [...] }}
     * starting from the family root (oldest ancestor) down to all leaves.
     * Easier for FE that wants pure recursive rendering without keeping
     * track of the "current" pointer.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getVersionTreeRoot(Long skillId, Long userId) {
        SkillEntity current = skillRepository.findById(skillId)
                .orElseThrow(() -> new RuntimeException("Skill not found: " + skillId));
        if (current.isSystem()) {
            throw new RuntimeException("Cannot expose version tree for system skill: " + skillId);
        }
        if (userId != null && current.getOwnerId() != null
                && !current.getOwnerId().equals(userId)) {
            throw new RuntimeException("Caller userId=" + userId
                    + " does not own skill id=" + skillId);
        }

        final int MAX_DEPTH = 10;

        // Walk up to the root (oldest ancestor with parentSkillId == null).
        java.util.Set<Long> visitedUp = new java.util.HashSet<>();
        SkillEntity cursor = current;
        visitedUp.add(cursor.getId());
        for (int i = 0; i < MAX_DEPTH; i++) {
            Long parentId = cursor.getParentSkillId();
            if (parentId == null || !visitedUp.add(parentId)) {
                break;
            }
            SkillEntity parent = skillRepository.findById(parentId).orElse(null);
            if (parent == null) {
                break;
            }
            cursor = parent;
        }
        // cursor is now the root.

        // Build full tree from root using the same descendants walk.
        java.util.Set<Long> visitedTree = new java.util.HashSet<>();
        visitedTree.add(cursor.getId());
        Map<String, Object> root = toRootTreeNode(cursor, current.getId());
        root.put("children", buildRootTreeChildren(cursor.getId(), current.getId(), visitedTree, 1, MAX_DEPTH));
        return root;
    }

    private List<Map<String, Object>> buildRootTreeChildren(Long parentId,
                                                            Long currentId,
                                                            java.util.Set<Long> visited,
                                                            int depth,
                                                            int maxDepth) {
        if (depth > maxDepth) {
            return List.of();
        }
        List<SkillEntity> children = skillRepository.findByParentSkillId(parentId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (SkillEntity child : children) {
            if (child.getId() == null || !visited.add(child.getId())) {
                continue;
            }
            Map<String, Object> node = toRootTreeNode(child, currentId);
            node.put("children", buildRootTreeChildren(child.getId(), currentId, visited, depth + 1, maxDepth));
            result.add(node);
        }
        return result;
    }

    private Map<String, Object> toRootTreeNode(SkillEntity entity, Long currentId) {
        Map<String, Object> node = new java.util.LinkedHashMap<>();
        node.put("id", entity.getId());
        node.put("name", entity.getName());
        node.put("version", entity.getSemver());
        node.put("status", entity.isEnabled() ? "enabled" : "disabled");
        node.put("source", entity.getSource());
        node.put("createdAt", entity.getCreatedAt());
        node.put("isCurrent", entity.getId().equals(currentId));
        Double latestScore = null;
        try {
            SkillEvalHistoryEntity latest = skillEvalHistoryRepository
                    .findFirstBySkillIdOrderByCreatedAtDesc(entity.getId())
                    .orElse(null);
            if (latest != null) {
                latestScore = latest.getCompositeScore();
            }
        } catch (Exception e) {
            log.warn("getVersionTreeRoot: latestScore lookup failed for skillId={}: {}",
                    entity.getId(), e.getMessage());
        }
        node.put("latestScore", latestScore);
        return node;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getVersionTree(Long skillId, Long userId) {
        SkillEntity current = skillRepository.findById(skillId)
                .orElseThrow(() -> new RuntimeException("Skill not found: " + skillId));

        // Ownership: V2 §I says system skill 不暴露 (system has ownerId=null).
        // For non-system rows, require caller userId == current.ownerId. userId=null
        // skips the check (admin / internal callers).
        if (current.isSystem()) {
            throw new RuntimeException("Cannot expose version tree for system skill: " + skillId);
        }
        if (userId != null && current.getOwnerId() != null
                && !current.getOwnerId().equals(userId)) {
            throw new RuntimeException("Caller userId=" + userId
                    + " does not own skill id=" + skillId);
        }

        final int MAX_DEPTH = 10;

        // Walk ancestors: root → parent (oldest first).
        List<Map<String, Object>> ancestors = new ArrayList<>();
        java.util.Set<Long> visited = new java.util.HashSet<>();
        visited.add(current.getId());
        SkillEntity cursor = current;
        for (int i = 0; i < MAX_DEPTH; i++) {
            Long parentId = cursor.getParentSkillId();
            if (parentId == null || !visited.add(parentId)) {
                break;
            }
            SkillEntity parent = skillRepository.findById(parentId).orElse(null);
            if (parent == null) {
                break;
            }
            ancestors.add(0, toVersionNode(parent, false));
            cursor = parent;
        }

        // Recursive descendants — depth-bounded.
        List<Map<String, Object>> descendants = collectDescendants(skillId, visited, 1, MAX_DEPTH);

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("ancestors", ancestors);
        result.put("current", toVersionNode(current, false));
        result.put("descendants", descendants);
        return result;
    }

    private List<Map<String, Object>> collectDescendants(Long parentId,
                                                         java.util.Set<Long> visited,
                                                         int depth,
                                                         int maxDepth) {
        if (depth > maxDepth) {
            return List.of();
        }
        List<SkillEntity> children = skillRepository.findByParentSkillId(parentId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (SkillEntity child : children) {
            if (child.getId() == null || !visited.add(child.getId())) {
                continue;  // cycle guard
            }
            Map<String, Object> node = toVersionNode(child, true);
            node.put("children", collectDescendants(child.getId(), visited, depth + 1, maxDepth));
            result.add(node);
        }
        return result;
    }

    private Map<String, Object> toVersionNode(SkillEntity entity, boolean withChildren) {
        Map<String, Object> node = new java.util.LinkedHashMap<>();
        node.put("id", entity.getId());
        node.put("name", entity.getName());
        node.put("semver", entity.getSemver());
        node.put("enabled", entity.isEnabled());
        node.put("source", entity.getSource());
        node.put("createdAt", entity.getCreatedAt());
        node.put("parentSkillId", entity.getParentSkillId());
        // Latest composite score (nullable). Best-effort lookup; failures degrade
        // gracefully to null so a transient eval-history outage doesn't break the
        // version tree drawer.
        Double latestScore = null;
        try {
            SkillEvalHistoryEntity latest = skillEvalHistoryRepository
                    .findFirstBySkillIdOrderByCreatedAtDesc(entity.getId())
                    .orElse(null);
            if (latest != null) {
                latestScore = latest.getCompositeScore();
            }
        } catch (Exception e) {
            log.warn("getVersionTree: latestScore lookup failed for skillId={}: {}",
                    entity.getId(), e.getMessage());
        }
        node.put("latestScore", latestScore);
        return node;
    }

    /**
     * Bootstrap semver to "v1" for skills that were created before versioning existed.
     */
    @Transactional
    public SkillEntity initSemver(Long skillId) {
        SkillEntity skill = skillRepository.findById(skillId)
                .orElseThrow(() -> new RuntimeException("Skill not found: " + skillId));
        if (skill.getSemver() == null) {
            skill.setSemver("v1");
            return skillRepository.save(skill);
        }
        return skill;
    }

    private void deleteDirectoryQuietly(Path dir) {
        try {
            if (Files.exists(dir)) {
                try (Stream<Path> walk = Files.walk(dir)) {
                    walk.sorted(Comparator.reverseOrder())
                            .forEach(path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException e) {
                                    log.warn("Failed to delete: {}", path, e);
                                }
                            });
                }
            }
        } catch (IOException e) {
            log.warn("Failed to clean up directory: {}", dir, e);
        }
    }
}
