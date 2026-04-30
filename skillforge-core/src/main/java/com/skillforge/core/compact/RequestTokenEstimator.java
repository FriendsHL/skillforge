package com.skillforge.core.compact;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.Message;
import com.skillforge.core.model.ToolSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Preflight estimator for the LLM request envelope.
 *
 * <p>Sums {@code systemPrompt + messages + tool schemas + maxTokens (output reservation)}
 * so the AgentLoopEngine compact-trigger ratio matches what the dashboard's
 * {@code ContextBreakdownService} shows. Both consumers go through {@link TokenEstimator}
 * and share the {@code cl100k_base} encoding — proportional, not billing-grade (±10%).
 *
 * <p><b>Why static:</b> {@link TokenEstimator} is stateless and thread-safe; this class
 * is just a multi-block aggregator on top of it. Callers pass an {@link ObjectMapper}
 * so the same Jackson configuration drives tool-schema serialisation everywhere
 * (footgun #1: must be a {@code JavaTimeModule}-registered Spring-managed mapper).
 *
 * <p>Output reservation: {@code maxTokens} is what the LLM holds back for its own
 * response, so it must come out of the window when computing pre-call ratio. Otherwise
 * we'd report 0.79 right up to the moment the model declines because the response
 * doesn't fit.
 *
 * @since CTX-1
 */
public final class RequestTokenEstimator {

    private static final Logger log = LoggerFactory.getLogger(RequestTokenEstimator.class);

    private RequestTokenEstimator() {}

    /**
     * Estimate the total token cost of a fully-built LLM request.
     *
     * @param systemPrompt may be null/empty
     * @param messages     may be null/empty
     * @param tools        may be null/empty
     * @param maxTokens    output reservation; clamped to {@code >= 0}
     * @param jsonMapper   used to serialise tool schemas (preferably the Spring Bean
     *                     with {@code JavaTimeModule}); when null, falls back to
     *                     {@code name + description} for each schema
     * @return non-negative token estimate
     */
    public static int estimate(String systemPrompt,
                                List<Message> messages,
                                List<ToolSchema> tools,
                                int maxTokens,
                                ObjectMapper jsonMapper) {
        int total = 0;
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            total += TokenEstimator.estimateString(systemPrompt);
        }
        if (messages != null && !messages.isEmpty()) {
            total += TokenEstimator.estimate(messages);
        }
        if (tools != null && !tools.isEmpty()) {
            total += estimateToolSchemas(tools, jsonMapper);
        }
        total += Math.max(0, maxTokens);
        return total;
    }

    /**
     * Estimate the JSON token cost of a list of tool schemas. Exposed publicly so the
     * dashboard's {@code ContextBreakdownService} can share the exact same algorithm
     * (FR-1.1 — no token-count drift between engine triggers and the right-rail UI).
     *
     * <p>When {@code mapper} is null or a schema fails to serialise, falls back to
     * {@code name + description} so the estimate is a lower bound rather than zero.
     */
    public static int estimateToolSchemas(List<ToolSchema> tools, ObjectMapper mapper) {
        if (tools == null || tools.isEmpty()) return 0;
        int total = 0;
        for (ToolSchema schema : tools) {
            if (schema == null) continue;
            if (mapper != null) {
                try {
                    total += TokenEstimator.estimateString(mapper.writeValueAsString(schema));
                    continue;
                } catch (JsonProcessingException e) {
                    log.debug("ToolSchema serialise failed for '{}', fallback to name+description",
                            schema.getName(), e);
                }
            }
            // Best-effort fallback (no mapper or serialisation threw)
            total += TokenEstimator.estimateString(nullSafe(schema.getName()))
                   + TokenEstimator.estimateString(nullSafe(schema.getDescription()));
        }
        return total;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
