package com.skillforge.core.llm;

import com.skillforge.core.model.ToolUseBlock;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * LLM 响应模型，统一不同提供商的返回格式。
 */
public class LlmResponse {

    private String content;
    private String reasoningContent;
    private List<ToolUseBlock> toolUseBlocks = new ArrayList<>();
    private String stopReason;
    private Usage usage;

    public LlmResponse() {
    }

    /**
     * 是否因工具调用而停止。
     */
    public boolean isToolUse() {
        return hasValidToolUseBlocks();
    }

    /**
     * Whether this response contains executable tool-use blocks.
     *
     * <p>Provider stop metadata is not reliable enough to be the source of truth:
     * some OpenAI-compatible streams can include tool call content while reporting
     * {@code end_turn}. The structural content decides whether the engine must run
     * tools.
     */
    public boolean hasValidToolUseBlocks() {
        return !getValidToolUseBlocks().isEmpty();
    }

    /**
     * Return tool-use blocks that can safely participate in the 1:1
     * tool_use/tool_result invariant.
     */
    public List<ToolUseBlock> getValidToolUseBlocks() {
        return validToolUseBlocks(toolUseBlocks);
    }

    public static List<ToolUseBlock> validToolUseBlocks(List<ToolUseBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        List<ToolUseBlock> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ToolUseBlock block : blocks) {
            if (!isUsableToolUseBlock(block) || !seen.add(block.getId())) {
                continue;
            }
            out.add(block);
        }
        return out;
    }

    public static boolean isUsableToolUseBlock(ToolUseBlock block) {
        return block != null
                && isUsableToolCallId(block.getId())
                && block.getName() != null
                && !block.getName().isBlank();
    }

    public static boolean isUsableToolCallId(String id) {
        return id != null && !id.isBlank() && !"null".equalsIgnoreCase(id);
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getReasoningContent() {
        return reasoningContent;
    }

    public void setReasoningContent(String reasoningContent) {
        this.reasoningContent = reasoningContent;
    }

    public List<ToolUseBlock> getToolUseBlocks() {
        return toolUseBlocks;
    }

    public void setToolUseBlocks(List<ToolUseBlock> toolUseBlocks) {
        this.toolUseBlocks = toolUseBlocks != null ? toolUseBlocks : new ArrayList<>();
    }

    public String getStopReason() {
        return stopReason;
    }

    public void setStopReason(String stopReason) {
        this.stopReason = stopReason;
    }

    public Usage getUsage() {
        return usage;
    }

    public void setUsage(Usage usage) {
        this.usage = usage;
    }

    /**
     * Token 用量统计。
     *
     * <p>PROMPT-CACHE-MVP (Phase 3): {@code cacheReadInputTokens} +
     * {@code cacheCreationInputTokens} added so providers can report prompt-cache hits.
     * The 2-arg constructor is preserved for backward compat — callers that don't yet
     * surface cache numbers (legacy SSE paths in non-Anthropic providers, tests) can
     * still build a Usage without breaking.
     *
     * <p>r3 semantic contract (FE r2 push-back fix): {@link #getInputTokens() inputTokens}
     * across all provider families represents the <em>non-cached</em> input tokens.
     * Total prompt size = {@code inputTokens + cacheReadInputTokens +
     * cacheCreationInputTokens}. {@link com.skillforge.core.llm.cache.UsageNormalizer}
     * normalizes provider-specific wire fields (Claude's {@code input_tokens} is already
     * non-cached; DeepSeek/Qwen/OpenAI wire {@code prompt_tokens} is TOTAL and gets the
     * cached portion subtracted on parse).
     */
    public static class Usage {

        /** Non-cached input tokens (uniform across all providers — see class JavaDoc). */
        private int inputTokens;
        private int outputTokens;
        /** Prompt-cache read tokens (Anthropic: cache_read_input_tokens; DeepSeek:
         *  prompt_cache_hit_tokens; OpenAI-shape: prompt_tokens_details.cached_tokens). */
        private int cacheReadInputTokens;
        /** Prompt-cache write/creation tokens (Anthropic only). NULL on the wire is mapped
         *  to 0 here; on the persistence layer the corresponding column is nullable for
         *  backward compat with rows pre-V62 (INV-11). */
        private int cacheCreationInputTokens;

        public Usage() {
        }

        /** Backward-compat constructor — leaves cache fields at 0. */
        public Usage(int inputTokens, int outputTokens) {
            this(inputTokens, outputTokens, 0, 0);
        }

        public Usage(int inputTokens, int outputTokens,
                     int cacheReadInputTokens, int cacheCreationInputTokens) {
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.cacheReadInputTokens = cacheReadInputTokens;
            this.cacheCreationInputTokens = cacheCreationInputTokens;
        }

        public int getInputTokens() {
            return inputTokens;
        }

        public void setInputTokens(int inputTokens) {
            this.inputTokens = inputTokens;
        }

        public int getOutputTokens() {
            return outputTokens;
        }

        public void setOutputTokens(int outputTokens) {
            this.outputTokens = outputTokens;
        }

        public int getCacheReadInputTokens() {
            return cacheReadInputTokens;
        }

        public void setCacheReadInputTokens(int cacheReadInputTokens) {
            this.cacheReadInputTokens = cacheReadInputTokens;
        }

        public int getCacheCreationInputTokens() {
            return cacheCreationInputTokens;
        }

        public void setCacheCreationInputTokens(int cacheCreationInputTokens) {
            this.cacheCreationInputTokens = cacheCreationInputTokens;
        }
    }
}
