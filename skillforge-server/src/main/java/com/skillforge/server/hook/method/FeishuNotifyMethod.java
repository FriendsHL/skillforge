package com.skillforge.server.hook.method;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.hook.BuiltInMethod;
import com.skillforge.core.engine.hook.HookExecutionContext;
import com.skillforge.core.engine.hook.HookRunResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sends a notification to a Feishu (Lark) bot webhook using the interactive card format.
 *
 * <p>SSRF validation allows Feishu domains ({@code open.feishu.cn}, {@code open.larksuite.com})
 * while blocking all other internal/private addresses.
 */
@Component
public class FeishuNotifyMethod implements BuiltInMethod {

    private static final Logger log = LoggerFactory.getLogger(FeishuNotifyMethod.class);

    private static final Set<String> FEISHU_ALLOWED_HOSTS = Set.of(
            "open.feishu.cn", "open.larksuite.com"
    );
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_RESPONSE_BODY = 2048;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    private final ObjectMapper objectMapper;

    public FeishuNotifyMethod(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String ref() {
        return "builtin.feishu.notify";
    }

    @Override
    public String displayName() {
        return "Feishu Notify";
    }

    @Override
    public String description() {
        return "Sends a notification to a Feishu (Lark) bot webhook with interactive card format.";
    }

    @Override
    public Map<String, String> argsSchema() {
        return Map.of(
                "webhook_url", "String (required) — Feishu bot webhook URL",
                "title", "String (optional) — card title",
                "content", "String (optional) — card content text; auto-generates from context if absent"
        );
    }

    @Override
    public HookRunResult execute(Map<String, Object> args, HookExecutionContext ctx) {
        long t0 = System.currentTimeMillis();

        Object webhookArg = args.get("webhook_url");
        if (webhookArg == null || webhookArg.toString().isBlank()) {
            return HookRunResult.failure("webhook_url is required", elapsed(t0));
        }
        String webhookUrl = webhookArg.toString().strip();

        // SSRF check — allow Feishu domains
        String ssrfError = UrlValidator.validate(webhookUrl, FEISHU_ALLOWED_HOSTS);
        if (ssrfError != null) {
            return HookRunResult.failure("ssrf_blocked: " + ssrfError, elapsed(t0));
        }

        // Build title
        Object titleArg = args.get("title");
        String title = (titleArg != null && !titleArg.toString().isBlank())
                ? titleArg.toString()
                : "SkillForge Hook Notification";

        // Build content — auto-generate from context if not provided
        Object contentArg = args.get("content");
        String content;
        if (contentArg != null && !contentArg.toString().isBlank()) {
            content = contentArg.toString();
        } else {
            content = autoContent(ctx);
        }

        // Build Feishu interactive card payload
        Map<String, Object> payload = buildCardPayload(title, content);

        try {
            String bodyJson = objectMapper.writeValueAsString(payload);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                    .build();

            HttpResponse<String> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            int status = resp.statusCode();
            long dur = elapsed(t0);

            if (status >= 200 && status < 300) {
                return HookRunResult.ok("HTTP " + status, dur);
            }
            String truncatedBody = truncate(resp.body(), MAX_RESPONSE_BODY);
            return HookRunResult.failure("HTTP " + status + ": " + truncatedBody, dur);

        } catch (Exception e) {
            log.warn("[FeishuNotify] request to {} failed: {}", webhookUrl, e.toString());
            return HookRunResult.failure(
                    "request_failed: " + HttpPostMethod.sanitizeError(e), elapsed(t0));
        }
    }

    private static String autoContent(HookExecutionContext ctx) {
        String event = ctx.event() != null ? ctx.event().wireName() : "unknown";
        String sessionId = ctx.sessionId() != null ? ctx.sessionId() : "N/A";
        return "Event: " + event + "\nSession: " + sessionId + "\nTime: " + Instant.now();
    }

    private static Map<String, Object> buildCardPayload(String title, String content) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("msg_type", "interactive");

        Map<String, Object> card = new LinkedHashMap<>();

        // Header
        Map<String, Object> header = new LinkedHashMap<>();
        Map<String, Object> titleObj = new LinkedHashMap<>();
        titleObj.put("tag", "plain_text");
        titleObj.put("content", title);
        header.put("title", titleObj);
        card.put("header", header);

        // Elements — single markdown element
        Map<String, Object> element = new LinkedHashMap<>();
        element.put("tag", "div");
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("tag", "plain_text");
        text.put("content", content);
        element.put("text", text);
        card.put("elements", List.of(element));

        payload.put("card", card);
        return payload;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }

    private static long elapsed(long t0) {
        return System.currentTimeMillis() - t0;
    }
}
