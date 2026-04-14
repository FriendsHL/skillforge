package com.skillforge.core.engine;

import com.skillforge.core.model.Message;

import java.util.List;
import java.util.Map;

/**
 * 向会话订阅者推送事件(通常由 WebSocket 实现)。
 * core 模块只定义接口,实现放在 server 模块。
 */
public interface ChatEventBroadcaster {

    /** 广播 session 状态变更。status: idle / running / waiting_user / error */
    void sessionStatus(String sessionId, String status, String step, String error);

    /** 广播一条新消息追加事件(engine 执行过程中每当有新消息落入 messages 列表时调用)。 */
    void messageAppended(String sessionId, Message message);

    /** 广播一次全量 messages 同步(可选,主要用于 loop 结束前对齐)。 */
    default void messagesSnapshot(String sessionId, List<Message> messages) {
        // no-op default
    }

    /** 广播 ask_user 事件,前端据此弹出回答面板。 */
    void askUser(String sessionId, AskUserEvent event);

    /** 工具开始执行(用于前端展示进行中卡片)。 */
    default void toolStarted(String sessionId, String toolUseId, String name, Map<String, Object> input) {
        // no-op default
    }

    /** 工具执行结束(成功/失败/耗时)。 */
    default void toolFinished(String sessionId, String toolUseId, String status, long durationMs, String error) {
        // no-op default
    }

    /** LLM 流式输出文本增量(loop 中正在生成的 assistant 回复)。 */
    default void assistantDelta(String sessionId, String text) {
        // no-op default
    }

    /** LLM 流式输出结束(无论 onComplete 还是 onError)。前端可在此清空临时 streamingText。 */
    default void assistantStreamEnd(String sessionId) {
        // no-op default
    }

    /** 会话标题更新事件。前端 SessionList / Chat 顶栏据此实时刷新标题。 */
    default void sessionTitleUpdated(String sessionId, String title) {
        // no-op default
    }

    // ---- 细粒度 token 流式事件(additive, 默认 no-op) ----

    /** 文本 token 增量(与 assistantDelta 语义一致,保留以对齐 LlmStreamHandler 命名)。 */
    default void textDelta(String sessionId, String delta) {
        // no-op default
    }

    /** tool_use 的 input JSON 分片。前端据此在 inflight 卡片里实时展示正在组装的参数。 */
    default void toolUseDelta(String sessionId, String toolUseId, String toolName, String jsonFragment) {
        // no-op default
    }

    /** tool_use 的 input 组装完成并解析为 Map。 */
    default void toolUseComplete(String sessionId, String toolUseId, Map<String, Object> parsedInput) {
        // no-op default
    }

    /**
     * 向 user 级别订阅者推送事件(per-user WebSocket 通道)。
     * 用于 Session 列表页实时刷新 runtimeStatus / title / messageCount / 新建 / 删除 等。
     * 载荷应保持精简(只含列表卡片所需字段)。
     */
    default void userEvent(Long userId, java.util.Map<String, Object> payload) {
        // no-op default
    }

    // ---- Multi-agent collaboration events ----

    /** A new team member was spawned in a collaboration run. */
    default void collabMemberSpawned(String collabRunId, String handle, String sessionId, String agentName) {
        // no-op default
    }

    /** A team member finished in a collaboration run. */
    default void collabMemberFinished(String collabRunId, String handle, String status, String summary) {
        // no-op default
    }

    /** Collaboration run status changed (RUNNING / COMPLETED / CANCELLED). */
    default void collabRunStatus(String collabRunId, String status) {
        // no-op default
    }

    /** A peer message was routed between team members. */
    default void collabMessageRouted(String collabRunId, String fromHandle, String toHandle, String messageId) {
        // no-op default
    }

    class AskUserEvent {
        public String askId;
        public String question;
        public String context;
        public List<Option> options;
        public boolean allowOther;

        public static class Option {
            public String label;
            public String description;

            public Option() {
            }

            public Option(String label, String description) {
                this.label = label;
                this.description = description;
            }
        }
    }
}
