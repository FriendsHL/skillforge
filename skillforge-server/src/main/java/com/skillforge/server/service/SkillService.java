package com.skillforge.server.service;

import com.skillforge.core.model.SkillDefinition;
import com.skillforge.core.skill.SkillPackageLoader;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.entity.SkillEvalHistoryEntity;
import com.skillforge.server.repository.SkillEvalHistoryRepository;
import com.skillforge.server.repository.SkillRepository;
import com.skillforge.server.skill.AllocationContext;
import com.skillforge.server.skill.SkillSource;
import com.skillforge.server.skill.SkillStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

    public SkillService(SkillRepository skillRepository,
                        SkillRegistry skillRegistry,
                        SkillPackageLoader skillPackageLoader,
                        SkillStorageService skillStorageService,
                        SkillEvalHistoryRepository skillEvalHistoryRepository) {
        this.skillRepository = skillRepository;
        this.skillRegistry = skillRegistry;
        this.skillPackageLoader = skillPackageLoader;
        this.skillStorageService = skillStorageService;
        this.skillEvalHistoryRepository = skillEvalHistoryRepository;
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

            return saved;

        } catch (IOException e) {
            // 清理已创建的目录
            deleteDirectoryQuietly(targetDir);
            throw new RuntimeException("Failed to process skill package: " + e.getMessage(), e);
        }
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
     * 删除 Skill，包括文件和数据库记录。
     */
    public void deleteSkill(Long id) {
        SkillEntity entity = skillRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Skill not found: " + id));

        // 从 SkillRegistry 注销
        skillRegistry.unregisterSkillDefinition(entity.getName());

        // 删除文件目录
        if (entity.getSkillPath() != null) {
            deleteDirectoryQuietly(Path.of(entity.getSkillPath()));
        }

        // 删除数据库记录
        skillRepository.deleteById(id);
        log.info("Skill deleted: id={}, name={}", id, entity.getName());
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
        child.setSkillPath(parent.getSkillPath());
        child.setTriggers(parent.getTriggers());
        child.setRequiredTools(parent.getRequiredTools());
        child.setOwnerId(ownerId != null ? ownerId : parent.getOwnerId());
        child.setPublic(parent.isPublic());
        child.setSource(parent.getSource());
        child.setRiskLevel(parent.getRiskLevel());
        child.setParentSkillId(parentId);
        child.setSemver(nextSemver(parent.getSemver()));
        child.setEnabled(false);

        SkillEntity saved = skillRepository.save(child);
        log.info("Forked skill id={} (semver={}) from parent id={}", saved.getId(), saved.getSemver(), parentId);
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
