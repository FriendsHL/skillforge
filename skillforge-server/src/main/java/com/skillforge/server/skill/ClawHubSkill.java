package com.skillforge.server.skill;

import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.Skill;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.clawhub.ClawHubClient;
import com.skillforge.server.clawhub.ClawHubInstallService;
import com.skillforge.server.clawhub.ClawHubModels;
import com.skillforge.server.entity.SkillEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Built-in tool: 让 Agent 直接搜索 / 检视 / 安装 ClawHub 上的 skill 包。
 *
 * 5 个 action:
 *   - search        : 关键词搜索
 *   - get_detail    : 拿单个 skill 的详细信息
 *   - scan          : 看官方安全扫描结果
 *   - install       : 下载 → 本地安全检测 → 强制 ask_user 确认 → 解压注册
 *   - list_installed: 列出本地已通过 clawhub 装的 skill
 *
 * 注意:install 是带副作用的 + 高风险 action,无论 session 处于 ask 或 auto 模式,
 * ClawHubInstallService 内部都会强制走一次 ask_user。
 */
public class ClawHubSkill implements Skill {

    private static final Logger log = LoggerFactory.getLogger(ClawHubSkill.class);

    private final ClawHubClient client;
    private final ClawHubInstallService installService;

    public ClawHubSkill(ClawHubClient client, ClawHubInstallService installService) {
        this.client = client;
        this.installService = installService;
    }

    @Override
    public String getName() {
        return "ClawHub";
    }

    @Override
    public String getDescription() {
        return "Search, inspect, and install Claude skill packages from clawhub.ai.\n\n"
                + "To find popular/trending skills, use the 'explore' action with sort=downloads or sort=trending. "
                + "This returns skills sorted by popularity with download counts — much more efficient than "
                + "multiple keyword searches.\n\n"
                + "Use 'search' only when looking for skills matching a specific keyword.\n"
                + "Use 'get_detail' to see full info for a specific skill.\n"
                + "The 'install' action will ALWAYS require human confirmation for safety.";
    }

    @Override
    public boolean isReadOnly() {
        // 整体不是只读(install 会写文件 + 改 SkillRegistry),所以返回 false
        return false;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", Map.of(
                "type", "string",
                "description", "The action to perform: explore (browse by popularity), search, get_detail, scan, install, or list_installed",
                "enum", List.of("explore", "search", "get_detail", "scan", "install", "list_installed")
        ));
        properties.put("query", Map.of(
                "type", "string",
                "description", "Keyword query (required for search)"
        ));
        properties.put("limit", Map.of(
                "type", "integer",
                "description", "Max search results (default 10, max 50)"
        ));
        properties.put("slug", Map.of(
                "type", "string",
                "description", "Skill slug (required for get_detail / scan / install)"
        ));
        properties.put("version", Map.of(
                "type", "string",
                "description", "Optional version (default: latest)"
        ));
        properties.put("sort", Map.of(
                "type", "string",
                "description", "Sort order for explore action: downloads, trending, newest, or rating (default: downloads)",
                "enum", List.of("downloads", "trending", "newest", "rating")
        ));
        properties.put("includeSuspicious", Map.of(
                "type", "boolean",
                "description", "Include suspicious results in search (default false)"
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("action"));
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            String action = (String) input.get("action");
            if (action == null || action.isBlank()) {
                return SkillResult.error("action is required");
            }
            return switch (action) {
                case "explore" -> handleExplore(input);
                case "search" -> handleSearch(input);
                case "get_detail" -> handleGetDetail(input);
                case "scan" -> handleScan(input);
                case "install" -> handleInstall(input, context);
                case "list_installed" -> handleListInstalled();
                default -> SkillResult.error("Unknown action: " + action +
                        ". Supported: explore, search, get_detail, scan, install, list_installed");
            };
        } catch (Exception e) {
            log.error("ClawHubSkill execute failed", e);
            return SkillResult.error("ClawHub error: " + e.getMessage());
        }
    }

    private SkillResult handleExplore(Map<String, Object> input) throws Exception {
        String sort = (String) input.get("sort");
        if (sort == null || sort.isBlank()) sort = "downloads";
        int limit = toInt(input.get("limit"), 20);

        List<ClawHubModels.SkillSummary> results = client.explore(sort, limit);
        if (results.isEmpty()) {
            return SkillResult.success("No skills found (sort=" + sort + "). The explore API may require authentication.");
        }

        StringBuilder sb = new StringBuilder("Top ").append(results.size())
                .append(" skills (sorted by ").append(sort).append("):\n");
        int rank = 1;
        for (ClawHubModels.SkillSummary s : results) {
            sb.append(rank++).append(". ").append(s.slug);
            if (s.name != null && !s.name.equals(s.slug)) sb.append(" (").append(s.name).append(")");
            sb.append("  ↓").append(s.downloads).append("  ★").append(s.stars);
            if (s.latestVersion != null) sb.append("  @").append(s.latestVersion);
            sb.append("\n");
            if (s.description != null && !s.description.isBlank()) {
                sb.append("   ").append(truncate(s.description, 120)).append("\n");
            }
        }
        return SkillResult.success(sb.toString());
    }

    private SkillResult handleSearch(Map<String, Object> input) throws Exception {
        String query = (String) input.get("query");
        if (query == null || query.isBlank()) {
            return SkillResult.error("query is required for search");
        }
        int limit = toInt(input.get("limit"), 10);
        boolean includeSuspicious = Boolean.TRUE.equals(input.get("includeSuspicious"));

        List<ClawHubModels.SkillSummary> results = client.search(query, limit, !includeSuspicious);
        if (results.isEmpty()) {
            return SkillResult.success("No skills found for query: " + query);
        }

        StringBuilder sb = new StringBuilder("Found ").append(results.size()).append(" skill(s):\n");
        for (ClawHubModels.SkillSummary s : results) {
            sb.append("- ").append(s.slug);
            if (s.latestVersion != null) sb.append(" @").append(s.latestVersion);
            sb.append("  ↓").append(s.downloads).append("  ★").append(s.stars);
            if (s.suspicious) sb.append("  ⚠ suspicious");
            sb.append("\n");
            if (s.description != null && !s.description.isBlank()) {
                sb.append("    ").append(truncate(s.description, 160)).append("\n");
            }
        }
        return SkillResult.success(sb.toString());
    }

    private SkillResult handleGetDetail(Map<String, Object> input) throws Exception {
        String slug = (String) input.get("slug");
        if (slug == null || slug.isBlank()) {
            return SkillResult.error("slug is required for get_detail");
        }
        ClawHubModels.SkillDetail d = client.getDetail(slug);
        StringBuilder sb = new StringBuilder();
        sb.append("Slug: ").append(d.slug).append("\n");
        if (d.name != null) sb.append("Name: ").append(d.name).append("\n");
        if (d.author != null) sb.append("Author: ").append(d.author).append("\n");
        if (d.latestVersion != null) sb.append("Latest version: ").append(d.latestVersion).append("\n");
        sb.append("Downloads: ").append(d.downloads).append("  Stars: ").append(d.stars).append("\n");
        if (d.suspicious) sb.append("⚠ suspicious flag set\n");
        if (d.homepage != null) sb.append("Homepage: ").append(d.homepage).append("\n");
        if (d.description != null) sb.append("\nDescription:\n").append(d.description).append("\n");
        if (d.versions != null && !d.versions.isEmpty()) {
            sb.append("\nAvailable versions: ").append(String.join(", ", d.versions)).append("\n");
        }
        return SkillResult.success(sb.toString());
    }

    private SkillResult handleScan(Map<String, Object> input) throws Exception {
        String slug = (String) input.get("slug");
        if (slug == null || slug.isBlank()) {
            return SkillResult.error("slug is required for scan");
        }
        String version = (String) input.get("version");
        ClawHubModels.ScanReport r = client.getScan(slug, version);
        StringBuilder sb = new StringBuilder();
        sb.append("Slug: ").append(slug);
        if (version != null) sb.append(" @").append(version);
        sb.append("\nSuspicious: ").append(r.suspicious).append("\n");
        sb.append("Malicious: ").append(r.malicious).append("\n");
        if (r.score >= 0) sb.append("Score: ").append(r.score).append("\n");
        if (r.summary != null) sb.append("Summary: ").append(r.summary).append("\n");
        return SkillResult.success(sb.toString());
    }

    private SkillResult handleInstall(Map<String, Object> input, SkillContext context) {
        String slug = (String) input.get("slug");
        if (slug == null || slug.isBlank()) {
            return SkillResult.error("slug is required for install");
        }
        String version = (String) input.get("version");
        String sessionId = context == null ? null : context.getSessionId();
        Long userId = context == null ? null : context.getUserId();

        ClawHubInstallService.InstallOutcome outcome = installService.install(slug, version, sessionId, userId);
        if (outcome.success) {
            return SkillResult.success(outcome.message);
        }
        return SkillResult.error(outcome.message);
    }

    private SkillResult handleListInstalled() {
        List<SkillEntity> list = installService.listInstalled();
        if (list.isEmpty()) {
            return SkillResult.success("No ClawHub skills installed yet.");
        }
        StringBuilder sb = new StringBuilder("Installed ClawHub skills:\n");
        for (SkillEntity e : list) {
            sb.append("- ").append(e.getName());
            if (e.getVersion() != null) sb.append(" @").append(e.getVersion());
            sb.append(" (risk=").append(e.getRiskLevel()).append(", id=").append(e.getId()).append(")");
            if (!e.isEnabled()) sb.append(" [disabled]");
            sb.append("\n");
        }
        return SkillResult.success(sb.toString());
    }

    private static int toInt(Object v, int def) {
        if (v == null) return def;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return def; }
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }
}
