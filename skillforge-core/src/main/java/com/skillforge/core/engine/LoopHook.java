package com.skillforge.core.engine;

import com.skillforge.core.llm.LlmResponse;

/**
 * Agent Loop 级别的 Hook，在整个对话循环前后触发。
 */
public interface LoopHook {

    /**
     * 循环开始前调用，可修改用户消息或注入上下文。返回 null 表示中断循环。
     */
    default LoopContext beforeLoop(LoopContext context) {
        return context;
    }

    /**
     * 循环结束后调用，可记录日志、费用等。
     */
    default void afterLoop(LoopContext context, LlmResponse finalResponse) {
    }
}
