package com.skillforge.server.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.engine.confirm.ConfirmationPromptPayload;
import com.skillforge.core.engine.confirm.Decision;
import com.skillforge.core.engine.confirm.PendingConfirmationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link AcpPermissionBridge}: a {@code session/request_permission}
 * → SkillForge confirmation (registry + WS broadcast) → async response via the ACP
 * responder. NO real cc; the responder is a hand-rolled idempotent recorder so we
 * can assert the outcome shape without an {@link AcpClient}.
 */
class AcpPermissionBridgeTest {

    private static final String SUB_SESSION = "sub-1";

    private ObjectMapper mapper;
    private PendingConfirmationRegistry registry;
    private ChatEventBroadcaster broadcaster;
    private ExecutorService waitExecutor;
    private AcpPermissionBridge bridge;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        registry = new PendingConfirmationRegistry();
        broadcaster = mock(ChatEventBroadcaster.class);
        waitExecutor = Executors.newCachedThreadPool();
        bridge = new AcpPermissionBridge(registry, broadcaster, waitExecutor, 5);
    }

    @AfterEach
    void tearDown() {
        waitExecutor.shutdownNow();
    }

    /** A recording responder that captures the outcome + counts terminal dispatches. */
    private static final class RecordingResponder implements AcpResponder {
        final AtomicReference<String> outcome = new AtomicReference<>();
        final AtomicReference<String> optionId = new AtomicReference<>();
        final java.util.concurrent.atomic.AtomicInteger dispatches =
                new java.util.concurrent.atomic.AtomicInteger();

        @Override public void respondResult(JsonNode result) { }
        @Override public void respondError(int code, String message) {
            dispatches.incrementAndGet(); outcome.set("error");
        }
        @Override public void selectPermissionOption(String id) {
            dispatches.incrementAndGet(); outcome.set("selected"); optionId.set(id);
        }
        @Override public void cancelPermission() {
            dispatches.incrementAndGet(); outcome.set("cancelled");
        }
        @Override public void deny() { cancelPermission(); }
        @Override public boolean isPermissionRequest() { return true; }
    }

    private AcpServerRequest permissionReq(String json) throws Exception {
        JsonNode msg = mapper.readTree(json);
        return new AcpServerRequest(msg.get("id"), msg.get("method").asText(), msg.get("params"));
    }

    private static final String PERMISSION_JSON =
            "{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"session/request_permission\",\"params\":{"
                    + "\"sessionId\":\"cc-1\","
                    + "\"options\":["
                    + "{\"kind\":\"allow_always\",\"name\":\"Always Allow all Write\",\"optionId\":\"allow_always\"},"
                    + "{\"kind\":\"allow_once\",\"name\":\"Allow\",\"optionId\":\"allow\"},"
                    + "{\"kind\":\"reject_once\",\"name\":\"Reject\",\"optionId\":\"reject\"}],"
                    + "\"toolCall\":{\"toolCallId\":\"tc-9\",\"title\":\"Write foo.txt\",\"kind\":\"edit\","
                    + "\"rawInput\":{\"path\":\"foo.txt\"}}}}";

    @Test
    @DisplayName("on approve, responds selected with the first allow optionId; broadcasts confirmation; reader not blocked")
    void approve_selectsAllowOption() throws Exception {
        RecordingResponder responder = new RecordingResponder();

        long t0 = System.nanoTime();
        bridge.handlePermissionRequest(SUB_SESSION, permissionReq(PERMISSION_JSON), responder);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        // The call returns immediately (does not block on the latch).
        assertThat(elapsedMs).isLessThan(1000);

        // A confirmation was registered + a WS card broadcast.
        ArgumentCaptor<ConfirmationPromptPayload> payloadCap =
                ArgumentCaptor.forClass(ConfirmationPromptPayload.class);
        verify(broadcaster).confirmationRequired(eq(SUB_SESSION), payloadCap.capture());
        ConfirmationPromptPayload payload = payloadCap.getValue();
        assertThat(payload.choices()).hasSize(3);
        assertThat(registry.size()).isEqualTo(1);

        // The human answers approve via the registry (as the dashboard endpoint would).
        registry.complete(payload.confirmationId(), Decision.APPROVED, null);

        await().atMost(3, TimeUnit.SECONDS).untilAtomic(responder.outcome, org.hamcrest.Matchers.notNullValue());
        assertThat(responder.outcome.get()).isEqualTo("selected");
        assertThat(responder.optionId.get()).isEqualTo("allow_always"); // first allow option
        assertThat(registry.exists(payload.confirmationId())).isFalse(); // cleaned up
    }

    @Test
    @DisplayName("on reject, responds selected with the reject optionId")
    void reject_selectsRejectOption() throws Exception {
        RecordingResponder responder = new RecordingResponder();
        bridge.handlePermissionRequest(SUB_SESSION, permissionReq(PERMISSION_JSON), responder);

        ArgumentCaptor<ConfirmationPromptPayload> cap = ArgumentCaptor.forClass(ConfirmationPromptPayload.class);
        verify(broadcaster).confirmationRequired(eq(SUB_SESSION), cap.capture());

        registry.complete(cap.getValue().confirmationId(), Decision.DENIED, null);

        await().atMost(3, TimeUnit.SECONDS).untilAtomic(responder.outcome, org.hamcrest.Matchers.notNullValue());
        assertThat(responder.outcome.get()).isEqualTo("selected");
        assertThat(responder.optionId.get()).isEqualTo("reject");
    }

    @Test
    @DisplayName("java-W2: double-answering the same confirmation produces exactly ONE responder dispatch")
    void doubleAnswer_singleDispatch() throws Exception {
        RecordingResponder responder = new RecordingResponder();
        bridge.handlePermissionRequest(SUB_SESSION, permissionReq(PERMISSION_JSON), responder);

        ArgumentCaptor<ConfirmationPromptPayload> cap = ArgumentCaptor.forClass(ConfirmationPromptPayload.class);
        verify(broadcaster).confirmationRequired(eq(SUB_SESSION), cap.capture());
        String confId = cap.getValue().confirmationId();

        // First answer wins; the registry CAS makes the second complete a no-op, so the
        // wait thread fires the responder exactly once.
        boolean first = registry.complete(confId, Decision.APPROVED, null);
        boolean second = registry.complete(confId, Decision.DENIED, null);
        assertThat(first).isTrue();
        assertThat(second).isFalse();

        await().atMost(3, TimeUnit.SECONDS).untilAtomic(responder.outcome, org.hamcrest.Matchers.notNullValue());
        // Give any erroneous second dispatch a chance to land, then assert exactly one.
        Thread.sleep(100);
        assertThat(responder.dispatches.get()).isEqualTo(1);
        assertThat(responder.outcome.get()).isEqualTo("selected");
        assertThat(responder.optionId.get()).isEqualTo("allow_always");
    }

    @Test
    @DisplayName("on timeout (user never answers), responds cancelled")
    void timeout_respondsCancelled() throws Exception {
        // Short timeout so the test does not wait 5s.
        bridge = new AcpPermissionBridge(registry, broadcaster, waitExecutor, 1);
        RecordingResponder responder = new RecordingResponder();

        bridge.handlePermissionRequest(SUB_SESSION, permissionReq(PERMISSION_JSON), responder);

        await().atMost(4, TimeUnit.SECONDS).untilAtomic(responder.outcome, org.hamcrest.Matchers.notNullValue());
        assertThat(responder.outcome.get()).isEqualTo("cancelled");
    }

    @Test
    @DisplayName("a request with no usable options fails closed (cancelled) without registering")
    void noOptions_failsClosed() throws Exception {
        RecordingResponder responder = new RecordingResponder();
        String json = "{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"session/request_permission\","
                + "\"params\":{\"sessionId\":\"cc-1\",\"options\":[],\"toolCall\":{\"toolCallId\":\"tc-1\"}}}";

        bridge.handlePermissionRequest(SUB_SESSION, permissionReq(json), responder);

        assertThat(responder.outcome.get()).isEqualTo("cancelled");
        assertThat(registry.size()).isZero();
    }
}
