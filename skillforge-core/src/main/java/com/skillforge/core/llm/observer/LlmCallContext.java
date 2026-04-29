package com.skillforge.core.llm.observer;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * 一次 LLM 调用的上下文，由调用方（AgentLoopEngine 等）在发起调用前构造，
 * 通过参数显式传给 {@link com.skillforge.core.llm.LlmProvider#chatStream(
 * com.skillforge.core.llm.LlmRequest, LlmCallContext, com.skillforge.core.llm.LlmStreamHandler)}。
 *
 * <p>不使用 ThreadLocal —— 跨线程传递明确，便于在 SubAgent / 异步上下文中保持一致。
 */
public record LlmCallContext(
        String traceId,
        String spanId,
        String parentSpanId,
        String sessionId,
        Long agentId,
        Long userId,
        String providerName,
        String modelId,
        int iterationIndex,
        boolean stream,
        Instant startedAt,
        Map<String, Object> attributes
) {

    public LlmCallContext {
        if (spanId == null || spanId.isBlank()) {
            spanId = UUID.randomUUID().toString();
        }
        if (startedAt == null) {
            startedAt = Instant.now();
        }
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    /** 测试 / 兼容路径占位上下文（旧签名 default 委托使用）。 */
    public static LlmCallContext empty() {
        return new LlmCallContext(
                null, UUID.randomUUID().toString(), null,
                null, null, null,
                null, null, 0, false,
                Instant.now(), Collections.emptyMap()
        );
    }

    public Builder toBuilder() {
        return new Builder()
                .traceId(traceId)
                .spanId(spanId)
                .parentSpanId(parentSpanId)
                .sessionId(sessionId)
                .agentId(agentId)
                .userId(userId)
                .providerName(providerName)
                .modelId(modelId)
                .iterationIndex(iterationIndex)
                .stream(stream)
                .startedAt(startedAt)
                .attributes(attributes);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String traceId;
        private String spanId;
        private String parentSpanId;
        private String sessionId;
        private Long agentId;
        private Long userId;
        private String providerName;
        private String modelId;
        private int iterationIndex;
        private boolean stream;
        private Instant startedAt;
        private Map<String, Object> attributes = Collections.emptyMap();

        public Builder traceId(String v) { this.traceId = v; return this; }
        public Builder spanId(String v) { this.spanId = v; return this; }
        public Builder parentSpanId(String v) { this.parentSpanId = v; return this; }
        public Builder sessionId(String v) { this.sessionId = v; return this; }
        public Builder agentId(Long v) { this.agentId = v; return this; }
        public Builder userId(Long v) { this.userId = v; return this; }
        public Builder providerName(String v) { this.providerName = v; return this; }
        public Builder modelId(String v) { this.modelId = v; return this; }
        public Builder iterationIndex(int v) { this.iterationIndex = v; return this; }
        public Builder stream(boolean v) { this.stream = v; return this; }
        public Builder startedAt(Instant v) { this.startedAt = v; return this; }
        public Builder attributes(Map<String, Object> v) {
            this.attributes = v == null ? Collections.emptyMap() : v;
            return this;
        }

        public LlmCallContext build() {
            return new LlmCallContext(
                    traceId, spanId, parentSpanId, sessionId,
                    agentId, userId, providerName, modelId,
                    iterationIndex, stream, startedAt, attributes);
        }
    }
}
