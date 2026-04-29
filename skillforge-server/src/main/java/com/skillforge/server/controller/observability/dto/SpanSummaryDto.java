package com.skillforge.server.controller.observability.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * OBS-1 §7.2 R2-B1 — sealed interface for the merged session/spans response.
 * Discriminated by {@code kind} ("llm" | "tool").
 *
 * <p>Frontend mirror is {@code src/types/observability.ts: SpanSummary}. Any change here
 * MUST send a SendMessage to Frontend Dev and tag {@code obs-1-dto-freeze} re-cut.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
              property = "kind", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = LlmSpanSummaryDto.class, name = "llm"),
        @JsonSubTypes.Type(value = ToolSpanSummaryDto.class, name = "tool")
})
@JsonIgnoreProperties(ignoreUnknown = true)
public sealed interface SpanSummaryDto permits LlmSpanSummaryDto, ToolSpanSummaryDto {

    String kind();

    String spanId();

    String traceId();

    String parentSpanId();

    java.time.Instant startedAt();

    java.time.Instant endedAt();

    long latencyMs();
}
