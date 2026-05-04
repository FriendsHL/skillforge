package com.skillforge.core.engine;

import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.core.skill.SkillResult;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link AgentLoopEngine#detectWaste(List)}: ensures the consecutive-error
 * counter only treats EXECUTION-class errors as waste signals, while VALIDATION-class
 * errors (LLM produced bad arguments — should be retried structurally, not amplified
 * via compaction) do NOT trigger the B1 light compact path.
 *
 * <p>Regression cover for SkillForge session 9347f84c, where 3 consecutive
 * "file_path is required" tool_results triggered an unnecessary compaction that
 * collapsed the LLM's pending Write content.
 */
class AgentLoopEngineWasteDetectTest {

    private AgentLoopEngine newEngine() {
        return new AgentLoopEngine(
                new LlmProviderFactory(),
                "unused",
                new SkillRegistry(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList()
        );
    }

    private Message toolResult(String id, String content, boolean isError, String errorType) {
        Message msg = new Message();
        msg.setRole(Message.Role.USER);
        msg.setContent(Collections.singletonList(
                ContentBlock.toolResult(id, content, isError, errorType)));
        return msg;
    }

    private Message toolUse(String id, String name) {
        return toolUse(id, name, Collections.singletonMap("call", id));
    }

    private Message toolUse(String id, String name, java.util.Map<String, Object> input) {
        Message msg = new Message();
        msg.setRole(Message.Role.ASSISTANT);
        msg.setContent(Collections.singletonList(
                ContentBlock.toolUse(id, name, input)));
        return msg;
    }

    @Test
    @DisplayName("3 consecutive EXECUTION errors trigger waste signal")
    void threeConsecutiveExecutionErrors_triggersWaste() {
        AgentLoopEngine engine = newEngine();
        List<Message> messages = new ArrayList<>();
        messages.add(Message.user("hi"));
        for (int i = 0; i < 3; i++) {
            messages.add(toolUse("t" + i, "Bash"));
            messages.add(toolResult("t" + i, "permission denied", true,
                    SkillResult.ErrorType.EXECUTION.name()));
        }

        assertThat(engine.detectWaste(messages))
                .as("3 EXECUTION errors should trigger waste detection")
                .isTrue();
    }

    @Test
    @DisplayName("9347f84c regression: 3 identical empty-input Write returning VALIDATION must NOT trigger waste")
    void threeConsecutiveValidationErrors_doesNotTriggerWaste() {
        AgentLoopEngine engine = newEngine();
        List<Message> messages = new ArrayList<>();
        messages.add(Message.user("rewrite FormMode.tsx"));
        // Real-world shape: LLM emits identical empty-input tool_use after compaction
        // — must skip both rule 2 (consecutive errors) AND rule 3 (consecutive identical
        // tool_use) since both would otherwise fire.
        for (int i = 0; i < 3; i++) {
            messages.add(toolUse("t" + i, "Write", Collections.emptyMap()));
            messages.add(toolResult("t" + i, "file_path is required", true,
                    SkillResult.ErrorType.VALIDATION.name()));
        }

        assertThat(engine.detectWaste(messages))
                .as("3 VALIDATION errors must NOT trigger waste — would create"
                        + " compaction → LLM amnesia → more validation errors feedback loop")
                .isFalse();
    }

    @Test
    @DisplayName("legacy null errorType + isError=true behaves as EXECUTION (backwards-compatible)")
    void nullErrorType_isErrorTrue_countsAsExecution() {
        AgentLoopEngine engine = newEngine();
        List<Message> messages = new ArrayList<>();
        messages.add(Message.user("hi"));
        for (int i = 0; i < 3; i++) {
            messages.add(toolUse("t" + i, "SomeSkill"));
            // legacy path: isError=true, errorType=null (pre-fix tool_result shape)
            messages.add(toolResult("t" + i, "boom", true, null));
        }

        assertThat(engine.detectWaste(messages))
                .as("Backwards compatibility: legacy errors without errorType still trigger waste")
                .isTrue();
    }

    @Test
    @DisplayName("VALIDATION errors interleaved with EXECUTION reset the counter correctly")
    void validationDoesNotIncrement_executionCounterResetsBetween() {
        AgentLoopEngine engine = newEngine();
        List<Message> messages = new ArrayList<>();
        messages.add(Message.user("hi"));

        // execution × 2
        messages.add(toolUse("t1", "Bash"));
        messages.add(toolResult("t1", "boom", true, SkillResult.ErrorType.EXECUTION.name()));
        messages.add(toolUse("t2", "Bash"));
        messages.add(toolResult("t2", "boom", true, SkillResult.ErrorType.EXECUTION.name()));

        // validation × 1 (must not increment, but also must not reset since
        // it's not a successful tool_result either)
        messages.add(toolUse("t3", "Write"));
        messages.add(toolResult("t3", "file_path is required", true,
                SkillResult.ErrorType.VALIDATION.name()));

        // execution × 1 — total execution count is 3 → should trigger
        messages.add(toolUse("t4", "Bash"));
        messages.add(toolResult("t4", "boom", true, SkillResult.ErrorType.EXECUTION.name()));

        assertThat(engine.detectWaste(messages))
                .as("VALIDATION between EXECUTION errors must not reset the execution counter")
                .isTrue();
    }

    @Test
    @DisplayName("successful tool_result resets the consecutive error counter")
    void successfulToolResult_resetsCounter() {
        AgentLoopEngine engine = newEngine();
        List<Message> messages = new ArrayList<>();
        messages.add(Message.user("hi"));

        // execution × 2
        messages.add(toolUse("t1", "Bash"));
        messages.add(toolResult("t1", "boom", true, SkillResult.ErrorType.EXECUTION.name()));
        messages.add(toolUse("t2", "Bash"));
        messages.add(toolResult("t2", "boom", true, SkillResult.ErrorType.EXECUTION.name()));

        // success — must reset
        messages.add(toolUse("t3", "Bash"));
        messages.add(toolResult("t3", "ok", false, null));

        // execution × 2 — should not trigger (counter reset, only 2 since reset)
        messages.add(toolUse("t4", "Bash"));
        messages.add(toolResult("t4", "boom", true, SkillResult.ErrorType.EXECUTION.name()));
        messages.add(toolUse("t5", "Bash"));
        messages.add(toolResult("t5", "boom", true, SkillResult.ErrorType.EXECUTION.name()));

        assertThat(engine.detectWaste(messages))
                .as("Successful tool_result resets execution error counter")
                .isFalse();
    }
}
