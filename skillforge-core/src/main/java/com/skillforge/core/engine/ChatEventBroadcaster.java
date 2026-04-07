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
