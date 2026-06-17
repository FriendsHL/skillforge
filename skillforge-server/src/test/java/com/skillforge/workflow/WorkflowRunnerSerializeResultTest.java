package com.skillforge.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.workflow.sandbox.BudgetTracker;
import com.skillforge.workflow.sandbox.L1SandboxFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AUTOEVOLVING V1 Sprint 3 (Task D, soft-point ②): {@code serializeResult} must
 * write a {@code Map}/{@code List} return as real structured JSON (AC-8 topIssues
 * query depends on it) instead of the pre-Sprint-3 {@code String.valueOf} junk,
 * while keeping the {@code {"result": "..."}} wrapper for scalar returns.
 */
class WorkflowRunnerSerializeResultTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** serializeResult only touches the injected ObjectMapper — other deps are nulled. */
    private final WorkflowRunnerService service = new WorkflowRunnerService(
            null, null, null, null, null, null, null, null,
            objectMapper, null, null, null, null, "session-annotator", 360L);

    @Test
    @DisplayName("Map result is serialized as structured JSON (nested topIssues preserved, not stringified)")
    void mapResultStructured() throws Exception {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalSessions", 12);
        summary.put("successRate", 0.75);
        summary.put("topIssues", List.of(Map.of("id", "issue-1", "title", "loop")));
        Map<String, Object> result = Map.of("status", "approved", "summary", summary);

        String json = service.serializeResult(result);
        JsonNode root = objectMapper.readTree(json);

        // structured — not a {"result":"{...}"} string blob
        assertThat(root.has("result")).isFalse();
        assertThat(root.path("status").asText()).isEqualTo("approved");
        assertThat(root.path("summary").path("totalSessions").asInt()).isEqualTo(12);
        assertThat(root.path("summary").path("topIssues").get(0).path("id").asText()).isEqualTo("issue-1");
    }

    @Test
    @DisplayName("List result is serialized as a JSON array")
    void listResultStructured() throws Exception {
        String json = service.serializeResult(List.of("a", "b"));
        JsonNode root = objectMapper.readTree(json);
        assertThat(root.isArray()).isTrue();
        assertThat(root.get(0).asText()).isEqualTo("a");
    }

    @Test
    @DisplayName("scalar / String result keeps the legacy {\"result\":\"...\"} wrapper")
    void scalarResultWrapped() throws Exception {
        JsonNode root = objectMapper.readTree(service.serializeResult("hello"));
        assertThat(root.path("result").asText()).isEqualTo("hello");
    }

    @Test
    @DisplayName("null result wraps to {\"result\":null}")
    void nullResultWrapped() throws Exception {
        JsonNode root = objectMapper.readTree(service.serializeResult(null));
        assertThat(root.has("result")).isTrue();
        assertThat(root.path("result").isNull()).isTrue();
    }

    /**
     * W4: production never hands serializeResult a plain {@code Map.of(...)} — the
     * workflow returns a Rhino {@code NativeObject} (the JS {@code {}} literal,
     * which implements {@code java.util.Map}). This drives the REAL evaluator so
     * the test exercises the actual production type, asserting it serializes to
     * structured JSON (not the {@code "{}"} W1 swallow, and not a stringified
     * blob). Also serves as the hello-world non-regression check.
     */
    @Test
    @DisplayName("Rhino NativeObject (real JS `return {ok:true}`) serializes to structured JSON, not {} (W4)")
    void nativeObjectRoundTrip() throws Exception {
        L1SandboxFactory sandbox = new L1SandboxFactory();
        WorkflowEvaluator evaluator = new WorkflowEvaluator(sandbox);
        WorkflowContext ctx = new WorkflowContext("run-nativeobj", Map.of(), new BudgetTracker(0L));
        ctx.setObjectMapper(objectMapper);
        // No agent()/phase()/log() in the body → invoker is never invoked.
        WorkflowAgentInvoker noopInvoker = (prompt, opts, stepIndex) -> null;

        ExecutorService exec = Executors.newSingleThreadExecutor();
        Object result;
        try {
            result = evaluator.evaluate("return {ok:true}", ctx, noopInvoker, exec);
        } finally {
            exec.shutdownNow();
        }

        // The raw evaluator result is a Rhino NativeObject — the exact type
        // serializeResult sees in production.
        assertThat(result).isInstanceOf(Map.class);

        String json = service.serializeResult(result);
        JsonNode root = objectMapper.readTree(json);
        assertThat(root.has("result")).isFalse();   // structured, not the scalar wrapper
        assertThat(root.path("ok").asBoolean()).isTrue();
    }

    /**
     * W1: when the ObjectMapper cannot serialize the result, serializeResult must
     * re-throw (NOT swallow to {@code "{}"}). The caller {@code runWorkflowBody}'s
     * {@code catch(Exception)} then calls {@code markError}, so the run transitions
     * to {@code error} instead of falsely {@code completed} with an empty summary.
     */
    @Test
    @DisplayName("serialization failure re-throws IllegalStateException (no silent {} → caller marks run errored) (W1)")
    void serializationFailureThrows() {
        // A Map whose value Jackson cannot serialize: a plain Object has no
        // properties → FAIL_ON_EMPTY_BEANS (default on) throws.
        Map<String, Object> unserializable = new LinkedHashMap<>();
        unserializable.put("bad", new Object());

        assertThatThrownBy(() -> service.serializeResult(unserializable))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("serializeResult failed");
    }
}
