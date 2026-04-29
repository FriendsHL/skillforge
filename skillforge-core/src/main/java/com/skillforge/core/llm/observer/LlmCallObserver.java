package com.skillforge.core.llm.observer;

import com.skillforge.core.llm.LlmResponse;

/**
 * LLM 调用生命周期观察者。
 *
 * <p>调用语义（plan §4.2）：
 * <ul>
 *   <li>{@link #beforeCall} —— provider 在 build request body 完成、进入握手循环之前**恰好**调用一次。
 *   握手 retry 期间不再触发。</li>
 *   <li>{@link #onStreamChunk} —— 流式调用每行 SSE data 触发一次（同步、调用线程）。</li>
 *   <li>{@link #onStreamComplete} —— 流式调用完整成功后触发一次（与 {@link #onError} 互斥）。</li>
 *   <li>{@link #afterCall} —— 非流式调用成功完成后触发一次（与 {@link #onError} 互斥）。</li>
 *   <li>{@link #onError} —— 调用最终失败（握手 retry 用尽 / 中途 IO / 取消）触发一次。</li>
 * </ul>
 *
 * <p>所有 observer 抛出的异常**绝不可**传播回 provider 调用栈，由
 * {@link SafeObservers} 统一 swallow + log。
 */
public interface LlmCallObserver {

    default void beforeCall(LlmCallContext ctx, RawHttpRequest request) {}

    /**
     * 非流式调用成功后触发。
     *
     * @param parsed 已解析为内部模型的 LlmResponse
     */
    default void afterCall(LlmCallContext ctx, RawHttpResponse response, LlmResponse parsed) {}

    /**
     * 流式调用每条 {@code data: ...} SSE 行触发；line 不含 "data: " 前缀。
     */
    default void onStreamChunk(LlmCallContext ctx, String line) {}

    /**
     * 流式调用完整成功后触发。
     *
     * @param capture 流式累积的字节快照（rawSse + accumulatedJson）
     * @param parsed  已解析为内部模型的 LlmResponse
     */
    default void onStreamComplete(LlmCallContext ctx, RawStreamCapture capture, LlmResponse parsed) {}

    /**
     * 调用最终失败触发。partial 仅在流式握手成功后中途出错时非空，否则 null。
     */
    default void onError(LlmCallContext ctx, Throwable error, RawStreamCapture partial) {}
}
