package com.skillforge.core.llm.observer;

import com.skillforge.core.llm.LlmResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 静态工具：把每次 hook 触发统一通过 try-catch 隔离，
 * 防止 observer 异常传播回 provider 调用栈（plan §4.4 硬约束 1）。
 */
public final class SafeObservers {

    private static final Logger log = LoggerFactory.getLogger(SafeObservers.class);

    private SafeObservers() {}

    public static void notifyBefore(LlmCallObserverRegistry registry,
                                    LlmCallContext ctx, RawHttpRequest request) {
        if (registry == null) return;
        for (LlmCallObserver o : registry.observers()) {
            try {
                o.beforeCall(ctx, request);
            } catch (Throwable t) {
                log.warn("Observer.beforeCall threw, swallowing: observer={} traceId={} spanId={}",
                        o.getClass().getSimpleName(),
                        ctx == null ? null : ctx.traceId(),
                        ctx == null ? null : ctx.spanId(), t);
            }
        }
    }

    public static void notifyAfter(LlmCallObserverRegistry registry, LlmCallContext ctx,
                                   RawHttpResponse response, LlmResponse parsed) {
        if (registry == null) return;
        for (LlmCallObserver o : registry.observers()) {
            try {
                o.afterCall(ctx, response, parsed);
            } catch (Throwable t) {
                log.warn("Observer.afterCall threw, swallowing: observer={} spanId={}",
                        o.getClass().getSimpleName(),
                        ctx == null ? null : ctx.spanId(), t);
            }
        }
    }

    public static void notifyStreamChunk(LlmCallObserverRegistry registry,
                                         LlmCallContext ctx, String line) {
        if (registry == null) return;
        for (LlmCallObserver o : registry.observers()) {
            try {
                o.onStreamChunk(ctx, line);
            } catch (Throwable t) {
                log.warn("Observer.onStreamChunk threw, swallowing: observer={} spanId={}",
                        o.getClass().getSimpleName(),
                        ctx == null ? null : ctx.spanId(), t);
            }
        }
    }

    public static void notifyStreamComplete(LlmCallObserverRegistry registry, LlmCallContext ctx,
                                            RawStreamCapture capture, LlmResponse parsed) {
        if (registry == null) return;
        for (LlmCallObserver o : registry.observers()) {
            try {
                o.onStreamComplete(ctx, capture, parsed);
            } catch (Throwable t) {
                log.warn("Observer.onStreamComplete threw, swallowing: observer={} spanId={}",
                        o.getClass().getSimpleName(),
                        ctx == null ? null : ctx.spanId(), t);
            }
        }
    }

    public static void notifyError(LlmCallObserverRegistry registry, LlmCallContext ctx,
                                   Throwable err, RawStreamCapture partial) {
        if (registry == null) return;
        for (LlmCallObserver o : registry.observers()) {
            try {
                o.onError(ctx, err, partial);
            } catch (Throwable t) {
                log.warn("Observer.onError threw, swallowing: observer={} spanId={}",
                        o.getClass().getSimpleName(),
                        ctx == null ? null : ctx.spanId(), t);
            }
        }
    }
}
