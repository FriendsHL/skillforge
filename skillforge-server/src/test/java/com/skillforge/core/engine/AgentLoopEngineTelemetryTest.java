package com.skillforge.core.engine;

import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.core.model.SkillDefinition;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.model.ToolUseBlock;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.core.skill.view.SessionSkillView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan r2 §10 (a)-(f) + Code Judge r1 W-BE-1 (强制随修) — verifies the engine calls the
 * telemetry recorder before EVERY return path inside {@code executeToolCall}, with the
 * correct (success, errorType) tuple.
 * <p>The original r1 missed the SkillDefinition early-return path; this matrix locks it in.
 */
class AgentLoopEngineTelemetryTest {

    /** Captures (skillName, success, errorType) tuples in order. */
    private static class CapturingRecorder implements SkillTelemetryRecorder {
        final List<Object[]> calls = new ArrayList<>();
        boolean throwOnNext = false;

        @Override
        public void record(String skillName, boolean success, String errorType) {
            calls.add(new Object[] { skillName, success, errorType });
            if (throwOnNext) {
                throwOnNext = false;
                throw new RuntimeException("simulated DB outage");
            }
        }

        Object[] last() {
            return calls.isEmpty() ? null : calls.get(calls.size() - 1);
        }
    }

    private SkillRegistry registry;
    private CapturingRecorder recorder;
    private AgentLoopEngine engine;

    @BeforeEach
    void setUp() {
        registry = new SkillRegistry();
        recorder = new CapturingRecorder();
        engine = new AgentLoopEngine(
                new LlmProviderFactory(),
                "unused",
                registry,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
        engine.setSkillTelemetryRecorder(recorder);
    }

    private LoopContext newCtx() {
        LoopContext c = new LoopContext();
        c.setSessionId("s1");
        c.setMessages(new ArrayList<>());
        return c;
    }

    private ToolUseBlock toolUse(String name) {
        return new ToolUseBlock("tu-" + name, name, Map.of());
    }

    private static class StubTool implements Tool {
        private final String name;
        private final SkillResult result;
        private final boolean throwIae;
        private final boolean throwGeneric;

        static StubTool ok(String name) {
            return new StubTool(name, SkillResult.success("ok"), false, false);
        }

        static StubTool err(String name) {
            return new StubTool(name, SkillResult.error("boom"), false, false);
        }

        static StubTool throwsIae(String name) {
            return new StubTool(name, null, true, false);
        }

        static StubTool throwsGeneric(String name) {
            return new StubTool(name, null, false, true);
        }

        private StubTool(String name, SkillResult result, boolean iae, boolean generic) {
            this.name = name;
            this.result = result;
            this.throwIae = iae;
            this.throwGeneric = generic;
        }

        @Override public String getName() { return name; }
        @Override public String getDescription() { return ""; }
        @Override public ToolSchema getToolSchema() {
            // Simple schema with one optional param (no required, so no missing-required path)
            Map<String, Object> in = new LinkedHashMap<>();
            in.put("type", "object");
            return new ToolSchema(name, "", in);
        }
        @Override public SkillResult execute(Map<String, Object> input, SkillContext ctx) {
            if (throwIae) throw new IllegalArgumentException("bad arg");
            if (throwGeneric) throw new RuntimeException("kaboom");
            return result;
        }
    }

    @Test
    @DisplayName("(a) Tool success → recordTelemetry(skillName, true, null)")
    void toolSuccess_recordsSuccessTuple() {
        registry.registerTool(StubTool.ok("Bash"));

        engine.executeToolCall(toolUse("Bash"), newCtx(), new ArrayList<>(), null);

        assertThat(recorder.calls).hasSize(1);
        Object[] last = recorder.last();
        assertThat(last[0]).isEqualTo("Bash");
        assertThat(last[1]).isEqualTo(true);
        assertThat(last[2]).isNull();
    }

    @Test
    @DisplayName("(b) Tool failure (EXECUTION) → recordTelemetry(skillName, false, EXECUTION)")
    void toolFailure_recordsExecutionTuple() {
        registry.registerTool(StubTool.err("Bash"));

        engine.executeToolCall(toolUse("Bash"), newCtx(), new ArrayList<>(), null);

        Object[] last = recorder.last();
        assertThat(last[1]).isEqualTo(false);
        assertThat(last[2]).isEqualTo(SkillResult.ErrorType.EXECUTION.name());
    }

    @Test
    @DisplayName("(c) SkillDefinition early-return path also calls recordTelemetry — r1 漏检的关键 case")
    void skillDefinitionEarlyReturn_recordsSuccess() {
        // Build a SkillDefinition (zip-package skill) and put it in a view (run() injects view;
        // for the unit test we prime the LoopContext.skillView directly).
        SkillDefinition def = new SkillDefinition();
        def.setName("user-skill-foo");
        def.setPromptContent("# prompt body");
        Map<String, SkillDefinition> allowed = new LinkedHashMap<>();
        allowed.put("user-skill-foo", def);
        SessionSkillView view = new SessionSkillView(allowed,
                Collections.emptySet(), java.util.Set.of("user-skill-foo"));

        LoopContext ctx = newCtx();
        ctx.setSkillView(view);

        engine.executeToolCall(toolUse("user-skill-foo"), ctx, new ArrayList<>(), null);

        assertThat(recorder.calls)
                .as("SkillDefinition early-return must trigger telemetry — r1 missed this in code")
                .hasSize(1);
        Object[] last = recorder.last();
        assertThat(last[0]).isEqualTo("user-skill-foo");
        assertThat(last[1]).isEqualTo(true);
        assertThat(last[2]).isNull();
    }

    @Test
    @DisplayName("(d) NOT_ALLOWED short-circuit → recordTelemetry(skillName, false, NOT_ALLOWED)")
    void notAllowed_recordsNotAllowedTuple() {
        // Skill name that's neither a built-in Tool nor an authorised view entry → NOT_ALLOWED.
        LoopContext ctx = newCtx();
        ctx.setSkillView(SessionSkillView.EMPTY);

        engine.executeToolCall(toolUse("denied-skill"), ctx, new ArrayList<>(), null);

        Object[] last = recorder.last();
        assertThat(last[0]).isEqualTo("denied-skill");
        assertThat(last[1]).isEqualTo(false);
        assertThat(last[2]).isEqualTo(SkillResult.ErrorType.NOT_ALLOWED.name());
    }

    @Test
    @DisplayName("(e) Top-level exception (RuntimeException) → recordTelemetry(skillName, false, EXECUTION)")
    void topLevelCatch_recordsExecutionTuple() {
        registry.registerTool(StubTool.throwsGeneric("Bash"));

        engine.executeToolCall(toolUse("Bash"), newCtx(), new ArrayList<>(), null);

        Object[] last = recorder.last();
        assertThat(last[1]).isEqualTo(false);
        assertThat(last[2]).isEqualTo(SkillResult.ErrorType.EXECUTION.name());
    }

    @Test
    @DisplayName("(e2) IllegalArgumentException → VALIDATION (so detectWaste does not amplify)")
    void iaeCatch_recordsValidationTuple() {
        registry.registerTool(StubTool.throwsIae("Bash"));

        engine.executeToolCall(toolUse("Bash"), newCtx(), new ArrayList<>(), null);

        Object[] last = recorder.last();
        assertThat(last[1]).isEqualTo(false);
        assertThat(last[2]).isEqualTo(SkillResult.ErrorType.VALIDATION.name());
    }

    @Test
    @DisplayName("(f) Recorder throws → tool call still completes successfully (telemetry fail-safe)")
    void recorderThrows_doesNotBreakToolCall() {
        registry.registerTool(StubTool.ok("Bash"));
        recorder.throwOnNext = true;

        Message result = engine.executeToolCall(toolUse("Bash"), newCtx(), new ArrayList<>(), null);

        // Tool result must still be the success path even though recorder threw.
        assertThat(result).isNotNull();
        assertThat(result.getContent()).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Object> blocks = (List<Object>) result.getContent();
        ContentBlock cb = (ContentBlock) blocks.get(0);
        assertThat(Boolean.TRUE.equals(cb.getIsError())).isFalse();
    }

    @Test
    @DisplayName("Unknown skill (no Tool, no view entry) → recordTelemetry(skillName, false, EXECUTION)")
    void unknownSkill_recordsExecution() {
        // No view set → NOT a NOT_ALLOWED path; falls through to "Unknown skill" branch.
        LoopContext ctx = newCtx();
        // Skip view entirely — engine sees view==null + tool not in registry → unknown branch.

        engine.executeToolCall(toolUse("ghost-skill"), ctx, new ArrayList<>(), null);

        Object[] last = recorder.last();
        assertThat(last[0]).isEqualTo("ghost-skill");
        assertThat(last[1]).isEqualTo(false);
        assertThat(last[2]).isEqualTo(SkillResult.ErrorType.EXECUTION.name());
    }
}
