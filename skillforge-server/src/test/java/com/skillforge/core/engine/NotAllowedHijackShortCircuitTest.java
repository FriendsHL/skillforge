package com.skillforge.core.engine;

import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.core.model.ToolUseBlock;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.view.SessionSkillView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan r2 §5 (B-4) + Code Judge r1 W-BE-1 — verify NOT_ALLOWED short-circuit / anti-hijack.
 * <ul>
 *   <li>1st NOT_ALLOWED → returns hint, does NOT abort</li>
 *   <li>2nd NOT_ALLOWED on same skill → setAbortToolUse(true), hint contains "ABORTED"</li>
 *   <li>{@link AgentLoopEngine#detectWaste(java.util.List)} treats NOT_ALLOWED like
 *       EXECUTION (not VALIDATION) — repeated NOT_ALLOWED contributes to consecutive-error
 *       count.</li>
 * </ul>
 */
class NotAllowedHijackShortCircuitTest {

    private AgentLoopEngine engine;
    private LoopContext ctx;

    @BeforeEach
    void setUp() {
        engine = new AgentLoopEngine(
                new LlmProviderFactory(),
                "unused",
                new SkillRegistry(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
        ctx = new LoopContext();
        ctx.setSessionId("s");
        ctx.setMessages(new ArrayList<>());
        ctx.setSkillView(SessionSkillView.EMPTY);
    }

    private ToolUseBlock toolUse(String name, String id) {
        return new ToolUseBlock(id, name, Map.of());
    }

    private static String firstBlockText(Message msg) {
        @SuppressWarnings("unchecked")
        List<Object> blocks = (List<Object>) msg.getContent();
        ContentBlock cb = (ContentBlock) blocks.get(0);
        return String.valueOf(cb.getContent());
    }

    private static String firstBlockErrorType(Message msg) {
        @SuppressWarnings("unchecked")
        List<Object> blocks = (List<Object>) msg.getContent();
        ContentBlock cb = (ContentBlock) blocks.get(0);
        return cb.getErrorType();
    }

    @Test
    @DisplayName("1st NOT_ALLOWED: hint message, abortToolUse stays false")
    void firstHit_hintNoAbort() {
        Message result = engine.executeToolCall(
                toolUse("denied", "u1"), ctx, new ArrayList<>(), null);

        assertThat(firstBlockText(result)).contains("[NOT ALLOWED]");
        assertThat(firstBlockText(result)).doesNotContain("ABORTED");
        assertThat(firstBlockErrorType(result)).isEqualTo(SkillResult.ErrorType.NOT_ALLOWED.name());
        assertThat(ctx.isAbortToolUse()).isFalse();
        assertThat(ctx.getNotAllowedCount("denied")).isEqualTo(1);
    }

    @Test
    @DisplayName("2nd NOT_ALLOWED on same skill → ABORT hint + abortToolUse=true")
    void secondHit_abortsToolUseLoop() {
        engine.executeToolCall(toolUse("denied", "u1"), ctx, new ArrayList<>(), null);
        Message second = engine.executeToolCall(
                toolUse("denied", "u2"), ctx, new ArrayList<>(), null);

        assertThat(firstBlockText(second)).contains("[ABORTED]");
        assertThat(firstBlockText(second)).contains("Repeated calls (n=2)");
        assertThat(firstBlockErrorType(second)).isEqualTo(SkillResult.ErrorType.NOT_ALLOWED.name());
        assertThat(ctx.isAbortToolUse()).isTrue();
        assertThat(ctx.getNotAllowedCount("denied")).isEqualTo(2);
    }

    @Test
    @DisplayName("detectWaste: 3 consecutive NOT_ALLOWED tool_results count as EXECUTION-equivalent waste")
    void detectWaste_treatsNotAllowedLikeExecution() {
        List<Message> messages = new ArrayList<>();
        // Three consecutive tool_result content blocks, each isError=true + NOT_ALLOWED.
        // detectWaste sees them as non-VALIDATION errors and trips the consecutive-error rule.
        for (int i = 0; i < 3; i++) {
            Message msg = Message.toolResult("u" + i, "[NOT ALLOWED] denied", true,
                    SkillResult.ErrorType.NOT_ALLOWED.name());
            messages.add(msg);
        }

        boolean waste = engine.detectWaste(messages);

        assertThat(waste)
                .as("detectWaste must treat NOT_ALLOWED as EXECUTION-equivalent — 3 consecutive trip the rule")
                .isTrue();
    }

    @Test
    @DisplayName("Different skills: each tracked independently — neither alone triggers abort")
    void differentSkills_independentCounts() {
        engine.executeToolCall(toolUse("skill-a", "u1"), ctx, new ArrayList<>(), null);
        engine.executeToolCall(toolUse("skill-b", "u2"), ctx, new ArrayList<>(), null);

        assertThat(ctx.getNotAllowedCount("skill-a")).isEqualTo(1);
        assertThat(ctx.getNotAllowedCount("skill-b")).isEqualTo(1);
        assertThat(ctx.isAbortToolUse()).isFalse();
    }
}
