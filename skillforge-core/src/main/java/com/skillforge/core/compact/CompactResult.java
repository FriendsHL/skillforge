package com.skillforge.core.compact;

import com.skillforge.core.model.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * 压缩策略的结果。
 */
public class CompactResult {

    private final List<Message> messages;
    private final int beforeTokens;
    private final int afterTokens;
    private final int tokensReclaimed;
    private final int beforeMessageCount;
    private final int afterMessageCount;
    private final List<String> strategiesApplied;

    public CompactResult(List<Message> messages,
                         int beforeTokens,
                         int afterTokens,
                         int beforeMessageCount,
                         int afterMessageCount,
                         List<String> strategiesApplied) {
        this.messages = messages;
        this.beforeTokens = beforeTokens;
        this.afterTokens = afterTokens;
        this.tokensReclaimed = Math.max(0, beforeTokens - afterTokens);
        this.beforeMessageCount = beforeMessageCount;
        this.afterMessageCount = afterMessageCount;
        this.strategiesApplied = strategiesApplied != null ? strategiesApplied : new ArrayList<>();
    }

    public List<Message> getMessages() { return messages; }
    public int getBeforeTokens() { return beforeTokens; }
    public int getAfterTokens() { return afterTokens; }
    public int getTokensReclaimed() { return tokensReclaimed; }
    public int getBeforeMessageCount() { return beforeMessageCount; }
    public int getAfterMessageCount() { return afterMessageCount; }
    public List<String> getStrategiesApplied() { return strategiesApplied; }

    public boolean isNoOp() {
        return tokensReclaimed == 0 && beforeMessageCount == afterMessageCount;
    }
}
