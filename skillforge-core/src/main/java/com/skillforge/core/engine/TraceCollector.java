package com.skillforge.core.engine;

/**
 * 链路追踪收集器接口。
 * <p>
 * 由 skillforge-server 实现（持久化到 t_trace_span），core 模块通过此接口解耦。
 * 引擎在每个操作完成后调用 {@link #record(TraceSpan)} 写入 span。
 */
public interface TraceCollector {

    /** 记录一个已完成的 span。实现方应异步写入以免阻塞引擎主循环。 */
    void record(TraceSpan span);
}
