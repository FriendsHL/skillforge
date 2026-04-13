package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.ToolCallRecord;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.server.dto.SessionReplayDto;
import com.skillforge.server.dto.SessionReplayDto.Iteration;
import com.skillforge.server.dto.SessionReplayDto.ReplayToolCall;
import com.skillforge.server.dto.SessionReplayDto.Turn;
import com.skillforge.server.entity.ModelUsageEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.ModelUsageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ReplayService 单元测试：验证消息列表 → turn/iteration/tool call 结构化解析逻辑。
 */
class ReplayServiceTest {

    private SessionService sessionService;
    private ModelUsageRepository modelUsageRepository;
    private ObjectMapper objectMapper;
    private ReplayService replayService;

    private static final String SESSION_ID = "test-session-001";

    @BeforeEach
    void setUp() {
        sessionService = mock(SessionService.class);
        modelUsageRepository = mock(ModelUsageRepository.class);
        objectMapper = new ObjectMapper();
        replayService = new ReplayService(sessionService, modelUsageRepository, objectMapper);

        SessionEntity session = new SessionEntity();
        session.setId(SESSION_ID);
        session.setStatus("active");
        session.setRuntimeStatus("idle");
        when(sessionService.getSession(SESSION_ID)).thenReturn(session);
    }

    // ── 辅助方法 ──────────────────────────────────────────

    /** 创建纯文本 user 消息 */
    private Message userMsg(String text) {
        return Message.user(text);
    }

    /** 创建纯文本 assistant 消息 */
    private Message assistantMsg(String text) {
        return Message.assistant(text);
    }

    /** 创建带 tool_use 的 assistant 消息 */
    private Message assistantWithTool(String text, String toolId, String toolName, Map<String, Object> input) {
        Message msg = new Message();
        msg.setRole(Message.Role.ASSISTANT);
        List<ContentBlock> blocks = new ArrayList<>();
        if (text != null && !text.isEmpty()) {
            blocks.add(ContentBlock.text(text));
        }
        blocks.add(ContentBlock.toolUse(toolId, toolName, input));
        msg.setContent(blocks);
        return msg;
    }

    /** 创建带多个 tool_use 的 assistant 消息 */
    private Message assistantWithTools(String text, String[][] tools) {
        Message msg = new Message();
        msg.setRole(Message.Role.ASSISTANT);
        List<ContentBlock> blocks = new ArrayList<>();
        if (text != null && !text.isEmpty()) {
            blocks.add(ContentBlock.text(text));
        }
        for (String[] t : tools) {
            blocks.add(ContentBlock.toolUse(t[0], t[1], Map.of("arg", t.length > 2 ? t[2] : "")));
        }
        msg.setContent(blocks);
        return msg;
    }

    /** 创建 tool_result user 消息 */
    private Message toolResultMsg(String toolUseId, String content, boolean isError) {
        return Message.toolResult(toolUseId, content, isError);
    }

    /** 创建带多个 tool_result 的 user 消息 */
    private Message toolResultsMsg(String[][] results) {
        Message msg = new Message();
        msg.setRole(Message.Role.USER);
        List<ContentBlock> blocks = new ArrayList<>();
        for (String[] r : results) {
            blocks.add(ContentBlock.toolResult(r[0], r[1], "true".equals(r.length > 2 ? r[2] : "false")));
        }
        msg.setContent(blocks);
        return msg;
    }

    private ModelUsageEntity makeUsage(int inputTok, int outputTok, String modelId, List<ToolCallRecord> records) {
        ModelUsageEntity usage = new ModelUsageEntity();
        usage.setInputTokens(inputTok);
        usage.setOutputTokens(outputTok);
        usage.setModelId(modelId);
        usage.setSessionId(SESSION_ID);
        try {
            usage.setToolCalls(objectMapper.writeValueAsString(records));
        } catch (Exception e) {
            usage.setToolCalls("[]");
        }
        return usage;
    }

    private ToolCallRecord makeRecord(String name, long durationMs, long timestamp, boolean success) {
        return new ToolCallRecord(name, Map.of(), "output", success, durationMs, timestamp);
    }

    private SessionReplayDto buildReplay(List<Message> messages, List<ModelUsageEntity> usages) {
        when(sessionService.getSessionMessages(SESSION_ID)).thenReturn(messages);
        when(modelUsageRepository.findBySessionIdOrderByCreatedAtAsc(SESSION_ID)).thenReturn(usages);
        return replayService.buildReplay(SESSION_ID);
    }

    // ── 测试用例 ──────────────────────────────────────────

    @Test
    void pureTextConversation_noToolCalls() {
        // user → assistant (纯文本对话，无工具调用)
        List<Message> messages = List.of(
                userMsg("你好"),
                assistantMsg("你好！有什么可以帮你的？")
        );
        ModelUsageEntity usage = makeUsage(100, 50, "gpt-4", Collections.emptyList());

        SessionReplayDto replay = buildReplay(messages, List.of(usage));

        assertThat(replay.getTurns()).hasSize(1);
        Turn turn = replay.getTurns().get(0);
        assertThat(turn.getUserMessage()).isEqualTo("你好");
        assertThat(turn.getFinalResponse()).isEqualTo("你好！有什么可以帮你的？");
        assertThat(turn.getIterationCount()).isZero();
        assertThat(turn.getIterations()).isEmpty();
        assertThat(turn.getInputTokens()).isEqualTo(100);
        assertThat(turn.getOutputTokens()).isEqualTo(50);
    }

    @Test
    void singleToolCall_oneIteration() {
        // user → assistant(tool_use) → user(tool_result) → assistant(text)
        List<Message> messages = List.of(
                userMsg("现在几点了？"),
                assistantWithTool("", "tool-1", "Bash", Map.of("command", "date")),
                toolResultMsg("tool-1", "2026-04-13 10:00:00", false),
                assistantMsg("现在是 10 点整。")
        );
        ToolCallRecord record = makeRecord("Bash", 50, 1000000L, true);
        ModelUsageEntity usage = makeUsage(200, 80, "claude-3", List.of(record));

        SessionReplayDto replay = buildReplay(messages, List.of(usage));

        assertThat(replay.getTurns()).hasSize(1);
        Turn turn = replay.getTurns().get(0);
        assertThat(turn.getIterationCount()).isEqualTo(1);
        assertThat(turn.getFinalResponse()).isEqualTo("现在是 10 点整。");

        Iteration iter = turn.getIterations().get(0);
        assertThat(iter.getIterationIndex()).isZero();
        assertThat(iter.getToolCalls()).hasSize(1);

        ReplayToolCall tc = iter.getToolCalls().get(0);
        assertThat(tc.getName()).isEqualTo("Bash");
        assertThat(tc.getId()).isEqualTo("tool-1");
        assertThat(tc.getOutput()).isEqualTo("2026-04-13 10:00:00");
        assertThat(tc.isSuccess()).isTrue();
        assertThat(tc.getDurationMs()).isEqualTo(50);
        assertThat(tc.getTimestamp()).isEqualTo(1000000L);
    }

    @Test
    void multipleIterations_withinOneTurn() {
        // user → assistant(tool_use) → user(tool_result) → assistant(tool_use) → user(tool_result) → assistant(text)
        List<Message> messages = List.of(
                userMsg("查一下天气"),
                assistantWithTool("", "tool-1", "SearchWeather", Map.of("city", "Beijing")),
                toolResultMsg("tool-1", "晴 25°C", false),
                assistantWithTool("让我再查一下明天的", "tool-2", "SearchWeather", Map.of("city", "Beijing", "date", "tomorrow")),
                toolResultMsg("tool-2", "多云 22°C", false),
                assistantMsg("今天北京晴 25°C，明天多云 22°C。")
        );
        List<ToolCallRecord> records = List.of(
                makeRecord("SearchWeather", 120, 1000L, true),
                makeRecord("SearchWeather", 150, 2000L, true)
        );
        ModelUsageEntity usage = makeUsage(500, 200, "claude-3", records);

        SessionReplayDto replay = buildReplay(messages, List.of(usage));

        Turn turn = replay.getTurns().get(0);
        assertThat(turn.getIterationCount()).isEqualTo(2);

        Iteration iter0 = turn.getIterations().get(0);
        assertThat(iter0.getToolCalls()).hasSize(1);
        assertThat(iter0.getToolCalls().get(0).getName()).isEqualTo("SearchWeather");
        assertThat(iter0.getToolCalls().get(0).getDurationMs()).isEqualTo(120);

        Iteration iter1 = turn.getIterations().get(1);
        assertThat(iter1.getAssistantText()).isEqualTo("让我再查一下明天的");
        assertThat(iter1.getToolCalls()).hasSize(1);
        assertThat(iter1.getToolCalls().get(0).getDurationMs()).isEqualTo(150);
    }

    @Test
    void multipleToolsInOneIteration() {
        // assistant 一次返回多个 tool_use，对应一个 tool_result 消息里的多个块
        List<Message> messages = List.of(
                userMsg("查北京和上海的天气"),
                assistantWithTools("", new String[][]{
                        {"tool-1", "SearchWeather", "Beijing"},
                        {"tool-2", "SearchWeather", "Shanghai"}
                }),
                toolResultsMsg(new String[][]{
                        {"tool-1", "晴 25°C"},
                        {"tool-2", "阴 20°C"}
                }),
                assistantMsg("北京晴 25°C，上海阴 20°C。")
        );
        List<ToolCallRecord> records = List.of(
                makeRecord("SearchWeather", 100, 1000L, true),
                makeRecord("SearchWeather", 110, 1100L, true)
        );
        ModelUsageEntity usage = makeUsage(300, 100, "claude-3", records);

        SessionReplayDto replay = buildReplay(messages, List.of(usage));

        Turn turn = replay.getTurns().get(0);
        assertThat(turn.getIterationCount()).isEqualTo(1);

        Iteration iter = turn.getIterations().get(0);
        assertThat(iter.getToolCalls()).hasSize(2);
        assertThat(iter.getToolCalls().get(0).getDurationMs()).isEqualTo(100);
        assertThat(iter.getToolCalls().get(1).getDurationMs()).isEqualTo(110);
        assertThat(iter.getToolCalls().get(0).getOutput()).isEqualTo("晴 25°C");
        assertThat(iter.getToolCalls().get(1).getOutput()).isEqualTo("阴 20°C");
    }

    @Test
    void multiTurnConversation() {
        // 两轮对话，每轮各有独立的 ModelUsageEntity
        List<Message> messages = List.of(
                userMsg("你好"),
                assistantMsg("你好！"),
                userMsg("几点了？"),
                assistantWithTool("", "tool-1", "Bash", Map.of("command", "date")),
                toolResultMsg("tool-1", "10:00", false),
                assistantMsg("10 点整。")
        );
        ModelUsageEntity usage0 = makeUsage(100, 50, "claude-3", Collections.emptyList());
        ToolCallRecord rec = makeRecord("Bash", 30, 5000L, true);
        ModelUsageEntity usage1 = makeUsage(200, 80, "claude-3", List.of(rec));

        SessionReplayDto replay = buildReplay(messages, List.of(usage0, usage1));

        assertThat(replay.getTurns()).hasSize(2);

        Turn t0 = replay.getTurns().get(0);
        assertThat(t0.getUserMessage()).isEqualTo("你好");
        assertThat(t0.getFinalResponse()).isEqualTo("你好！");
        assertThat(t0.getIterationCount()).isZero();
        assertThat(t0.getInputTokens()).isEqualTo(100);

        Turn t1 = replay.getTurns().get(1);
        assertThat(t1.getUserMessage()).isEqualTo("几点了？");
        assertThat(t1.getIterationCount()).isEqualTo(1);
        assertThat(t1.getInputTokens()).isEqualTo(200);
        assertThat(t1.getFinalResponse()).isEqualTo("10 点整。");
    }

    @Test
    void cancelledIteration_noToolResult() {
        // 取消场景：assistant 发出 tool_use 但没有 tool_result
        List<Message> messages = List.of(
                userMsg("运行一个长任务"),
                assistantWithTool("", "tool-1", "LongTask", Map.of("duration", "60"))
                // 没有 tool_result → 被取消了
        );
        ModelUsageEntity usage = makeUsage(100, 50, "claude-3", Collections.emptyList());

        SessionReplayDto replay = buildReplay(messages, List.of(usage));

        assertThat(replay.getTurns()).hasSize(1);
        Turn turn = replay.getTurns().get(0);
        assertThat(turn.getIterationCount()).isEqualTo(1);

        ReplayToolCall tc = turn.getIterations().get(0).getToolCalls().get(0);
        assertThat(tc.getName()).isEqualTo("LongTask");
        assertThat(tc.isSuccess()).isFalse(); // 取消的 tool call 应标记为失败
        assertThat(tc.getOutput()).isNull();
    }

    @Test
    void toolCallWithError() {
        // 工具执行失败
        List<Message> messages = List.of(
                userMsg("删除文件"),
                assistantWithTool("", "tool-1", "Bash", Map.of("command", "rm /nonexistent")),
                toolResultMsg("tool-1", "No such file or directory", true),
                assistantMsg("文件不存在。")
        );
        ToolCallRecord rec = makeRecord("Bash", 10, 1000L, false);
        ModelUsageEntity usage = makeUsage(100, 50, "claude-3", List.of(rec));

        SessionReplayDto replay = buildReplay(messages, List.of(usage));

        ReplayToolCall tc = replay.getTurns().get(0).getIterations().get(0).getToolCalls().get(0);
        assertThat(tc.isSuccess()).isFalse();
        assertThat(tc.getOutput()).isEqualTo("No such file or directory");
    }

    @Test
    void emptySession_noMessages() {
        SessionReplayDto replay = buildReplay(Collections.emptyList(), Collections.emptyList());
        assertThat(replay.getTurns()).isEmpty();
    }

    @Test
    void systemMessagesSkipped() {
        Message sysMsg = new Message();
        sysMsg.setRole(Message.Role.SYSTEM);
        sysMsg.setContent("You are a helpful assistant.");

        List<Message> messages = List.of(
                sysMsg,
                userMsg("你好"),
                assistantMsg("你好！")
        );
        ModelUsageEntity usage = makeUsage(100, 50, "claude-3", Collections.emptyList());

        SessionReplayDto replay = buildReplay(messages, List.of(usage));

        assertThat(replay.getTurns()).hasSize(1);
        assertThat(replay.getTurns().get(0).getUserMessage()).isEqualTo("你好");
    }

    @Test
    void turnDuration_computedFromToolTimestamps() {
        // 两个工具调用，duration 应该是 last.timestamp + last.durationMs - first.timestamp
        List<Message> messages = List.of(
                userMsg("做两件事"),
                assistantWithTools("", new String[][]{
                        {"tool-1", "TaskA"},
                        {"tool-2", "TaskB"}
                }),
                toolResultsMsg(new String[][]{
                        {"tool-1", "done A"},
                        {"tool-2", "done B"}
                }),
                assistantMsg("都搞定了。")
        );
        List<ToolCallRecord> records = List.of(
                new ToolCallRecord("TaskA", Map.of(), "done A", true, 100, 10000),
                new ToolCallRecord("TaskB", Map.of(), "done B", true, 200, 10100)
        );
        ModelUsageEntity usage = makeUsage(200, 100, "claude-3", records);

        SessionReplayDto replay = buildReplay(messages, List.of(usage));

        Turn turn = replay.getTurns().get(0);
        // duration = (10100 + 200) - 10000 = 300ms
        assertThat(turn.getDurationMs()).isEqualTo(300L);
    }

    @Test
    void noModelUsageAvailable_gracefulDegradation() {
        // 没有 model usage 记录（如系统异常），replay 仍然能构建，只是没有 timing
        List<Message> messages = List.of(
                userMsg("你好"),
                assistantWithTool("", "tool-1", "Bash", Map.of("command", "echo hi")),
                toolResultMsg("tool-1", "hi", false),
                assistantMsg("hi!")
        );

        SessionReplayDto replay = buildReplay(messages, Collections.emptyList());

        assertThat(replay.getTurns()).hasSize(1);
        Turn turn = replay.getTurns().get(0);
        assertThat(turn.getInputTokens()).isZero();
        assertThat(turn.getIterationCount()).isEqualTo(1);
        // tool call 没有 timing 但结构正确
        ReplayToolCall tc = turn.getIterations().get(0).getToolCalls().get(0);
        assertThat(tc.getName()).isEqualTo("Bash");
        assertThat(tc.getOutput()).isEqualTo("hi");
        assertThat(tc.getDurationMs()).isNull();
    }
}
