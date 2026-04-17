package com.skillforge.server.hook;

import com.skillforge.core.engine.hook.BuiltInMethod;
import com.skillforge.core.engine.hook.HookEvent;
import com.skillforge.core.engine.hook.HookExecutionContext;
import com.skillforge.core.engine.hook.HookHandler;
import com.skillforge.core.engine.hook.HookRunResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MethodHandlerRunner}.
 */
@ExtendWith(MockitoExtension.class)
class MethodHandlerRunnerTest {

    @Mock
    private BuiltInMethodRegistry registry;

    @Mock
    private BuiltInMethod builtInMethod;

    private MethodHandlerRunner runner;

    private static final HookExecutionContext CTX = new HookExecutionContext(
            "sess-1", 42L, HookEvent.SESSION_START,
            Map.of("_hook_origin", "lifecycle:SessionStart"));

    @BeforeEach
    void setUp() {
        runner = new MethodHandlerRunner(registry);
    }

    @Test
    @DisplayName("handlerType returns MethodHandler.class")
    void handlerType_returnsMethodHandler() {
        assertThat(runner.handlerType()).isEqualTo(HookHandler.MethodHandler.class);
    }

    @Test
    @DisplayName("run succeeds when method found and executes successfully")
    void run_methodFound_returnsSuccess() {
        HookHandler.MethodHandler handler = new HookHandler.MethodHandler();
        handler.setMethodRef("builtin.test");
        when(registry.get("builtin.test")).thenReturn(Optional.of(builtInMethod));
        when(builtInMethod.execute(anyMap(), any())).thenReturn(HookRunResult.ok("done", 5));

        HookRunResult result = runner.run(handler, Map.of("key", "val"), CTX);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("done");
    }

    @Test
    @DisplayName("run returns method_not_found when ref not in registry")
    void run_methodNotFound_returnsFailure() {
        HookHandler.MethodHandler handler = new HookHandler.MethodHandler();
        handler.setMethodRef("builtin.missing");
        when(registry.get("builtin.missing")).thenReturn(Optional.empty());

        HookRunResult result = runner.run(handler, Map.of(), CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("method_not_found:builtin.missing");
    }

    @Test
    @DisplayName("run returns method_ref_missing when ref is blank")
    void run_blankRef_returnsFailure() {
        HookHandler.MethodHandler handler = new HookHandler.MethodHandler();
        handler.setMethodRef("  ");

        HookRunResult result = runner.run(handler, Map.of(), CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("method_ref_missing");
    }

    @Test
    @DisplayName("run returns method_ref_missing when ref is null")
    void run_nullRef_returnsFailure() {
        HookHandler.MethodHandler handler = new HookHandler.MethodHandler();
        // methodRef is null by default

        HookRunResult result = runner.run(handler, Map.of(), CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("method_ref_missing");
    }

    @Test
    @DisplayName("run merges handler args with input — runtime wins on collision")
    void run_mergesArgs_runtimeWins() {
        HookHandler.MethodHandler handler = new HookHandler.MethodHandler();
        handler.setMethodRef("builtin.test");
        handler.setArgs(Map.of("key", "default", "extra", "config-value"));

        when(registry.get("builtin.test")).thenReturn(Optional.of(builtInMethod));
        when(builtInMethod.execute(anyMap(), any())).thenReturn(HookRunResult.ok("ok", 1));

        Map<String, Object> input = Map.of("key", "runtime");
        runner.run(handler, input, CTX);

        // Capture the merged args passed to execute
        var captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(builtInMethod).execute(captor.capture(), any());
        @SuppressWarnings("unchecked")
        Map<String, Object> merged = captor.getValue();

        assertThat(merged.get("key")).isEqualTo("runtime");
        assertThat(merged.get("extra")).isEqualTo("config-value");
    }

    @Test
    @DisplayName("run catches exception from method.execute and returns failure")
    void run_methodThrows_returnsFailure() {
        HookHandler.MethodHandler handler = new HookHandler.MethodHandler();
        handler.setMethodRef("builtin.test");

        when(registry.get("builtin.test")).thenReturn(Optional.of(builtInMethod));
        when(builtInMethod.execute(anyMap(), any()))
                .thenThrow(new RuntimeException("boom"));

        HookRunResult result = runner.run(handler, Map.of(), CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("method_execution_error");
    }
}
