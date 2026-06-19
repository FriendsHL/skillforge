package com.skillforge.server.acp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Newline-delimited JSON-RPC 2.0 client for the Agent Client Protocol (ACP).
 *
 * <p>ACP-EXTERNAL-AGENT P1a-1 — PURE PROTOCOL LAYER. Drives an ACP agent adapter
 * (cc / codex) over an injected {@link AcpTransport}. Responsibilities:
 * <ul>
 *   <li>async request/response correlation by JSON-RPC {@code id}
 *       ({@link CompletableFuture} map);</li>
 *   <li>protocol methods: {@link #initialize()}, {@link #newSession},
 *       {@link #prompt}, {@link #cancel}, {@link #setModel};</li>
 *   <li>deliver inbound {@code session/update} notifications (translated via
 *       {@link AcpUpdateTranslator}) to a listener;</li>
 *   <li>forward server→client requests (permission) to a handler — defaulting to
 *       a DENY so a stray request cannot hang the agent.</li>
 * </ul>
 *
 * <p>NO SkillForge session/Message/UI integration here — that is P1a-2 / P1b.
 *
 * <p>Threading: inbound lines arrive on the transport's reader thread; futures
 * complete there. Callers should chain off the returned futures (or block with a
 * timeout) rather than assuming the calling thread runs the completion.
 *
 * <p><b>No request timeout (J-W1):</b> the futures returned by {@link #initialize()}
 * / {@link #newSession} / {@link #prompt} / {@link #setModel} do NOT time out. If
 * the adapter never sends a matching response, the future stays pending forever
 * and its entry lives in the correlation map until {@link #close()} (which fails
 * all in-flight futures). Callers that need a deadline MUST impose their own —
 * e.g. {@code future.orTimeout(30, TimeUnit.SECONDS)} or {@code get(timeout, ...)}.
 * The P1a-2 runner will likely add a default timeout.
 */
public class AcpClient {

    private static final Logger log = LoggerFactory.getLogger(AcpClient.class);

    /** ACP protocol version negotiated in {@code initialize} (spike-verified: 1). */
    private static final int PROTOCOL_VERSION = 1;

    /** The server→client request method whose deny answer is a permission outcome. */
    private static final String PERMISSION_REQUEST_METHOD = "session/request_permission";

    /** JSON-RPC "method not found" error code (used to reject unsupported server requests). */
    private static final int RPC_METHOD_NOT_FOUND = -32601;

    private final AcpTransport transport;
    private final ObjectMapper objectMapper;
    private final AcpUpdateTranslator translator;

    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

    /** Listener for translated {@code session/update} notifications. No-op default. */
    private volatile Consumer<AcpSessionUpdate> updateListener = u -> { };

    /**
     * Handler for server→client requests (permission). Default DENIES so a stray
     * request can never block the agent. TODO(P1b): the permission bridge replaces
     * this with one that maps to SkillForge ask/confirmation.
     *
     * <p><b>MUST NOT BLOCK (J-W3):</b> invoked on the transport reader thread —
     * see {@link #setServerRequestHandler}.
     */
    private volatile Consumer<AcpServerRequest> serverRequestHandler = this::defaultDenyServerRequest;

    private volatile boolean started;

    public AcpClient(AcpTransport transport, ObjectMapper objectMapper, AcpUpdateTranslator translator) {
        this.transport = transport;
        this.objectMapper = objectMapper;
        this.translator = translator;
    }

    // ───────────────────────────── lifecycle ─────────────────────────────

    /** Wire the transport line listener and start it. Idempotent. */
    public synchronized void start() {
        if (started) {
            return;
        }
        transport.setLineListener(this::onLine);
        transport.start();
        started = true;
    }

    /**
     * Close the transport and fail any in-flight requests so callers blocked on
     * their futures unblock instead of hanging.
     */
    public void close() {
        transport.close();
        AcpException closed = new AcpException("ACP transport closed");
        pending.forEach((id, fut) -> fut.completeExceptionally(closed));
        pending.clear();
    }

    // ───────────────────────────── listeners ─────────────────────────────

    /** Register the {@code session/update} listener (translated, typed). */
    public void setUpdateListener(Consumer<AcpSessionUpdate> listener) {
        this.updateListener = listener != null ? listener : u -> { };
    }

    /**
     * Register the server→client request handler. The handler is responsible for
     * eventually answering via {@link #respondToServerRequest} (P1b). If left
     * unset (or set to {@code null}) the default DENY handler is used.
     *
     * <p><b>WARNING — the handler is invoked ON THE TRANSPORT READER THREAD and
     * MUST NOT BLOCK (J-W3):</b> no blocking waits, no synchronous user-confirmation
     * round-trips, no long I/O inside it. The reader thread is the single pump for
     * ALL inbound messages (notifications AND request/response correlations), so a
     * blocking handler stalls every subsequent message — including the responses
     * the caller is awaiting — and can deadlock the session. P1b's permission
     * bridge MUST hand off asynchronously (e.g. enqueue the request, return
     * immediately) and answer later via {@link #respondToServerRequest}.
     */
    public void setServerRequestHandler(Consumer<AcpServerRequest> handler) {
        this.serverRequestHandler = handler != null ? handler : this::defaultDenyServerRequest;
    }

    // ──────────────────────────── ACP methods ────────────────────────────

    /**
     * {@code initialize} handshake. Spike-verified params:
     * {@code {protocolVersion:1, clientCapabilities:{fs:{readTextFile,writeTextFile}}}}.
     */
    public CompletableFuture<JsonNode> initialize() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        ObjectNode fs = objectMapper.createObjectNode();
        // P1a-1 does not implement the fs bridge; advertise no fs capabilities.
        fs.put("readTextFile", false);
        fs.put("writeTextFile", false);
        ObjectNode caps = objectMapper.createObjectNode();
        caps.set("fs", fs);
        params.set("clientCapabilities", caps);
        return request("initialize", params);
    }

    /**
     * {@code session/new}. Spike-verified params {@code {cwd, mcpServers}} → result
     * {@code {sessionId, models{...}, modes{...}}}.
     *
     * @param cwd        working directory for the agent session
     * @param mcpServers MCP server configs (empty list ⇒ {@code []}); never null
     */
    public CompletableFuture<JsonNode> newSession(String cwd, List<JsonNode> mcpServers) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("cwd", cwd);
        ArrayNode servers = objectMapper.createArrayNode();
        if (mcpServers != null) {
            mcpServers.forEach(servers::add);
        }
        params.set("mcpServers", servers);
        return request("session/new", params);
    }

    /**
     * {@code session/prompt}. Spike-verified params
     * {@code {sessionId, prompt:[{type:"text",text}]}} → streams
     * {@code session/update} notifications, then a final result
     * {@code {stopReason}}.
     *
     * @param sessionId    target session
     * @param promptBlocks content blocks (e.g. {@code {type:"text", text:"..."}})
     */
    public CompletableFuture<JsonNode> prompt(String sessionId, List<JsonNode> promptBlocks) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("sessionId", sessionId);
        ArrayNode blocks = objectMapper.createArrayNode();
        if (promptBlocks != null) {
            promptBlocks.forEach(blocks::add);
        }
        params.set("prompt", blocks);
        // Never log prompt content at INFO (sensitive). DEBUG only, block count only.
        log.debug("ACP session/prompt sessionId={} blocks={}", sessionId, blocks.size());
        return request("session/prompt", params);
    }

    /**
     * {@code session/cancel} — a notification (no response expected) per ACP.
     * Returns once the line has been written.
     */
    public void cancel(String sessionId) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("sessionId", sessionId);
        notify("session/cancel", params);
    }

    /** {@code session/set_model} — switch the session's model (from session/new {@code models}). */
    public CompletableFuture<JsonNode> setModel(String sessionId, String modelId) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("sessionId", sessionId);
        params.put("modelId", modelId);
        return request("session/set_model", params);
    }

    // ─────────────────────── server-request response ─────────────────────

    /**
     * Send a response to a server→client request. P1b's permission bridge calls
     * this with the real outcome; the default handler uses it to DENY.
     *
     * @param id     the request id to echo (from {@link AcpServerRequest#id()})
     * @param result the JSON-RPC {@code result} payload
     */
    public void respondToServerRequest(JsonNode id, JsonNode result) {
        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.set("id", id);
        msg.set("result", result != null ? result : objectMapper.nullNode());
        writeMessage(msg);
    }

    /**
     * Send a JSON-RPC {@code error} response to a server→client request. Used to
     * reject a server request we don't know how to satisfy without faking a
     * domain-specific (e.g. permission) outcome.
     */
    public void respondToServerRequestError(JsonNode id, int code, String message) {
        ObjectNode error = objectMapper.createObjectNode();
        error.put("code", code);
        error.put("message", message);
        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.set("id", id);
        msg.set("error", error);
        writeMessage(msg);
    }

    // ───────────────────────────── internals ─────────────────────────────

    private CompletableFuture<JsonNode> request(String method, JsonNode params) {
        long id = nextId.getAndIncrement();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);

        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("id", id);
        msg.put("method", method);
        msg.set("params", params);
        try {
            writeMessage(msg);
        } catch (RuntimeException e) {
            // Write failed before any response could arrive: drop the correlation entry
            // and fail the future. If close() races and also completes it, the second
            // completeExceptionally is a benign no-op (CompletableFuture ignores it).
            pending.remove(id);
            future.completeExceptionally(e);
        }
        return future;
    }

    private void notify(String method, JsonNode params) {
        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("method", method);
        msg.set("params", params);
        writeMessage(msg);
    }

    private void writeMessage(ObjectNode msg) {
        try {
            transport.send(objectMapper.writeValueAsString(msg));
        } catch (JsonProcessingException e) {
            throw new AcpException("Failed to serialize ACP message", e);
        }
    }

    /** Inbound line dispatch — invoked on the transport reader thread. Never throws. */
    void onLine(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        JsonNode msg;
        try {
            msg = objectMapper.readTree(line);
        } catch (JsonProcessingException e) {
            log.warn("ACP received non-JSON line (ignored): {}", truncate(line));
            return;
        }
        try {
            JsonNode idNode = msg.get("id");
            boolean hasId = idNode != null && !idNode.isNull();
            boolean isResponse = msg.has("result") || msg.has("error");

            if (hasId && isResponse) {
                handleResponse(idNode, msg);
            } else if (msg.hasNonNull("method") && hasId) {
                handleServerRequest(idNode, msg);
            } else if (msg.hasNonNull("method")) {
                handleNotification(msg);
            } else {
                log.debug("ACP unrecognized message (ignored): {}", truncate(line));
            }
        } catch (RuntimeException e) {
            log.warn("ACP failed to dispatch inbound message (ignored)", e);
        }
    }

    private void handleResponse(JsonNode idNode, JsonNode msg) {
        Long id = idNode.canConvertToLong() ? idNode.asLong() : null;
        CompletableFuture<JsonNode> future = (id != null) ? pending.remove(id) : null;
        if (future == null) {
            log.debug("ACP response for unknown/duplicate id {} (ignored)", idNode);
            return;
        }
        JsonNode error = msg.get("error");
        if (error != null && !error.isNull()) {
            future.completeExceptionally(new AcpException(
                    "ACP error response: " + error.toString(),
                    error.path("code").asInt(0)));
        } else {
            // result may legitimately be null (e.g. an empty ack); pass it through.
            future.complete(msg.get("result"));
        }
    }

    private void handleServerRequest(JsonNode idNode, JsonNode msg) {
        AcpServerRequest req = new AcpServerRequest(
                idNode, msg.get("method").asText(), msg.get("params"));
        log.debug("ACP server→client request method={} id={}", req.method(), idNode);
        try {
            serverRequestHandler.accept(req);
        } catch (RuntimeException e) {
            // Handler blew up — deny so the agent is not left hanging.
            log.warn("ACP server-request handler threw; denying request id={}", idNode, e);
            defaultDenyServerRequest(req);
        }
    }

    private void handleNotification(JsonNode msg) {
        String method = msg.get("method").asText();
        if (!"session/update".equals(method)) {
            log.debug("ACP notification (no listener for '{}')", method);
            return;
        }
        JsonNode params = msg.get("params");
        String sessionId = (params != null && params.hasNonNull("sessionId"))
                ? params.get("sessionId").asText() : null;
        JsonNode updateNode = (params != null) ? params.get("update") : null;
        AcpUpdate update = translator.translate(updateNode);
        try {
            updateListener.accept(new AcpSessionUpdate(sessionId, update));
        } catch (RuntimeException e) {
            log.warn("ACP update listener threw (ignored)", e);
        }
    }

    /**
     * Default server-request response so the agent never hangs (D-W2).
     *
     * <p>The permission-shaped deny ({@code {outcome:{outcome:"cancelled"}}}) is
     * applied ONLY to the actual permission request method. Any other / unknown
     * server→client request gets a generic JSON-RPC "method not found" error
     * instead of a faked permission outcome — applying a permission outcome to a
     * non-permission method would confuse P1b debugging.
     */
    private void defaultDenyServerRequest(AcpServerRequest req) {
        try {
            if (PERMISSION_REQUEST_METHOD.equals(req.method())) {
                // ACP permission outcome shape (spike auto-deny): {outcome:{outcome:"cancelled"}}.
                ObjectNode inner = objectMapper.createObjectNode();
                inner.put("outcome", "cancelled");
                ObjectNode result = objectMapper.createObjectNode();
                result.set("outcome", inner);
                respondToServerRequest(req.id(), result);
                log.debug("ACP default-denied permission request id={}", req.id());
            } else {
                respondToServerRequestError(req.id(), RPC_METHOD_NOT_FOUND,
                        "Unsupported server request (P1a-1): " + req.method());
                log.debug("ACP rejected unsupported server request method={} id={}",
                        req.method(), req.id());
            }
        } catch (RuntimeException e) {
            log.warn("ACP failed to send default response for request id={}", req.id(), e);
        }
    }

    private static String truncate(String s) {
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }
}
