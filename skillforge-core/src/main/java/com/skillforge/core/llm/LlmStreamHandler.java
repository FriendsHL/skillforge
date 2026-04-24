package com.skillforge.core.llm;

import com.skillforge.core.model.ToolUseBlock;

import java.util.Map;

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

    // ---- 流内取消支持(默认 no-op 保持向后兼容) ----

    /**
     * Provider 创建底层 HTTP Call 后回调此方法,传入可取消该 Call 的 Runnable。
     * 流结束时 Provider 会以 null 调用一次以清理引用。
     *
     * @param cancelAction 取消动作;null 表示清理
     */
    default void onStreamStart(Runnable cancelAction) {}

    /**
     * Provider 在 SSE 循环中轮询此方法;返回 true 时 Provider 应尽快中断读取。
     */
    default boolean isCancelled() { return false; }

    // ---- 新增:细粒度 token/tool_use 流式事件(默认 no-op 保持向后兼容) ----

    /**
     * LLM 开始生成一个 tool_use 块(已知 id 和 name, 但 input 尚未到达)。
     */
    default void onToolUseStart(String toolUseId, String name) {
        // no-op
    }

    /**
     * tool_use 的 input JSON 分片到达。前端可据此实时展示"正在组装参数"。
     * 注意:这是原始 JSON 片段,不保证自身合法 JSON。
     */
    default void onToolUseInputDelta(String toolUseId, String jsonFragment) {
        // no-op
    }

    /**
     * tool_use 组装完成并且 input 已成功解析为 Map。
     * 在 onToolUse 之前或之后触发(实现方保证至少其中一个到达)。
     */
    default void onToolUseEnd(String toolUseId, Map<String, Object> parsedInput) {
        // no-op
    }

    /**
     * Provider 在流式处理过程中发现可观测警告时调用。
     * 当前用途：tool_use input JSON 解析失败后的兜底截断事件（{@code warning.tool_input_truncated}）。
     * 引擎侧实现把这些 warning 写入 LLM_CALL span 的 attributes，供前端 / 日志排障。
     *
     * @param key   warning 名称；约定以 {@code "warning."} 前缀命名
     * @param value 任意可序列化对象（String / Boolean / Number / Map）
     */
    default void onWarning(String key, Object value) {
        // no-op
    }
}
