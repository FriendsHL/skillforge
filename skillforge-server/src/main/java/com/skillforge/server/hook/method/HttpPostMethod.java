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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Sends an HTTP POST request to an external URL. Useful for webhook integrations.
 *
 * <p>Uses {@link java.net.http.HttpClient} (JDK built-in) to keep dependencies lightweight.
 * Basic SSRF protection via {@link UrlValidator}.
 */
@Component
public class HttpPostMethod implements BuiltInMethod {

    private static final Logger log = LoggerFactory.getLogger(HttpPostMethod.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_RESPONSE_BODY = 2048;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    private static final Set<String> BLOCKED_HEADER_NAMES = Set.of(
            "host", "content-type", "content-length", "transfer-encoding", "connection"
    );

    private final ObjectMapper objectMapper;

    public HttpPostMethod(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String ref() {
        return "builtin.http.post";
    }

    @Override
    public String displayName() {
        return "HTTP POST";
    }

    @Override
    public String description() {
        return "Sends an HTTP POST to an external URL. Supports custom headers and body.";
    }

    @Override
    public Map<String, String> argsSchema() {
        return Map.of(
                "url", "String (required) — target URL",
                "headers", "Map (optional) — custom HTTP headers",
                "body", "Object (optional) — request body; if absent, uses the full hook input"
        );
    }

    @Override
    public HookRunResult execute(Map<String, Object> args, HookExecutionContext ctx) {
        long t0 = System.currentTimeMillis();

        Object urlArg = args.get("url");
        if (urlArg == null || urlArg.toString().isBlank()) {
            return HookRunResult.failure("url is required", elapsed(t0));
        }
        String url = urlArg.toString().strip();

        String ssrfError = UrlValidator.validate(url);
        if (ssrfError != null) {
            return HookRunResult.failure("ssrf_blocked: " + ssrfError, elapsed(t0));
        }

        return doPost(url, args, ctx, t0, Set.of());
    }

    /**
     * Shared POST logic, usable by subclasses or other methods that need
     * to POST with different SSRF allowed-host lists.
     */
    protected HookRunResult doPost(String url, Map<String, Object> args,
                                   HookExecutionContext ctx, long t0,
                                   Set<String> allowedHosts) {
        // Build body
        Object bodyArg = args.get("body");
        String bodyJson;
        try {
            if (bodyArg != null) {
                bodyJson = objectMapper.writeValueAsString(bodyArg);
            } else {
                bodyJson = objectMapper.writeValueAsString(args);
            }
        } catch (Exception e) {
            return HookRunResult.failure("body serialization failed: " + e.getMessage(), elapsed(t0));
        }

        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson));

            Object headersArg = args.get("headers");
            if (headersArg instanceof Map<?, ?> headerMap) {
                for (Map.Entry<?, ?> entry : headerMap.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        String key = entry.getKey().toString();
                        if (BLOCKED_HEADER_NAMES.contains(key.toLowerCase(Locale.ROOT))) {
                            continue;
                        }
                        reqBuilder.header(key, entry.getValue().toString());
                    }
                }
            }

            HttpResponse<String> resp = HTTP_CLIENT.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            int status = resp.statusCode();
            long dur = elapsed(t0);

            if (status >= 200 && status < 300) {
                return HookRunResult.ok("HTTP " + status, dur);
            }
            String truncatedBody = truncate(resp.body(), MAX_RESPONSE_BODY);
            return HookRunResult.failure("HTTP " + status + ": " + truncatedBody, dur);

        } catch (Exception e) {
            log.warn("[HttpPost] request to {} failed: {}", url, e.toString());
            return HookRunResult.failure("request_failed: " + sanitizeError(e), elapsed(t0));
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }

    static String sanitizeError(Exception e) {
        if (e instanceof java.net.http.HttpTimeoutException) return "timeout";
        if (e instanceof java.net.http.HttpConnectTimeoutException) return "connect_timeout";
        if (e instanceof java.io.IOException) return "io_error";
        if (e instanceof InterruptedException) return "interrupted";
        return "network_error";
    }

    private static long elapsed(long t0) {
        return System.currentTimeMillis() - t0;
    }
}
