package com.skillforge.server.clawhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * ClawHub REST 客户端。
 *
 * 公共读端点(无需 token):
 *   GET /api/v1/search?q=...&highlightedOnly=&nonSuspiciousOnly=
 *   GET /api/v1/skills/{slug}
 *   GET /api/v1/skills/{slug}/scan
 *   GET /api/v1/download?slug=&version=&tag=
 *
 * 我们目前只用读端点 + download。Publish/delete 这些写操作不实现。
 */
@Component
public class ClawHubClient {

    private static final Logger log = LoggerFactory.getLogger(ClawHubClient.class);

    private final ClawHubProperties props;
    private final OkHttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public ClawHubClient(ClawHubProperties props) {
        this.props = props;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(props.getHttpTimeoutMs(), TimeUnit.MILLISECONDS)
                .readTimeout(props.getHttpTimeoutMs(), TimeUnit.MILLISECONDS)
                .writeTimeout(props.getHttpTimeoutMs(), TimeUnit.MILLISECONDS)
                .build();
    }

    /**
     * 搜索 skill。
     *
     * @param query             关键词
     * @param limit             返回条数(<=50)
     * @param nonSuspiciousOnly 只返回未被标记为可疑的(默认 true)
     */
    public List<ClawHubModels.SkillSummary> search(String query, int limit, boolean nonSuspiciousOnly) throws IOException {
        HttpUrl url = HttpUrl.parse(props.getBaseUrl() + "/api/v1/search").newBuilder()
                .addQueryParameter("q", query == null ? "" : query)
                .addQueryParameter("limit", String.valueOf(Math.max(1, Math.min(limit, 50))))
                .addQueryParameter("nonSuspiciousOnly", String.valueOf(nonSuspiciousOnly))
                .build();

        JsonNode root = getJson(url);
        // 实际响应: { "results": [{slug, displayName, summary, version, score, updatedAt}] }
        JsonNode arr = root.isArray() ? root : root.path("results");
        if (!arr.isArray()) arr = root.path("skills");
        List<ClawHubModels.SkillSummary> list = new ArrayList<>();
        if (arr.isArray()) {
            for (JsonNode n : arr) {
                list.add(parseSummary(n));
            }
        }
        return list;
    }

    /**
     * 浏览 skill 列表,支持按 downloads/trending/newest/rating 排序。
     * 对应 CLI: clawhub explore --sort downloads --limit N
     */
    public List<ClawHubModels.SkillSummary> explore(String sort, int limit) throws IOException {
        HttpUrl url = HttpUrl.parse(props.getBaseUrl() + "/api/v1/explore").newBuilder()
                .addQueryParameter("sort", sort != null ? sort : "downloads")
                .addQueryParameter("limit", String.valueOf(Math.max(1, Math.min(limit, 200))))
                .build();

        JsonNode root = getJson(url);
        // 响应: { "items": [{slug, displayName, summary, ...}], "nextCursor": ... }
        JsonNode arr = root.path("items");
        if (!arr.isArray()) arr = root.isArray() ? root : root.path("results");
        List<ClawHubModels.SkillSummary> list = new ArrayList<>();
        if (arr.isArray()) {
            for (JsonNode n : arr) {
                list.add(parseSummary(n));
            }
        }
        return list;
    }

    /**
     * 获取 skill 详情。
     * 实际响应嵌套结构:
     *   { skill: {slug, displayName, summary, tags:{latest}, stats:{downloads,stars,...}},
     *     latestVersion: {version, ...}, owner: {handle, displayName, ...} }
     */
    public ClawHubModels.SkillDetail getDetail(String slug) throws IOException {
        HttpUrl url = HttpUrl.parse(props.getBaseUrl() + "/api/v1/skills/" + encodeSlug(slug));
        JsonNode root = getJson(url);
        JsonNode skillNode = root.path("skill");
        JsonNode statsNode = skillNode.path("stats");
        JsonNode tagsNode = skillNode.path("tags");
        JsonNode latestVerNode = root.path("latestVersion");
        JsonNode ownerNode = root.path("owner");

        ClawHubModels.SkillDetail d = new ClawHubModels.SkillDetail();
        d.slug = textOr(skillNode, "slug", slug);
        d.name = textOr(skillNode, "displayName", d.slug);
        d.description = textOr(skillNode, "summary", null);
        d.author = textOr(ownerNode, "displayName", textOr(ownerNode, "handle", null));
        d.latestVersion = textOr(latestVerNode, "version", textOr(tagsNode, "latest", null));
        d.downloads = statsNode.path("downloads").asInt(0);
        d.stars = statsNode.path("stars").asInt(0);
        d.suspicious = root.path("moderation").path("flagged").asBoolean(false);
        d.homepage = textOr(skillNode, "homepage", null);
        // 版本列表(顶层可选)
        JsonNode versions = root.path("versions");
        if (versions.isArray()) {
            d.versions = new ArrayList<>();
            for (JsonNode v : versions) {
                d.versions.add(textOr(v, "version", v.asText()));
            }
        }
        return d;
    }

    /**
     * 获取官方安全扫描结果。
     * 实际响应:
     *   { security: { status, hasWarnings, scanners: { vt: {verdict}, llm: {verdict, summary, guidance} } } }
     * status / verdict 取值:clean / benign / suspicious / malicious
     */
    public ClawHubModels.ScanReport getScan(String slug, String version) throws IOException {
        HttpUrl.Builder b = HttpUrl.parse(props.getBaseUrl() + "/api/v1/skills/" + encodeSlug(slug) + "/scan").newBuilder();
        if (version != null && !version.isBlank()) {
            b.addQueryParameter("version", version);
        }
        HttpUrl url = b.build();
        try {
            JsonNode root = getJson(url);
            JsonNode sec = root.path("security");
            String status = textOr(sec, "status", "unknown");
            JsonNode vt = sec.path("scanners").path("vt");
            JsonNode llm = sec.path("scanners").path("llm");
            String vtVerdict = textOr(vt, "verdict", null);
            String llmVerdict = textOr(llm, "verdict", null);

            ClawHubModels.ScanReport r = new ClawHubModels.ScanReport();
            r.malicious = "malicious".equalsIgnoreCase(status)
                    || "malicious".equalsIgnoreCase(vtVerdict)
                    || "malicious".equalsIgnoreCase(llmVerdict);
            r.suspicious = "suspicious".equalsIgnoreCase(status)
                    || "suspicious".equalsIgnoreCase(vtVerdict)
                    || "suspicious".equalsIgnoreCase(llmVerdict)
                    || sec.path("hasWarnings").asBoolean(false);
            r.score = -1; // 上游不直接给数字 score
            // summary 优先取 LLM scanner 的人类可读说明
            r.summary = textOr(llm, "summary", textOr(llm, "guidance", "status=" + status));
            r.rawJson = root.toString();
            return r;
        } catch (IOException e) {
            // scan 接口不存在或失败时不阻塞流程,只标记 unknown
            log.warn("ClawHub scan fetch failed for {}: {}", slug, e.getMessage());
            ClawHubModels.ScanReport r = new ClawHubModels.ScanReport();
            r.summary = "scan unavailable: " + e.getMessage();
            r.rawJson = "{}";
            return r;
        }
    }

    /**
     * 下载 skill zip 包,返回原始字节(在内存中,后续交给 SafetyChecker 校验)。
     */
    public byte[] download(String slug, String version) throws IOException {
        HttpUrl.Builder b = HttpUrl.parse(props.getBaseUrl() + "/api/v1/download").newBuilder()
                .addQueryParameter("slug", slug);
        if (version != null && !version.isBlank()) {
            b.addQueryParameter("version", version);
        }
        HttpUrl url = b.build();

        Request req = new Request.Builder().url(url).get().build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("ClawHub download HTTP " + resp.code() + " for " + slug);
            }
            ResponseBody body = resp.body();
            if (body == null) throw new IOException("Empty response body for " + slug);
            // 限制单次下载字节(防御端点返回过大文件)
            long contentLength = body.contentLength();
            if (contentLength > 0 && contentLength > 50L * 1024 * 1024) {
                throw new IOException("ClawHub package too large: " + contentLength + " bytes");
            }
            return body.bytes();
        }
    }

    // ======== helpers ========

    private JsonNode getJson(HttpUrl url) throws IOException {
        Request req = new Request.Builder().url(url).get()
                .header("Accept", "application/json")
                .build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("ClawHub HTTP " + resp.code() + " " + url);
            }
            ResponseBody body = resp.body();
            if (body == null) throw new IOException("Empty response from " + url);
            return json.readTree(body.string());
        }
    }

    private ClawHubModels.SkillSummary parseSummary(JsonNode n) {
        ClawHubModels.SkillSummary s = new ClawHubModels.SkillSummary();
        s.slug = textOr(n, "slug", null);
        s.name = textOr(n, "name", s.slug);
        s.description = textOr(n, "description", null);
        s.downloads = n.path("downloads").asInt(0);
        s.stars = n.path("stars").asInt(0);
        s.suspicious = n.path("suspicious").asBoolean(false);
        s.latestVersion = textOr(n, "latestVersion", textOr(n, "version", null));
        return s;
    }

    private static String textOr(JsonNode n, String field, String fallback) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) return fallback;
        String s = v.asText();
        return (s == null || s.isEmpty()) ? fallback : s;
    }

    private static String encodeSlug(String slug) {
        // slug 通常 [a-z0-9-_/] —— 简单替换 / 即可,避免依赖 URLEncoder
        return slug.replace(" ", "%20");
    }
}
