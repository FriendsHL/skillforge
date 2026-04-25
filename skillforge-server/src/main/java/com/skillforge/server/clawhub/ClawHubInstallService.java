package com.skillforge.server.clawhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.engine.PendingAskRegistry;
import com.skillforge.core.model.SkillDefinition;
import com.skillforge.core.skill.SkillPackageLoader;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.clawhub.ClawHubModels.SafetyReport;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.repository.SkillRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 编排 ClawHub 安装的完整流程:
 *  1. 下载 zip
 *  2. 调官方 scan(best-effort)
 *  3. 本地 SafetyChecker 检查
 *  4. 合并官方扫描结果
 *  5. 如果 BLOCKED -> 直接拒绝
 *  6. 否则发 ask_user 让用户最终确认(即使在 auto 模式)
 *  7. 用户同意 -> 调用 SkillPackageLoader 解压注册 + 持久化 SkillEntity
 *
 * 这个 service 在 ClawHubTool 的 execute() 里被调用,会同步阻塞,
 * 借用现有 PendingAskRegistry 实现 ask_user 暂停语义。
 */
@Service
public class ClawHubInstallService {

    private static final Logger log = LoggerFactory.getLogger(ClawHubInstallService.class);

    private final ClawHubProperties props;
    private final ClawHubClient client;
    private final ClawHubSafetyChecker safetyChecker;
    private final SkillPackageLoader packageLoader;
    private final SkillRegistry skillRegistry;
    private final SkillRepository skillRepository;
    private final ChatEventBroadcaster broadcaster;
    private final PendingAskRegistry pendingAskRegistry;
    private final ObjectMapper json;

    public ClawHubInstallService(ClawHubProperties props,
                                 ClawHubClient client,
                                 ClawHubSafetyChecker safetyChecker,
                                 SkillPackageLoader packageLoader,
                                 SkillRegistry skillRegistry,
                                 SkillRepository skillRepository,
                                 ChatEventBroadcaster broadcaster,
                                 PendingAskRegistry pendingAskRegistry,
                                 ObjectMapper json) {
        this.props = props;
        this.client = client;
        this.safetyChecker = safetyChecker;
        this.packageLoader = packageLoader;
        this.skillRegistry = skillRegistry;
        this.skillRepository = skillRepository;
        this.broadcaster = broadcaster;
        this.pendingAskRegistry = pendingAskRegistry;
        this.json = json;
    }

    /**
     * 完整安装流程。
     *
     * @param slug      ClawHub skill slug
     * @param version   版本(null 取 latest)
     * @param sessionId 当前会话 id(用于 ask_user 推送)
     * @param userId    安装发起人(t_skill.ownerId 用)
     */
    public InstallOutcome install(String slug, String version, String sessionId, Long userId) {
        if (!props.isEnabled()) {
            return InstallOutcome.failure("ClawHub integration is disabled by config");
        }
        if (slug == null || slug.isBlank()) {
            return InstallOutcome.failure("slug is required");
        }

        // 名字冲突预检(避免下载半天后发现装不进去)
        Optional<SkillEntity> existing = skillRepository.findByName(slug);
        if (existing.isPresent() && "clawhub".equals(existing.get().getSource())) {
            return InstallOutcome.failure("Skill " + slug + " is already installed from ClawHub. Uninstall first.");
        }
        if (existing.isPresent()) {
            return InstallOutcome.failure("A skill with name " + slug + " already exists from another source.");
        }
        if (skillRegistry.getTool(slug).isPresent()) {
            return InstallOutcome.failure("Skill name " + slug + " conflicts with a built-in tool");
        }

        // 1. 下载
        byte[] zipBytes;
        try {
            zipBytes = client.download(slug, version);
        } catch (Exception e) {
            return InstallOutcome.failure("Download failed: " + e.getMessage());
        }
        log.info("ClawHub install: downloaded slug={} version={} bytes={}", slug, version, zipBytes.length);

        // 2. 官方扫描
        ClawHubModels.ScanReport official = null;
        try {
            official = client.getScan(slug, version);
        } catch (Exception e) {
            log.warn("ClawHub install: official scan failed for {}: {}", slug, e.getMessage());
        }

        // 3. 本地安全检查
        SafetyReport safety = safetyChecker.check(zipBytes, slug);
        safetyChecker.mergeOfficialScan(safety, official);

        // 4. BLOCKED -> 直接拒绝,不进 ask_user
        if (safety.isBlocked()) {
            String reason = "Install BLOCKED:\n  - " + String.join("\n  - ", safety.blockReasons);
            log.warn("ClawHub install BLOCKED slug={} reasons={}", slug, safety.blockReasons);
            return InstallOutcome.failure(reason).withSafety(safety).withOfficialScan(official);
        }

        // 5. 强制 ask_user(即使 auto 模式)
        boolean approved;
        try {
            approved = askUserConfirmation(sessionId, slug, version, safety, official);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return InstallOutcome.failure("Install interrupted while waiting for user").withSafety(safety);
        }
        if (!approved) {
            return InstallOutcome.failure("User declined installation").withSafety(safety).withOfficialScan(official);
        }

        // 6. 真正解压 + 注册
        String resolvedVersion = (version == null || version.isBlank()) ? "latest" : version;
        Path targetDir = Path.of(props.getInstallDir(), sanitizeSlug(slug), resolvedVersion);
        try {
            SkillDefinition def = packageLoader.loadFromZip(new ByteArrayInputStream(zipBytes), targetDir);
            // 用 slug 作为 skill name 的兜底,避免 frontmatter 没写 name 时拿到目录 hash
            if (def.getName() == null || def.getName().isBlank()) {
                def.setName(slug);
            }

            // 7. 持久化 SkillEntity
            SkillEntity entity = new SkillEntity();
            entity.setName(def.getName());
            entity.setDescription(def.getDescription());
            entity.setSkillPath(targetDir.toAbsolutePath().toString());
            entity.setOwnerId(userId);
            entity.setPublic(true);
            entity.setEnabled(true);
            entity.setSource("clawhub");
            entity.setVersion(resolvedVersion);
            entity.setRiskLevel(safety.risk.name().toLowerCase());
            entity.setScanReport(toJson(safety, official));
            if (def.getRequiredTools() != null && !def.getRequiredTools().isEmpty()) {
                entity.setRequiredTools(String.join(",", def.getRequiredTools()));
            }
            SkillEntity saved = skillRepository.save(entity);

            // 8. 注册到内存 SkillRegistry
            skillRegistry.registerSkillDefinition(def);

            log.info("ClawHub install OK slug={} version={} riskLevel={} entityId={}",
                    slug, resolvedVersion, safety.risk, saved.getId());
            return InstallOutcome.success(saved, safety, official);
        } catch (Exception e) {
            log.error("ClawHub install: package load failed slug={}", slug, e);
            return InstallOutcome.failure("Package load failed: " + e.getMessage()).withSafety(safety);
        }
    }

    /**
     * 借用现有 ask_user 推送通道:生成 askId → 注册 latch → 广播 → 阻塞等待回答。
     * 返回 true 表示用户选择了 Install,false 表示拒绝或超时。
     */
    private boolean askUserConfirmation(String sessionId, String slug, String version,
                                        SafetyReport safety, ClawHubModels.ScanReport official) throws InterruptedException {
        if (sessionId == null) {
            // 没有 session 上下文(理论上 Agent loop 调用时一定有),保守拒绝
            log.warn("ClawHub install: no sessionId, refusing");
            return false;
        }

        String askId = UUID.randomUUID().toString();
        pendingAskRegistry.register(askId);

        ChatEventBroadcaster.AskUserEvent event = new ChatEventBroadcaster.AskUserEvent();
        event.askId = askId;
        event.question = String.format("是否安装 ClawHub 上的 skill「%s」(版本: %s)?",
                slug, version == null || version.isBlank() ? "latest" : version);
        event.context = buildAskContext(slug, safety, official);
        event.options = new ArrayList<>();
        event.options.add(new ChatEventBroadcaster.AskUserEvent.Option(
                "Install", "确认安装该 skill 到本地"));
        event.options.add(new ChatEventBroadcaster.AskUserEvent.Option(
                "Cancel", "拒绝本次安装"));
        event.allowOther = false;

        broadcaster.askUser(sessionId, event);
        broadcaster.sessionStatus(sessionId, "waiting_user", "Awaiting ClawHub install confirmation", null);

        log.info("ClawHub install: waiting for user confirmation askId={} slug={}", askId, slug);
        String answer = pendingAskRegistry.await(askId, props.getAskUserTimeoutSeconds());
        log.info("ClawHub install: ask_user answered askId={} answer={}", askId, answer);

        // 状态恢复给前端,避免一直停在 waiting_user
        broadcaster.sessionStatus(sessionId, "running", "ClawHub install resuming", null);

        if (answer == null) return false;
        return answer.equalsIgnoreCase("Install") || answer.toLowerCase().startsWith("install");
    }

    private String buildAskContext(String slug, SafetyReport safety, ClawHubModels.ScanReport official) {
        StringBuilder sb = new StringBuilder();
        sb.append("【安全检测结果】\n");
        sb.append("风险等级: ").append(safety.risk).append("\n");
        sb.append("文件数: ").append(safety.entryCount).append(" / 解压后字节: ").append(safety.uncompressedBytes).append("\n");
        if (official != null) {
            sb.append("ClawHub 官方扫描: suspicious=").append(official.suspicious)
                    .append(" malicious=").append(official.malicious);
            if (official.summary != null) sb.append(" — ").append(official.summary);
            sb.append("\n");
        }
        if (!safety.warnings.isEmpty()) {
            sb.append("\n警告:\n");
            for (String w : safety.warnings) sb.append("  - ").append(w).append("\n");
        }
        if (!safety.hits.isEmpty()) {
            sb.append("\n关键词命中:\n");
            for (String h : safety.hits) sb.append("  - ").append(h).append("\n");
        }
        if (safety.skillMdSnippet != null) {
            sb.append("\nSKILL.md 片段:\n").append(safety.skillMdSnippet);
        }
        return sb.toString();
    }

    private String toJson(SafetyReport safety, ClawHubModels.ScanReport official) {
        try {
            Map<String, Object> root = new HashMap<>();
            root.put("risk", safety.risk.name());
            root.put("entryCount", safety.entryCount);
            root.put("uncompressedBytes", safety.uncompressedBytes);
            root.put("hits", safety.hits);
            root.put("warnings", safety.warnings);
            root.put("blockReasons", safety.blockReasons);
            if (official != null) {
                Map<String, Object> off = new HashMap<>();
                off.put("suspicious", official.suspicious);
                off.put("malicious", official.malicious);
                off.put("score", official.score);
                off.put("summary", official.summary);
                root.put("officialScan", off);
            }
            return json.writeValueAsString(root);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * 列出本地已通过 clawhub 安装的 skill。
     */
    public List<SkillEntity> listInstalled() {
        return skillRepository.findBySource("clawhub");
    }

    private String sanitizeSlug(String slug) {
        return slug.replaceAll("[^a-zA-Z0-9_./@-]", "_");
    }

    // ============ outcome ============

    public static class InstallOutcome {
        public boolean success;
        public String message;
        public SkillEntity entity;
        public SafetyReport safety;
        public ClawHubModels.ScanReport officialScan;

        public static InstallOutcome success(SkillEntity entity, SafetyReport safety, ClawHubModels.ScanReport scan) {
            InstallOutcome o = new InstallOutcome();
            o.success = true;
            o.message = "Installed " + entity.getName() + " (risk=" + safety.risk + ")";
            o.entity = entity;
            o.safety = safety;
            o.officialScan = scan;
            return o;
        }

        public static InstallOutcome failure(String reason) {
            InstallOutcome o = new InstallOutcome();
            o.success = false;
            o.message = reason;
            return o;
        }

        public InstallOutcome withSafety(SafetyReport s) { this.safety = s; return this; }
        public InstallOutcome withOfficialScan(ClawHubModels.ScanReport s) { this.officialScan = s; return this; }
    }
}
