package com.skillforge.core.engine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 管理正在等待用户回答的 ask_user 请求。
 * AgentLoopEngine 在处理 ask_user tool_use 时注册一个 askId,
 * 然后阻塞等待 ChatController 的 answer 端点 complete 该 askId。
 */
public class PendingAskRegistry {

    public static class PendingAsk {
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile String answer;

        public String getAnswer() {
            return answer;
        }
    }

    private final Map<String, PendingAsk> pending = new ConcurrentHashMap<>();

    public PendingAsk register(String askId) {
        PendingAsk ask = new PendingAsk();
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
}
