package com.skillforge.core.llm;

import com.skillforge.core.llm.observer.LlmCallContext;

/**
 * 统一的 LLM 提供商接口，抽象不同 LLM API 的差异。
 */
public interface LlmProvider {

    /**
     * 提供商名称，如 "claude"、"openai" 等。
     */
    String getName();

    /**
     * 同步调用 LLM，返回完整响应。
     *
     * @param request 请求参数
     * @return 完整响应
     */
    LlmResponse chat(LlmRequest request);

    /**
     * 流式调用 LLM，通过回调逐步推送内容。
     *
     * @param request 请求参数
     * @param handler 流式回调处理器
     */
    void chatStream(LlmRequest request, LlmStreamHandler handler);

    /**
     * 带 observer 上下文的同步调用。新签名；旧签名 default 委托。
     *
     * @since OBS-1
     */
    default LlmResponse chat(LlmRequest request, LlmCallContext ctx) {
        return chat(request);
    }

    /**
     * 带 observer 上下文的流式调用。新签名；旧签名 default 委托。
     *
     * @since OBS-1
     */
    default void chatStream(LlmRequest request, LlmCallContext ctx, LlmStreamHandler handler) {
        chatStream(request, handler);
    }
}
