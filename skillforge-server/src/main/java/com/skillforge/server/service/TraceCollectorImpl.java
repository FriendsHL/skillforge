package com.skillforge.server.service;

import com.skillforge.core.engine.TraceCollector;
import com.skillforge.core.engine.TraceSpan;
import org.springframework.stereotype.Service;

/**
 * OBS-2 M6 compatibility bean.
 *
 * <p>The legacy {@code t_trace_span} table is dropped. Core still accepts a
 * {@link TraceCollector} for older call sites and tests, but production trace data now
 * flows through {@code TraceLifecycleSink} into {@code t_llm_trace}/{@code t_llm_span}.
 */
@Service
public class TraceCollectorImpl implements TraceCollector {

    @Override
    public void record(TraceSpan span) {
        // no-op: legacy trace_span writes closed in OBS-2 M4; table dropped in M6.
    }
}
