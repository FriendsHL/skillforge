package com.skillforge.core.engine;

import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolResultRequestBudgeterTest {

    private static Message toolResultMsg(String toolUseId, int chars) {
        return Message.toolResult(toolUseId, "x".repeat(chars), false);
    }

    private static String toolResultText(Message m) {
        ContentBlock cb = (ContentBlock) ((List<?>) m.getContent()).get(0);
        return cb.getContent();
    }

    @Test
    @DisplayName("聚合在预算内时不裁剪，返回原 messages list (深拷贝引用)")
    void apply_underBudget_noTrim() {
        List<Message> in = new ArrayList<>();
        in.add(Message.user("hello"));
        in.add(toolResultMsg("t1", 100));
        in.add(toolResultMsg("t2", 200));

        ToolResultRequestBudgeter.Result result = ToolResultRequestBudgeter.apply(in, 100_000);

        assertThat(result.wasTrimmed()).isFalse();
        assertThat(result.trimmedCount).isZero();
        assertThat(result.totalToolResultCount).isEqualTo(2);
        assertThat(result.originalAggregateChars).isEqualTo(300);
        assertThat(result.retainedAggregateChars).isEqualTo(300);
        // Original messages untouched.
        assertThat(toolResultText(in.get(1))).hasSize(100);
    }

    @Test
    @DisplayName("聚合超预算时按 size 降序裁剪最大块直到回到预算内")
    void apply_overBudget_trimsLargestFirst() {
        List<Message> in = new ArrayList<>();
        in.add(Message.user("hello"));
        in.add(toolResultMsg("t-small", 5_000));
        in.add(toolResultMsg("t-big1", 50_000));
        in.add(toolResultMsg("t-big2", 50_000));

        // Budget 30K → must trim both 50K blocks (small one stays).
        ToolResultRequestBudgeter.Result result = ToolResultRequestBudgeter.apply(in, 30_000);

        assertThat(result.wasTrimmed()).isTrue();
        assertThat(result.trimmedCount).isEqualTo(2);
        assertThat(result.totalToolResultCount).isEqualTo(3);

        // Original messages untouched: still 50K each.
        assertThat(toolResultText(in.get(2))).hasSize(50_000);
        assertThat(toolResultText(in.get(3))).hasSize(50_000);

        // Output messages: small stays original; big1/big2 are trimmed-preview-sized.
        assertThat(toolResultText(result.messages.get(1))).hasSize(5_000);
        String big1Out = toolResultText(result.messages.get(2));
        String big2Out = toolResultText(result.messages.get(3));
        assertThat(big1Out).contains("[Tool result trimmed for request]")
                .contains("tool_use_id: t-big1")
                .contains("original_chars: 50000")
                .contains("reason: request_tool_result_budget");
        assertThat(big2Out).contains("[Tool result trimmed for request]")
                .contains("tool_use_id: t-big2");
    }

    @Test
    @DisplayName("不变量：原始 messages list 和 ContentBlock 对象不被修改")
    void apply_doesNotMutateOriginal() {
        Message big = toolResultMsg("t1", 100_000);
        List<Message> in = new ArrayList<>();
        in.add(big);
        ContentBlock origCb = (ContentBlock) ((List<?>) big.getContent()).get(0);
        String origContent = origCb.getContent();

        ToolResultRequestBudgeter.apply(in, 1_000);

        // Original block still has full content.
        assertThat(origCb.getContent()).isEqualTo(origContent);
        assertThat(origContent).hasSize(100_000);
        // Original list size unchanged.
        assertThat(in).hasSize(1);
    }

    @Test
    @DisplayName("空 / null messages 返回空 Result")
    void apply_nullOrEmpty() {
        ToolResultRequestBudgeter.Result r1 = ToolResultRequestBudgeter.apply(null, 100);
        ToolResultRequestBudgeter.Result r2 = ToolResultRequestBudgeter.apply(Collections.emptyList(), 100);

        assertThat(r1.totalToolResultCount).isZero();
        assertThat(r1.messages).isEmpty();
        assertThat(r2.totalToolResultCount).isZero();
    }

    @Test
    @DisplayName("budget=0 / 负数禁用裁剪")
    void apply_disabledBudget() {
        List<Message> in = new ArrayList<>();
        in.add(toolResultMsg("t1", 100_000));

        ToolResultRequestBudgeter.Result result = ToolResultRequestBudgeter.apply(in, 0);

        assertThat(result.wasTrimmed()).isFalse();
        assertThat(toolResultText(result.messages.get(0))).hasSize(100_000);
    }

    @Test
    @DisplayName("BUG-32 现场仿真：33 条 ~3K tool_result 聚合 ~99K 撑爆请求时按预算裁剪")
    void apply_bug32Fixture_trimsAggregateOverBudget() {
        List<Message> in = new ArrayList<>();
        in.add(Message.user("simulate bug-32 long task"));
        // 33 tool results, each 3K chars → aggregate ~99K
        for (int i = 0; i < 33; i++) {
            in.add(toolResultMsg("call-" + i, 3_000));
        }

        // Budget 80K → must trim a meaningful subset (each block can shrink 3000→2048).
        ToolResultRequestBudgeter.Result result = ToolResultRequestBudgeter.apply(in, 80_000);

        assertThat(result.wasTrimmed()).isTrue();
        assertThat(result.totalToolResultCount).isEqualTo(33);
        // Output aggregate must be reduced from original.
        assertThat(result.retainedAggregateChars).isLessThan(result.originalAggregateChars);
        // Ensure tool_use ↔ tool_result pairing structure preserved: every output
        // tool_result still references its original tool_use_id.
        for (int i = 1; i < result.messages.size(); i++) {
            ContentBlock cb = (ContentBlock) ((List<?>) result.messages.get(i).getContent()).get(0);
            assertThat(cb.getToolUseId()).startsWith("call-");
        }
    }

    @Test
    @DisplayName("已经短于裁剪目标的 tool_result 即便聚合超预算也不裁剪")
    void apply_blocksSmallerThanTrimTarget_skipped() {
        List<Message> in = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            in.add(toolResultMsg("c" + i, 1_500)); // 1.5K each, < DEFAULT_TRIMMED_CHARS (2048)
        }

        // Budget tiny but candidates are all already small → no trim.
        ToolResultRequestBudgeter.Result result = ToolResultRequestBudgeter.apply(in, 10_000);

        // Aggregate exceeds budget but trimming wouldn't help → trimmedCount = 0
        assertThat(result.wasTrimmed()).isFalse();
        assertThat(result.retainedAggregateChars).isEqualTo(result.originalAggregateChars);
    }
}
