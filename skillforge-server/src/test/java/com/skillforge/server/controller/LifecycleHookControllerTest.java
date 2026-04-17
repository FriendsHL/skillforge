package com.skillforge.server.controller;

import com.skillforge.core.engine.hook.BuiltInMethod;
import com.skillforge.core.engine.hook.HookExecutionContext;
import com.skillforge.core.engine.hook.HookRunResult;
import com.skillforge.server.dto.HookHistoryDto;
import com.skillforge.server.hook.BuiltInMethodRegistry;
import com.skillforge.server.service.LifecycleHookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LifecycleHookControllerTest {

    @Mock
    private BuiltInMethodRegistry methodRegistry;

    @Mock
    private LifecycleHookService lifecycleHookService;

    private LifecycleHookController controller;

    @BeforeEach
    void setUp() {
        controller = new LifecycleHookController(methodRegistry, lifecycleHookService);
    }

    @Test
    @DisplayName("GET /methods returns registered methods")
    void listMethods_returnsRegisteredMethods() {
        BuiltInMethod stub = new BuiltInMethod() {
            @Override public String ref() { return "builtin.test"; }
            @Override public String displayName() { return "Test"; }
            @Override public String description() { return "A test method"; }
            @Override public Map<String, String> argsSchema() { return Map.of("arg1", "String"); }
            @Override public HookRunResult execute(Map<String, Object> args, HookExecutionContext ctx) {
                return HookRunResult.ok("ok", 0);
            }
        };
        when(methodRegistry.listAll()).thenReturn(List.of(stub));

        ResponseEntity<List<Map<String, Object>>> resp = controller.listMethods();

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).hasSize(1);
        assertThat(resp.getBody().get(0).get("ref")).isEqualTo("builtin.test");
    }

    @Test
    @DisplayName("dry-run returns 404 when agent not found")
    void dryRun_agentNotFound_returns404() {
        when(lifecycleHookService.dryRun(any()))
                .thenThrow(new IllegalArgumentException("agent not found: 999"));

        ResponseEntity<?> resp = controller.dryRunHook(999L,
                Map.of("event", "SessionStart", "entryIndex", 0));

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("dry-run returns 400 when agent has no hooks config")
    void dryRun_noHooksConfig_returns400() {
        when(lifecycleHookService.dryRun(any()))
                .thenThrow(new IllegalArgumentException("agent has no lifecycle hooks config"));

        ResponseEntity<?> resp = controller.dryRunHook(1L,
                Map.of("event", "SessionStart", "entryIndex", 0));

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("dry-run returns 200 with result on successful execution")
    void dryRun_success_returns200() {
        when(lifecycleHookService.dryRun(any()))
                .thenReturn(HookRunResult.ok("dry-run-ok:Memory", 10));

        ResponseEntity<?> resp = controller.dryRunHook(1L,
                Map.of("event", "SessionStart", "entryIndex", 0));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).containsKey("success");
        assertThat(body.get("success")).isEqualTo(true);
        assertThat(body.get("output")).asString().contains("dry-run-ok:Memory");
    }

    @Test
    @DisplayName("dry-run returns 400 when event field is missing")
    void dryRun_missingEvent_returns400() {
        when(lifecycleHookService.dryRun(any()))
                .thenThrow(new IllegalArgumentException("event is required"));

        ResponseEntity<?> resp = controller.dryRunHook(1L, Map.of("entryIndex", 0));

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("hook-history returns 200 with DTOs")
    void hookHistory_returnsDto() {
        HookHistoryDto dto = new HookHistoryDto(
                "span-1", "session-1", "LIFECYCLE_HOOK", "SessionStart",
                "skill:Memory|idx=0", "ok", Instant.now(), Instant.now(),
                42, true, null);
        when(lifecycleHookService.getHookHistory(1L, 50)).thenReturn(List.of(dto));

        ResponseEntity<?> resp = controller.hookHistory(1L, 50);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<HookHistoryDto> body = (List<HookHistoryDto>) resp.getBody();
        assertThat(body).hasSize(1);
        assertThat(body.get(0).id()).isEqualTo("span-1");
    }

    @Test
    @DisplayName("hook-history returns 404 when agent not found")
    void hookHistory_agentNotFound_returns404() {
        when(lifecycleHookService.getHookHistory(999L, 50))
                .thenThrow(new IllegalArgumentException("agent not found: 999"));

        ResponseEntity<?> resp = controller.hookHistory(999L, 50);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }
}
