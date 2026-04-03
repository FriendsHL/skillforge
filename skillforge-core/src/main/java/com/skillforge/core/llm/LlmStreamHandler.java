package com.skillforge.core.llm;

import com.skillforge.core.model.ToolUseBlock;

/**
 * 流式响应回调接口，用于处理 LLM 的流式输出。
 */
public interface LlmStreamHandler {

    /**
     * 收到文本增量。
     *
     * @param text 增量文本片段
     */
    void onText(String text);

    /**
     * 一个工具调用块解析完成。
     *
     * @param block 工具调用块
     */
    void onToolUse(ToolUseBlock block);

    /**
     * 流式响应完成，提供完整的聚合响应。
     *
     * @param fullResponse 聚合后的完整响应
     */
    void onComplete(LlmResponse fullResponse);

    /**
     * 流式过程中发生错误。
     *
     * @param error 异常
     */
    void onError(Throwable error);
}
