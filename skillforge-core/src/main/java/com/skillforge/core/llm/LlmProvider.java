package com.skillforge.core.llm;

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
}
