package com.skillforge.core.engine;

import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * P9-2: request-time tool_result 聚合预算裁剪。
 *
 * <p>每次调用 LLM 前，对将要发送给 provider 的 messages 副本做 ephemeral 裁剪：
 * <ul>
 *   <li>单条 tool_result 已被 {@link ToolResultTruncator} 限制（默认 40K chars）。</li>
 *   <li>本预算器进一步限制整批 messages 的 tool_result 聚合量（默认 200K chars），
 *       覆盖 BUG-32 "33 条 ~3K-40K tool_result 合计 99K chars 把请求撑爆" 的场景。</li>
 * </ul>
 *
 * <p>本预算器是<strong>纯函数</strong>，对入参 messages 不做任何 mutate：
 * <ul>
 *   <li>原 messages 列表不被修改</li>
 *   <li>原 Message 对象不被修改</li>
 *   <li>原 ContentBlock 对象不被修改（被替换的 tool_result 用新建的 ContentBlock）</li>
 * </ul>
 * 仅返回深拷贝（其他类型的 ContentBlock 引用复用，因为不会被改）。
 *
 * <p>request-time 裁剪是 ephemeral 的：不写 archive 表、不影响幂等不变量；下次 request
 * 仍可能保留原文（取决于当时的聚合预算压力）。preview 文本格式与持久化归档不同，
 * 避免模型误以为 trimmed 内容可按 archive_id 读取。
 */
public final class ToolResultRequestBudgeter {

    /**
     * Request-level aggregate cap：所有 messages 中 tool_result 文本总 chars 上限。
     * BUG-32 现场最大约 99K，但 max_tokens 已触发；保守取 200K 作为软顶。
     */
    public static final int DEFAULT_REQUEST_AGGREGATE_CHARS = 200_000;

    /** 单条 tool_result 被裁剪后保留的 head/tail 总 chars。 */
    public static final int DEFAULT_TRIMMED_CHARS = 2_048;

    private ToolResultRequestBudgeter() {
    }

    /** Result holder：返回裁剪后的 messages 副本和统计信息（trace 上报用）。 */
    public static final class Result {
        public final List<Message> messages;
        public final int originalAggregateChars;
        public final int retainedAggregateChars;
        public final int trimmedCount;
        public final int totalToolResultCount;

        public Result(List<Message> messages,
                      int originalAggregateChars,
                      int retainedAggregateChars,
                      int trimmedCount,
                      int totalToolResultCount) {
            this.messages = messages;
            this.originalAggregateChars = originalAggregateChars;
            this.retainedAggregateChars = retainedAggregateChars;
            this.trimmedCount = trimmedCount;
            this.totalToolResultCount = totalToolResultCount;
        }

        public boolean wasTrimmed() {
            return trimmedCount > 0;
        }
    }

    /**
     * 用默认预算（{@link #DEFAULT_REQUEST_AGGREGATE_CHARS}）裁剪。
     */
    public static Result apply(List<Message> messages) {
        return apply(messages, DEFAULT_REQUEST_AGGREGATE_CHARS);
    }

    /**
     * 对 messages 副本做 request-time 聚合预算裁剪。
     *
     * @param messages 原始 messages（不会被修改）。null/empty 返回空 Result。
     * @param aggregateBudgetChars request 内 tool_result 文本聚合上限；&lt;=0 表示禁用。
     */
    public static Result apply(List<Message> messages, int aggregateBudgetChars) {
        if (messages == null || messages.isEmpty()) {
            return new Result(Collections.emptyList(), 0, 0, 0, 0);
        }

        // Phase 1：collect candidates (msgIndex, blockIndex, chars, toolUseId) without mutating.
        List<Candidate> candidates = new ArrayList<>();
        int aggregate = 0;
        int totalCount = 0;
        for (int mi = 0; mi < messages.size(); mi++) {
            Message m = messages.get(mi);
            if (m == null || !(m.getContent() instanceof List<?> blocks)) {
                continue;
            }
            for (int bi = 0; bi < blocks.size(); bi++) {
                Object obj = blocks.get(bi);
                String toolUseId = null;
                String text = null;
                if (obj instanceof ContentBlock cb && "tool_result".equals(cb.getType())) {
                    toolUseId = cb.getToolUseId();
                    text = cb.getContent();
                } else if (obj instanceof Map<?, ?> map && "tool_result".equals(map.get("type"))) {
                    Object idVal = map.get("tool_use_id");
                    Object contentVal = map.get("content");
                    toolUseId = idVal != null ? idVal.toString() : null;
                    if (contentVal instanceof String s) {
                        text = s;
                    } else if (contentVal != null) {
                        text = contentVal.toString();
                    }
                }
                if (text == null) {
                    continue;
                }
                int chars = text.length();
                aggregate += chars;
                totalCount++;
                candidates.add(new Candidate(mi, bi, chars, toolUseId));
            }
        }

        // No tool_results, or aggregate within budget → return shallow-copied messages list.
        if (aggregateBudgetChars <= 0 || aggregate <= aggregateBudgetChars) {
            return new Result(new ArrayList<>(messages), aggregate, aggregate, 0, totalCount);
        }

        // Phase 2：sort candidates by chars desc, mark for trim until aggregate within budget.
        // We trim each chosen result down to DEFAULT_TRIMMED_CHARS (head/tail preview).
        List<Candidate> sorted = new ArrayList<>(candidates);
        sorted.sort((a, b) -> Integer.compare(b.chars, a.chars));

        // Track which (mi, bi) to trim by encoded long key.
        java.util.Set<Long> toTrim = new java.util.HashSet<>();
        int retained = aggregate;
        for (Candidate c : sorted) {
            if (retained <= aggregateBudgetChars) break;
            if (c.chars <= DEFAULT_TRIMMED_CHARS) {
                // Already smaller than the trim target; trimming wouldn't help.
                continue;
            }
            toTrim.add(encodeKey(c.msgIndex, c.blockIndex));
            retained -= (c.chars - DEFAULT_TRIMMED_CHARS);
        }

        // Phase 3：deep-copy messages with trimmed blocks substituted (only for marked indices).
        List<Message> out = new ArrayList<>(messages.size());
        int trimmedCount = 0;
        for (int mi = 0; mi < messages.size(); mi++) {
            Message original = messages.get(mi);
            if (original == null) {
                out.add(null);
                continue;
            }
            if (!(original.getContent() instanceof List<?> blocks)) {
                // Non-list content (e.g. plain user text) — reuse original message reference.
                out.add(original);
                continue;
            }
            // Determine if this message has any block to trim.
            boolean dirty = false;
            for (int bi = 0; bi < blocks.size(); bi++) {
                if (toTrim.contains(encodeKey(mi, bi))) {
                    dirty = true;
                    break;
                }
            }
            if (!dirty) {
                out.add(original);
                continue;
            }
            // Build a new content list with trimmed blocks substituted.
            List<Object> newBlocks = new ArrayList<>(blocks.size());
            for (int bi = 0; bi < blocks.size(); bi++) {
                Object obj = blocks.get(bi);
                if (!toTrim.contains(encodeKey(mi, bi))) {
                    newBlocks.add(obj);
                    continue;
                }
                if (obj instanceof ContentBlock cb && "tool_result".equals(cb.getType())) {
                    String trimmed = formatTrimmedPreview(cb.getToolUseId(),
                            cb.getContent() != null ? cb.getContent().length() : 0,
                            cb.getContent());
                    ContentBlock replacement = ContentBlock.toolResult(
                            cb.getToolUseId(),
                            trimmed,
                            Boolean.TRUE.equals(cb.getIsError()),
                            cb.getErrorType());
                    newBlocks.add(replacement);
                    trimmedCount++;
                } else if (obj instanceof Map<?, ?> map && "tool_result".equals(map.get("type"))) {
                    String idVal = map.get("tool_use_id") != null ? map.get("tool_use_id").toString() : null;
                    Object contentVal = map.get("content");
                    String original0 = contentVal instanceof String s ? s
                            : (contentVal != null ? contentVal.toString() : "");
                    String trimmed = formatTrimmedPreview(idVal, original0.length(), original0);
                    boolean isError = Boolean.TRUE.equals(map.get("is_error"));
                    ContentBlock replacement = ContentBlock.toolResult(idVal, trimmed, isError);
                    newBlocks.add(replacement);
                    trimmedCount++;
                } else {
                    newBlocks.add(obj);
                }
            }
            Message copy = new Message();
            copy.setRole(original.getRole());
            copy.setContent(newBlocks);
            copy.setReasoningContent(original.getReasoningContent());
            out.add(copy);
        }

        return new Result(out, aggregate, retained, trimmedCount, totalCount);
    }

    /**
     * 生成 trimmed preview 文本。格式与持久化归档显式不同，避免 LLM 误以为可
     * 按 archive_id 取回原文。
     */
    private static String formatTrimmedPreview(String toolUseId, int originalChars, String original) {
        int budget = DEFAULT_TRIMMED_CHARS;
        String preview;
        if (original == null) {
            preview = "";
        } else if (original.length() <= budget) {
            preview = original;
        } else {
            int head = budget * 70 / 100;
            int tail = budget - head;
            preview = original.substring(0, head)
                    + "\n... [request-time trimmed] ...\n"
                    + original.substring(original.length() - tail);
        }
        return "[Tool result trimmed for request]\n"
                + "tool_use_id: " + (toolUseId != null ? toolUseId : "<unknown>") + "\n"
                + "original_chars: " + originalChars + "\n"
                + "retained_chars: " + preview.length() + "\n"
                + "reason: request_tool_result_budget\n"
                + "preview:\n"
                + preview;
    }

    private static long encodeKey(int mi, int bi) {
        return ((long) mi << 32) | (bi & 0xFFFFFFFFL);
    }

    private static final class Candidate {
        final int msgIndex;
        final int blockIndex;
        final int chars;
        final String toolUseId; // currently informational; reserved for future trace metadata.

        Candidate(int msgIndex, int blockIndex, int chars, String toolUseId) {
            this.msgIndex = msgIndex;
            this.blockIndex = blockIndex;
            this.chars = chars;
            this.toolUseId = toolUseId;
        }
    }
}
