package com.skillforge.tools.mcp.transport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.tools.mcp.exception.McpClientException;
import com.skillforge.tools.mcp.protocol.McpRequest;
import com.skillforge.tools.mcp.protocol.McpResponse;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Streamable-HTTP MCP transport. Each JSON-RPC request is one {@code POST} to the
 * configured endpoint; the response is parsed back into an {@link McpResponse}.
 *
 * <p>This is the "client-POST-only" subset of the MCP Streamable HTTP transport
 * (server→client GET long-poll SSE is out of scope — tool calls don't need it):
 * <ul>
 *   <li>Every request POSTs with {@code Content-Type: application/json} and
 *       {@code Accept: application/json, text/event-stream}, plus the caller-supplied
 *       headers (e.g. {@code Authorization}).</li>
 *   <li>The response is demuxed by {@code Content-Type}: a plain
 *       {@code application/json} body is parsed directly; a
 *       {@code text/event-stream} body is parsed as SSE and the
 *       JSON-RPC message matching the request id (or the last parseable message)
 *       is returned. Many hosted MCP servers answer a single request with one SSE
 *       frame, so we tolerate both.</li>
 *   <li>If the {@code initialize} response carries an {@code Mcp-Session-Id} header
 *       we capture it and echo it on every subsequent request (spec requirement).</li>
 * </ul>
 *
 * <p>Concurrency: {@link OkHttpClient} is thread-safe and every {@link #sendRequest}
 * builds an independent {@link Call}, correlating purely by the JSON-RPC {@code id}
 * carried in the HTTP exchange itself — there is no shared pending mailbox like the
 * stdio transport. The only mutable shared state is {@link #sessionId} (a
 * {@code volatile} string) and {@link #closed}.
 *
 * <p>This class is unaware of MCP semantics (initialize / tools/list); it speaks raw
 * JSON-RPC over HTTP. {@code McpServerSession} owns the protocol state machine.
 *
 * <p>Security: error messages never include request headers or bodies (which carry
 * the bearer token); only the server name, endpoint URL, HTTP status, and method.
 */
public class McpHttpTransport implements McpTransport {

    private static final Logger log = LoggerFactory.getLogger(McpHttpTransport.class);

    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final String ACCEPT_VALUE = "application/json, text/event-stream";
    private static final String SESSION_ID_HEADER = "Mcp-Session-Id";

    /**
     * Hard cap on the response body we'll buffer into memory. A malicious or
     * malfunctioning upstream could otherwise stream an unbounded body and OOM the
     * agent-loop thread pool. 16 MiB is comfortably above any sane tools/list or
     * tools/call payload.
     */
    private static final long MAX_BODY_BYTES = 16L * 1024 * 1024;

    private final String serverName;
    private final HttpUrl url;
    private final Map<String, String> headers;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;

    /** Captured from the initialize response (if the server issues one). */
    private volatile String sessionId;

    private final AtomicBoolean closed = new AtomicBoolean(false);

    public McpHttpTransport(String serverName,
                            String url,
                            Map<String, String> headers,
                            ObjectMapper objectMapper,
                            OkHttpClient httpClient) {
        if (serverName == null || serverName.isBlank()) {
            throw new IllegalArgumentException("serverName is required");
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url is required");
        }
        HttpUrl parsed = HttpUrl.parse(url.trim());
        if (parsed == null) {
            throw new IllegalArgumentException("Invalid MCP HTTP url for '" + serverName + "': " + url);
        }
        if (objectMapper == null) {
            throw new IllegalArgumentException("objectMapper is required");
        }
        if (httpClient == null) {
            throw new IllegalArgumentException("httpClient is required");
        }
        this.serverName = serverName;
        this.url = parsed;
        // Defensive immutable copy; preserve insertion order for deterministic wire output.
        this.headers = headers != null
                ? Map.copyOf(new LinkedHashMap<>(headers))
                : Map.of();
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public void start() {
        // No process / no long-lived connection. Reset closed so the transport is
        // reusable after a close() (idempotent).
        closed.set(false);
    }

    @Override
    public McpResponse sendRequest(McpRequest request, long timeoutMillis) {
        if (closed.get()) {
            throw new McpClientException("MCP transport '" + serverName + "' is closed");
        }
        if (request.id() == null) {
            throw new IllegalArgumentException(
                    "sendRequest requires a non-null id; use sendNotification() for fire-and-forget");
        }
        String body = encode(request);
        Request httpRequest = buildHttpRequest(body);
        OkHttpClient call = clientForTimeout(timeoutMillis);
        try (Response response = call.newCall(httpRequest).execute()) {
            captureSessionId(response);
            if (!response.isSuccessful()) {
                throw new McpClientException("MCP server '" + serverName + "' HTTP "
                        + response.code() + " for method=" + request.method()
                        + " url=" + url.redact());
            }
            String text = readBody(response);
            return parseResponse(response, text, request);
        } catch (IOException e) {
            // Never include e's request context verbatim — OkHttp IOExceptions don't carry
            // headers, but keep the message scoped to method/url/server to be safe.
            throw new McpClientException("MCP server '" + serverName + "' request failed for method="
                    + request.method() + " url=" + url.redact() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void sendNotification(McpRequest notification) {
        if (closed.get()) {
            log.debug("Dropping notification on closed transport '{}': {}", serverName, notification.method());
            return;
        }
        if (notification.id() != null) {
            throw new IllegalArgumentException("sendNotification requires id == null");
        }
        String body = encode(notification);
        Request httpRequest = buildHttpRequest(body);
        try (Response response = httpClient.newCall(httpRequest).execute()) {
            captureSessionId(response);
            if (!response.isSuccessful()) {
                // Best-effort: a failed notification must not blow up the handshake.
                log.warn("MCP server '{}' notification '{}' got HTTP {}",
                        serverName, notification.method(), response.code());
            }
        } catch (IOException e) {
            log.warn("MCP server '{}' notification '{}' write failed: {}",
                    serverName, notification.method(), e.getMessage());
        }
    }

    @Override
    public boolean isAlive() {
        // HTTP has no persistent channel; "alive" simply means not explicitly closed.
        return !closed.get();
    }

    @Override
    public void close() {
        // Idempotent. No process to kill; just mark closed so further sends fail fast.
        closed.set(true);
    }

    // -----------------------------------------------------------------------
    // internals
    // -----------------------------------------------------------------------

    private String encode(McpRequest msg) {
        try {
            return objectMapper.writeValueAsString(msg);
        } catch (JsonProcessingException e) {
            throw new McpClientException("Failed to encode JSON-RPC request for '"
                    + serverName + "': " + e.getMessage(), e);
        }
    }

    private Request buildHttpRequest(String jsonBody) {
        Request.Builder b = new Request.Builder()
                .url(url)
                .post(RequestBody.create(jsonBody, JSON_MEDIA_TYPE))
                .header("Accept", ACCEPT_VALUE);
        // Caller-supplied headers (Authorization, etc.). These overwrite defaults if
        // the operator set the same key. Content-Type is implied by the RequestBody.
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                b.header(e.getKey(), e.getValue());
            }
        }
        String sid = sessionId;
        if (sid != null && !sid.isBlank()) {
            b.header(SESSION_ID_HEADER, sid);
        }
        return b.build();
    }

    /**
     * Derive a per-call client honoring the session-supplied timeout. {@code newBuilder()}
     * shares the connection pool / dispatcher with the base client so this is cheap.
     */
    private OkHttpClient clientForTimeout(long timeoutMillis) {
        if (timeoutMillis <= 0) {
            return httpClient;
        }
        return httpClient.newBuilder()
                .callTimeout(Duration.ofMillis(timeoutMillis))
                .build();
    }

    private void captureSessionId(Response response) {
        String sid = response.header(SESSION_ID_HEADER);
        if (sid != null && !sid.isBlank()) {
            this.sessionId = sid;
        }
    }

    private String readBody(Response response) throws IOException {
        ResponseBody rb = response.body();
        if (rb == null) return "";
        // Fast reject when the server declares an over-limit Content-Length.
        long declared = rb.contentLength();
        if (declared > MAX_BODY_BYTES) {
            throw oversizeBody(declared);
        }
        // Chunked / unknown-length bodies: buffer at most MAX_BODY_BYTES + 1 to detect
        // overflow without materializing an unbounded stream. request(n) returns false
        // iff the whole body is shorter than n (already fully buffered, ≤ limit).
        BufferedSource source = rb.source();
        if (source.request(MAX_BODY_BYTES + 1)) {
            throw oversizeBody(MAX_BODY_BYTES + 1);
        }
        return source.readUtf8();
    }

    private McpClientException oversizeBody(long size) {
        return new McpClientException("MCP server '" + serverName
                + "' response body exceeds " + MAX_BODY_BYTES + " bytes (>=" + size
                + ") url=" + url.redact());
    }

    private McpResponse parseResponse(Response response, String text, McpRequest request) {
        MediaType contentType = contentTypeOf(response);
        boolean sse = contentType != null
                && "text".equalsIgnoreCase(contentType.type())
                && "event-stream".equalsIgnoreCase(contentType.subtype());
        if (sse) {
            return parseSse(text, request);
        }
        return parseJson(text, request);
    }

    private MediaType contentTypeOf(Response response) {
        String header = response.header("Content-Type");
        if (header == null) return null;
        try {
            return MediaType.parse(header);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private McpResponse parseJson(String text, McpRequest request) {
        if (text == null || text.isBlank()) {
            throw new McpClientException("MCP server '" + serverName + "' returned an empty body for method="
                    + request.method());
        }
        try {
            return objectMapper.readValue(text, McpResponse.class);
        } catch (JsonProcessingException e) {
            throw new McpClientException("MCP server '" + serverName
                    + "' returned non-JSON-RPC body for method=" + request.method()
                    + ": " + e.getMessage(), e);
        }
    }

    /**
     * Parse an SSE stream and return the JSON-RPC message matching the request id, or
     * the last parseable JSON-RPC message if none matches. SSE framing per spec: events
     * are separated by blank lines; {@code data:} lines within one event are joined by
     * {@code \n}; lines beginning with {@code :} are comments and ignored.
     */
    private McpResponse parseSse(String text, McpRequest request) {
        if (text == null || text.isBlank()) {
            throw new McpClientException("MCP server '" + serverName
                    + "' returned an empty SSE body for method=" + request.method());
        }
        String wantId = String.valueOf(request.id());
        McpResponse lastParseable = null;
        StringBuilder data = new StringBuilder();
        // Normalize CRLF / CR to LF then split on LF so SSE line handling is uniform.
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        for (String line : normalized.split("\n", -1)) {
            if (line.isEmpty()) {
                // event boundary
                McpResponse parsed = tryParseEvent(data.toString());
                data.setLength(0);
                if (parsed != null) {
                    lastParseable = parsed;
                    if (parsed.id() != null && wantId.equals(String.valueOf(parsed.id()))) {
                        return parsed;
                    }
                }
                continue;
            }
            if (line.startsWith(":")) {
                continue; // comment
            }
            if (line.startsWith("data:")) {
                String chunk = line.substring("data:".length());
                if (chunk.startsWith(" ")) {
                    chunk = chunk.substring(1); // strip single optional leading space (SSE spec)
                }
                if (data.length() > 0) data.append('\n');
                data.append(chunk);
            }
            // other fields (event:, id:, retry:) ignored — we only need the JSON payload
        }
        // Flush a trailing event with no terminating blank line.
        McpResponse parsed = tryParseEvent(data.toString());
        if (parsed != null) {
            lastParseable = parsed;
            if (parsed.id() != null && wantId.equals(String.valueOf(parsed.id()))) {
                return parsed;
            }
        }
        if (lastParseable != null) {
            return lastParseable;
        }
        throw new McpClientException("MCP server '" + serverName
                + "' SSE response contained no parseable JSON-RPC message for method=" + request.method());
    }

    private McpResponse tryParseEvent(String data) {
        if (data == null || data.isBlank()) return null;
        try {
            return objectMapper.readValue(data, McpResponse.class);
        } catch (JsonProcessingException e) {
            // A non-JSON-RPC SSE frame (e.g. a keep-alive / progress event) — skip it.
            return null;
        }
    }
}
