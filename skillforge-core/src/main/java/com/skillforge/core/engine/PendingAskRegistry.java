package com.skillforge.core.engine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 管理正在等待用户回答的 ask_user 请求。
 * AgentLoopEngine 在处理 ask_user tool_use 时注册一个 askId,
 * 然后阻塞等待 ChatController 的 answer 端点 complete 该 askId。
 *
 * <p>r2 扩展:PendingAsk 新增 sessionId 字段 + {@link #hasPendingForSession} API,
 * 给 install confirmation 流程做 "同 turn 并行 ask_user + install" 互斥检查使用
 * (见 docs/design-install-confirmation-flow.md §0#7 / §3 / §5 B3 fix)。
 */
public class PendingAskRegistry {

    public static class PendingAsk {
        private final String sessionId;
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile String answer;

        public PendingAsk() {
            this(null);
        }

        public PendingAsk(String sessionId) {
            this.sessionId = sessionId;
        }

        public String getSessionId() {
            return sessionId;
        }

        public String getAnswer() {
            return answer;
        }
    }

    private final Map<String, PendingAsk> pending = new ConcurrentHashMap<>();

    /**
     * @deprecated 保留向后兼容;新代码请调用 {@link #register(String, String)} 带上 sessionId,
     *             以便 {@link #hasPendingForSession} 正确检测互斥。
     */
    @Deprecated
    public PendingAsk register(String askId) {
        return register(askId, null);
    }

    public PendingAsk register(String askId, String sessionId) {
        PendingAsk ask = new PendingAsk(sessionId);
        pending.put(askId, ask);
        return ask;
    }

    /**
     * 阻塞等待指定 askId 的答复。
     *
     * @return 用户答复文本,超时返回 null
     */
    public String await(String askId, long timeoutSeconds) throws InterruptedException {
        PendingAsk ask = pending.get(askId);
        if (ask == null) {
            return null;
        }
        try {
            boolean ok = ask.latch.await(timeoutSeconds, TimeUnit.SECONDS);
            return ok ? ask.answer : null;
        } finally {
            pending.remove(askId);
        }
    }

    /**
     * 外部端点调用,release latch 并传递用户答复。
     *
     * @return true 如果成功 complete,false 如果 askId 已不存在(可能已超时或被 cancel)
     */
    public boolean complete(String askId, String answer) {
        PendingAsk ask = pending.get(askId);
        if (ask == null) {
            return false;
        }
        ask.answer = answer;
        ask.latch.countDown();
        return true;
    }

    public boolean exists(String askId) {
        return pending.containsKey(askId);
    }

    /**
     * 某 session 当前是否有 pending ask_user。用于 install-confirmation 入口互斥:
     * LLM 在同一 turn 并行发出 ask_user + Bash(install) 时,install 分支要直接返回 error
     * tool_result,避免两个 latch 同时挂在同一 session 上死锁。
     */
    public boolean hasPendingForSession(String sessionId) {
        if (sessionId == null) return false;
        for (PendingAsk ask : pending.values()) {
            if (sessionId.equals(ask.getSessionId())) {
                return true;
            }
        }
        return false;
    }
}
