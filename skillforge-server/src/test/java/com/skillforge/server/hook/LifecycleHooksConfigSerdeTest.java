package com.skillforge.server.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.hook.FailurePolicy;
import com.skillforge.core.engine.hook.HookEntry;
import com.skillforge.core.engine.hook.HookEvent;
import com.skillforge.core.engine.hook.HookHandler;
import com.skillforge.core.engine.hook.LifecycleHooksConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Serde round-trip tests for {@link LifecycleHooksConfig} and its polymorphic handler schema.
 */
class LifecycleHooksConfigSerdeTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
    }

    @Test
    @DisplayName("Skill handler round-trips with type=skill discriminator")
    void skillHandler_roundTrip_preservesFields() throws Exception {
        HookHandler.SkillHandler handler = new HookHandler.SkillHandler("ContentFilter");
        handler.setArgs(Map.of("maxLen", 200));
        HookEntry entry = new HookEntry();
        entry.setHandler(handler);
        entry.setTimeoutSeconds(5);
        entry.setFailurePolicy(FailurePolicy.ABORT);
        entry.setAsync(false);
        entry.setDisplayName("prompt filter");

        LifecycleHooksConfig cfg = new LifecycleHooksConfig();
        cfg.putEntries(HookEvent.USER_PROMPT_SUBMIT, List.of(entry));

        String json = mapper.writeValueAsString(cfg);
        assertThat(json).contains("\"UserPromptSubmit\"");
        assertThat(json).contains("\"type\":\"skill\"");
        assertThat(json).contains("\"ContentFilter\"");

        LifecycleHooksConfig back = mapper.readValue(json, LifecycleHooksConfig.class);
        assertThat(back.getVersion()).isEqualTo(1);
        List<HookEntry> list = back.entriesFor(HookEvent.USER_PROMPT_SUBMIT);
        assertThat(list).hasSize(1);
        HookEntry got = list.get(0);
        assertThat(got.getHandler()).isInstanceOf(HookHandler.SkillHandler.class);
        assertThat(((HookHandler.SkillHandler) got.getHandler()).getSkillName()).isEqualTo("ContentFilter");
        assertThat(got.getTimeoutSeconds()).isEqualTo(5);
        assertThat(got.getFailurePolicy()).isEqualTo(FailurePolicy.ABORT);
        assertThat(got.getHandler().getArgs()).containsEntry("maxLen", 200);
    }

    @Test
    @DisplayName("Script handler deserializes with type=script (runner not implemented in P0, but parse succeeds)")
    void scriptHandler_parses_even_though_runner_is_p1() throws Exception {
        String json = """
                {
                  "version": 1,
                  "hooks": {
                    "PostToolUse": [{
                      "handler": { "type": "script", "scriptLang": "bash", "scriptBody": "echo hi" },
                      "timeoutSeconds": 15,
                      "failurePolicy": "CONTINUE",
                      "async": false
                    }]
                  }
                }
                """;
        LifecycleHooksConfig cfg = mapper.readValue(json, LifecycleHooksConfig.class);
        HookEntry entry = cfg.entriesFor(HookEvent.POST_TOOL_USE).get(0);
        assertThat(entry.getHandler()).isInstanceOf(HookHandler.ScriptHandler.class);
        HookHandler.ScriptHandler sh = (HookHandler.ScriptHandler) entry.getHandler();
        assertThat(sh.getScriptLang()).isEqualTo("bash");
        assertThat(sh.getScriptBody()).isEqualTo("echo hi");
    }

    @Test
    @DisplayName("Method handler deserializes with type=method")
    void methodHandler_parses() throws Exception {
        String json = """
                {
                  "version": 1,
                  "hooks": {
                    "SessionEnd": [{
                      "handler": { "type": "method", "methodRef": "builtin.feishu.notify",
                                   "args": { "webhook": "https://example.com" } },
                      "timeoutSeconds": 30,
                      "async": true
                    }]
                  }
                }
                """;
        LifecycleHooksConfig cfg = mapper.readValue(json, LifecycleHooksConfig.class);
        HookEntry entry = cfg.entriesFor(HookEvent.SESSION_END).get(0);
        assertThat(entry.getHandler()).isInstanceOf(HookHandler.MethodHandler.class);
        HookHandler.MethodHandler mh = (HookHandler.MethodHandler) entry.getHandler();
        assertThat(mh.getMethodRef()).isEqualTo("builtin.feishu.notify");
        assertThat(mh.getArgs()).containsEntry("webhook", "https://example.com");
        assertThat(entry.isAsync()).isTrue();
    }

    @Test
    @DisplayName("Unknown handler type raises at Jackson layer (defensive parse pattern)")
    void unknownHandlerType_raises() {
        String json = """
                {"version":1,"hooks":{"SessionStart":[{"handler":{"type":"bogus"}}]}}
                """;
        // Jackson throws InvalidTypeIdException / JsonMappingException; the server layer
        // (AgentService.toAgentDefinition) catches and falls back to empty config.
        assertThatThrownBy(() -> mapper.readValue(json, LifecycleHooksConfig.class))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }

    @Test
    @DisplayName("Unknown event name in hooks map is dropped silently")
    void unknownEventName_isDropped() throws Exception {
        String json = """
                {"version":1,"hooks":{"NotARealEvent":[{"handler":{"type":"skill","skillName":"X"}}]}}
                """;
        // The map may contain a null HookEvent key when unknown event names appear; we still
        // want the call to succeed and the registered events to remain empty.
        LifecycleHooksConfig cfg = mapper.readValue(json, LifecycleHooksConfig.class);
        for (HookEvent ev : HookEvent.values()) {
            assertThat(cfg.entriesFor(ev)).isEmpty();
        }
    }

    @Test
    @DisplayName("HookEvent JSON uses PascalCase wire name not SCREAMING_SNAKE_CASE")
    void hookEvent_wireName_isPascalCase() throws Exception {
        String json = mapper.writeValueAsString(HookEvent.SESSION_START);
        assertThat(json).isEqualTo("\"SessionStart\"");
    }

    @Test
    @DisplayName("Unknown FailurePolicy values fall back to CONTINUE")
    void failurePolicy_unknownValue_fallsBackToContinue() throws Exception {
        String json = """
                {"handler":{"type":"skill","skillName":"X"},"failurePolicy":"BOGUS"}
                """;
        HookEntry entry = mapper.readValue(json, HookEntry.class);
        assertThat(entry.getFailurePolicy()).isEqualTo(FailurePolicy.CONTINUE);
    }
}
