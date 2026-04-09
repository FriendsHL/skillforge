package com.skillforge.core.compact;

import com.skillforge.core.model.Message;

import java.util.List;

/**
 * Engine → Server 的压缩回调。
 * <p>Server 模块(CompactionService)注入此接口的实现, core 模块通过它触发真实压缩而不依赖
 * Spring / JPA。
 *
 * <p>两个方法保持 source 标签区分 trigger 来源(见 trigger matrix):
 * <ul>
 *   <li>compactLight(sourceLabel="agent-tool" | "engine-soft" | "engine-gap" | "user-manual")</li>
 *   <li>compactFull(sourceLabel="agent-tool" | "engine-hard" | "user-manual")</li>
 * </ul>
 */
public interface ContextCompactorCallback {

    /**
     * 触发一次 light 压缩 (纯规则, 无 LLM)。
     *
     * @param sessionId   当前 session id
     * @param currentMessages 当前内存中的 messages 列表 (engine 侧)
     * @param sourceLabel 来源标签, 用于记录 event.source
     * @param reason      人类可读原因
     * @return 压缩后的 messages (若未变化返回原引用); 结果事件已由实现方持久化
     */
    CompactCallbackResult compactLight(String sessionId, List<Message> currentMessages,
                                        String sourceLabel, String reason);

    /**
     * 触发一次 full 压缩 (LLM 驱动)。
     */
    CompactCallbackResult compactFull(String sessionId, List<Message> currentMessages,
                                       String sourceLabel, String reason);

    /** 压缩回调的返回值。 */
    class CompactCallbackResult {
        public final List<Message> messages;
        public final boolean performed;
        public final int tokensReclaimed;
        public final int beforeTokens;
        public final int afterTokens;
        public final String summary;

        public CompactCallbackResult(List<Message> messages, boolean performed,
                                     int tokensReclaimed, int beforeTokens, int afterTokens,
                                     String summary) {
            this.messages = messages;
            this.performed = performed;
            this.tokensReclaimed = tokensReclaimed;
            this.beforeTokens = beforeTokens;
            this.afterTokens = afterTokens;
            this.summary = summary;
        }

        public static CompactCallbackResult noOp(List<Message> messages, String reason) {
            return new CompactCallbackResult(messages, false, 0, 0, 0, reason);
        }
    }
}
