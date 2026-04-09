package com.skillforge.core.engine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理正在运行的 Agent Loop,支持通过 sessionId 请求取消。
 * ChatService.runLoop 启动前 register,finally 里 unregister;
 * Controller 通过 cancel(sessionId) 标记对应 LoopContext 的 cancelRequested 位。
 */
public class CancellationRegistry {

    private final Map<String, LoopContext> running = new ConcurrentHashMap<>();

    /** 注册一个正在运行的 loop 上下文(同一 sessionId 覆盖旧的)。 */
    public void register(String sessionId, LoopContext ctx) {
        if (sessionId == null || ctx == null) return;
        running.put(sessionId, ctx);
    }

    /** 清除注册(loop 结束后由 finally 调用)。 */
    public void unregister(String sessionId) {
        if (sessionId == null) return;
        running.remove(sessionId);
    }

    /**
     * 请求取消某个 session 的 loop。
     *
     * @return true 如果找到对应的 loop 并标记了取消;false 如果当前无正在运行的 loop。
     */
    public boolean cancel(String sessionId) {
        if (sessionId == null) return false;
        LoopContext ctx = running.get(sessionId);
        if (ctx == null) return false;
        ctx.requestCancel();
        return true;
    }

    /** 测试/运维辅助: 判断某个 session 当前是否有注册的 loop。 */
    public boolean isRunning(String sessionId) {
        return sessionId != null && running.containsKey(sessionId);
    }
}
