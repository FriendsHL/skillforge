package com.skillforge.core.compact;

import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Light 压缩策略:纯 Java 规则, 无 LLM 调用。
 *
 * <p>规则(按顺序应用, 达到目标后停止):
 * <ol>
 *   <li>truncate-large-tool-output: tool_result.content > 5KB 截断头 10 行 + 尾 10 行</li>
 *   <li>dedup-consecutive-tools: 相邻两次 tool_use 同名同输入, 去掉较早的一对 (tool_use + tool_result)</li>
 *   <li>fold-failed-retries: 3+ 连续 is_error=true 的 tool_result 折叠为 1 条 (保留最后一次)</li>
 *   <li>drop-empty-assistant-narration: 纯过渡文本(< 80 字符、下一条是 tool_use)的 assistant 文本消息</li>
 * </ol>
 *
 * <p>不变量:
 * <ul>
 *   <li>tool_use 与对应 tool_result 必须同时保留或同时丢弃</li>
 *   <li>孤立的 tool_result 永远不允许存在</li>
 *   <li>消息顺序不变</li>
 *   <li>最后 {@link #PROTECTION_WINDOW} 条消息永不触碰</li>
 * </ul>
 */
public class LightCompactStrategy {

    private static final Logger log = LoggerFactory.getLogger(LightCompactStrategy.class);

    private final CompactableToolRegistry toolRegistry;

    public LightCompactStrategy() {
        this.toolRegistry = new CompactableToolRegistry();
    }

    public LightCompactStrategy(CompactableToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry != null ? toolRegistry : new CompactableToolRegistry();
    }

    public static final int PROTECTION_WINDOW = 5;
    public static final int LARGE_TOOL_OUTPUT_BYTES = 5 * 1024;
    public static final int TRUNCATE_HEAD_LINES = 10;
    public static final int TRUNCATE_TAIL_LINES = 10;
    public static final int NARRATION_MAX_CHARS = 80;

    /** 达到 20% reclaim 或 estTokens < 30% 即停止。 */
    private static final double TARGET_RECLAIM_RATIO = 0.20;
    private static final double TARGET_ABSOLUTE_RATIO = 0.30;

    public CompactResult apply(List<Message> messages, int contextWindowTokens) {
        return apply(messages, contextWindowTokens, this.toolRegistry);
    }

    /**
     * Apply light compaction with a per-call registry override (e.g. per-agent whitelist).
     */
    public CompactResult apply(List<Message> messages, int contextWindowTokens,
                               CompactableToolRegistry registry) {
        if (messages == null || messages.isEmpty()) {
            return new CompactResult(messages, 0, 0, 0, 0, new ArrayList<>());
        }
        CompactableToolRegistry effectiveRegistry = registry != null ? registry : this.toolRegistry;
        int beforeTokens = TokenEstimator.estimate(messages);
        int beforeCount = messages.size();
        List<String> applied = new ArrayList<>();

        // Deep clone 浅层:复制 list 引用 + 对 message 保持引用 (我们只会替换 message 或 block)
        List<Message> working = new ArrayList<>(messages);
        int protectFrom = Math.max(0, working.size() - PROTECTION_WINDOW);

        // Build toolUseId → toolName index for whitelist checking
        Map<String, String> toolUseIdToName = buildToolUseIdToNameIndex(working);

        // Rule 1: truncate large tool outputs (only for whitelisted tools)
        int truncated = truncateLargeToolOutputs(working, protectFrom, toolUseIdToName, effectiveRegistry);
        if (truncated > 0) {
            applied.add("truncate-large-tool-output");
        }

        if (stopEarly(working, beforeTokens, contextWindowTokens)) {
            return done(working, beforeTokens, beforeCount, applied);
        }

        // Rule 2: dedup consecutive identical tool calls
        // (protection window 在内部每次迭代重新计算, 不受 protectFrom 传参影响)
        int deduped = dedupConsecutiveTools(working);
        if (deduped > 0) {
            applied.add("dedup-consecutive-tools");
        }

        if (stopEarly(working, beforeTokens, contextWindowTokens)) {
            return done(working, beforeTokens, beforeCount, applied);
        }

        // Rule 3: fold consecutive error tool_results
        int folded = foldFailedRetries(working, Math.max(0, working.size() - PROTECTION_WINDOW));
        if (folded > 0) {
            applied.add("fold-failed-retries");
        }

        if (stopEarly(working, beforeTokens, contextWindowTokens)) {
            return done(working, beforeTokens, beforeCount, applied);
        }

        // Rule 4: drop empty assistant narration (protection window 在内部每次迭代重新计算)
        int dropped = dropEmptyNarration(working);
        if (dropped > 0) {
            applied.add("drop-empty-assistant-narration");
        }

        return done(working, beforeTokens, beforeCount, applied);
    }

    private CompactResult done(List<Message> working, int beforeTokens, int beforeCount, List<String> applied) {
        int afterTokens = TokenEstimator.estimate(working);
        return new CompactResult(working, beforeTokens, afterTokens, beforeCount, working.size(), applied);
    }

    /**
     * 判断是否已达到停止目标。
     * <p>两个条件任一满足即停止:
     * <ol>
     *   <li>有实际 reclaim 且 reclaim/before ≥ 20%</li>
     *   <li>now &lt; contextWindow × 30% 且 reclaim/before ≥ 20%</li>
     * </ol>
     * 没有实际 reclaim 时永不停止, 避免小对话一上来就被判定达标导致后续规则永远不跑。
     */
    private boolean stopEarly(List<Message> msgs, int beforeTokens, int contextWindowTokens) {
        if (beforeTokens <= 0) return false;
        int now = TokenEstimator.estimate(msgs);
        int reclaimed = beforeTokens - now;
        double reclaimRatio = (double) reclaimed / beforeTokens;
        if (reclaimRatio < TARGET_RECLAIM_RATIO) {
            // 没达到 reclaim 目标就继续跑后面的规则
            return false;
        }
        // 达到了 20% reclaim → 已经值回票价, 可以停
        return true;
    }

    // ==================== Index builder ====================

    /**
     * Scan all messages to build a toolUseId → toolName index.
     * Handles both {@link ContentBlock} and raw {@link Map} forms.
     */
    private Map<String, String> buildToolUseIdToNameIndex(List<Message> messages) {
        Map<String, String> index = new HashMap<>();
        for (Message m : messages) {
            if (!(m.getContent() instanceof List<?> blocks)) continue;
            for (Object o : blocks) {
                if (o instanceof ContentBlock cb) {
                    if ("tool_use".equals(cb.getType()) && cb.getId() != null && cb.getName() != null) {
                        index.put(cb.getId(), cb.getName());
                    }
                } else if (o instanceof Map<?, ?> map) {
                    if ("tool_use".equals(map.get("type"))) {
                        Object id = map.get("id");
                        Object name = map.get("name");
                        if (id instanceof String sid && name instanceof String sname) {
                            index.put(sid, sname);
                        }
                    }
                }
            }
        }
        return index;
    }

    // ==================== Rule 1 ====================

    private int truncateLargeToolOutputs(List<Message> working, int protectFrom,
                                         Map<String, String> toolUseIdToName,
                                         CompactableToolRegistry registry) {
        int count = 0;
        for (int i = 0; i < Math.min(working.size(), protectFrom); i++) {
            Message m = working.get(i);
            if (!(m.getContent() instanceof List<?> blocks)) continue;
            List<ContentBlock> newBlocks = null;
            for (int j = 0; j < blocks.size(); j++) {
                Object o = blocks.get(j);
                if (!(o instanceof ContentBlock cb)) continue;
                if (!"tool_result".equals(cb.getType())) continue;
                // Check whitelist: skip non-compactable tools
                String toolName = toolUseIdToName.get(cb.getToolUseId());
                if (!registry.isCompactable(toolName)) continue;
                String content = cb.getContent();
                if (content == null) continue;
                int byteLen = content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                if (byteLen <= LARGE_TOOL_OUTPUT_BYTES) continue;

                String truncated = truncateLines(content, byteLen);
                ContentBlock replaced = ContentBlock.toolResult(cb.getToolUseId(), truncated,
                        Boolean.TRUE.equals(cb.getIsError()));
                if (newBlocks == null) {
                    newBlocks = new ArrayList<>();
                    for (int k = 0; k < blocks.size(); k++) {
                        Object ok = blocks.get(k);
                        if (ok instanceof ContentBlock cbk) newBlocks.add(cbk);
                    }
                }
                newBlocks.set(j, replaced);
                count++;
            }
            if (newBlocks != null) {
                Message replacement = new Message();
                replacement.setRole(m.getRole());
                replacement.setContent(newBlocks);
                working.set(i, replacement);
            }
        }
        return count;
    }

    private String truncateLines(String content, int originalBytes) {
        String[] lines = content.split("\n", -1);
        if (lines.length <= TRUNCATE_HEAD_LINES + TRUNCATE_TAIL_LINES) {
            // 不够行数折半即可:保持头尾各一半
            return content;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < TRUNCATE_HEAD_LINES; i++) {
            sb.append(lines[i]).append("\n");
        }
        int droppedLines = lines.length - TRUNCATE_HEAD_LINES - TRUNCATE_TAIL_LINES;
        sb.append("\n... [truncated ").append(droppedLines)
          .append(" lines, original ").append(originalBytes).append(" bytes] ...\n\n");
        for (int i = lines.length - TRUNCATE_TAIL_LINES; i < lines.length; i++) {
            sb.append(lines[i]);
            if (i < lines.length - 1) sb.append("\n");
        }
        return sb.toString();
    }

    // ==================== Rule 2 ====================

    /**
     * 相邻两次 tool_use (同名同输入) 去掉较早的那对。
     * 实现为单 assistant 消息携带单 tool_use + 随后 user/tool_result 的简化模型:
     * 把 (assistant-with-tool_use, user-with-tool_result) 视为一对。
     *
     * 只在 assistant 消息只含单个 tool_use (无 text 块) 且后一条 user 只含单个 tool_result 时才干预,
     * 避免误删多 tool_use 批次场景。
     */
    private int dedupConsecutiveTools(List<Message> working) {
        int removed = 0;
        // 滑动窗口: pair i-1 (i-3, i-2) 和 pair i (i-1, i) 比较
        // 每次命中后 i 回退, 继续检测新对齐
        // CRITICAL: 每次迭代都要重新计算 protection 边界, 因为 working.size() 会随 remove 变化,
        // 不能在循环开始前一次性捕获 protectFromExclusive
        int i = 3;
        while (true) {
            int protectFromExclusive = Math.max(0, working.size() - PROTECTION_WINDOW);
            if (i >= Math.min(working.size(), protectFromExclusive)) break;
            Message aOlder = working.get(i - 3);
            Message bOlder = working.get(i - 2);
            Message aNewer = working.get(i - 1);
            Message bNewer = working.get(i);
            SingleToolPair older = asSinglePair(aOlder, bOlder);
            SingleToolPair newer = asSinglePair(aNewer, bNewer);
            if (older != null && newer != null && older.sameCallAs(newer)) {
                // 移除较早的一对 (i-3, i-2)
                working.remove(i - 3);
                working.remove(i - 3);
                removed++;
                // i 左移 2, 但需要 >= 3 才能继续
                i = Math.max(3, i - 1);
                continue;
            }
            i++;
        }
        return removed;
    }

    private SingleToolPair asSinglePair(Message a, Message b) {
        if (a == null || b == null) return null;
        if (a.getRole() != Message.Role.ASSISTANT) return null;
        if (b.getRole() != Message.Role.USER) return null;
        if (!(a.getContent() instanceof List<?> aBlocks)) return null;
        if (!(b.getContent() instanceof List<?> bBlocks)) return null;
        ContentBlock toolUse = null;
        boolean extraText = false;
        for (Object o : aBlocks) {
            if (!(o instanceof ContentBlock cb)) return null;
            if ("tool_use".equals(cb.getType())) {
                if (toolUse != null) return null; // 多个 tool_use 不处理
                toolUse = cb;
            } else if ("text".equals(cb.getType())) {
                String txt = cb.getText();
                if (txt != null && !txt.isBlank()) extraText = true;
            }
        }
        if (toolUse == null) return null;
        if (extraText) return null; // 保守:带实质 text 时不删
        ContentBlock toolResult = null;
        for (Object o : bBlocks) {
            if (!(o instanceof ContentBlock cb)) return null;
            if ("tool_result".equals(cb.getType())) {
                if (toolResult != null) return null;
                toolResult = cb;
            } else {
                return null;
            }
        }
        if (toolResult == null) return null;
        if (toolResult.getToolUseId() == null || !toolResult.getToolUseId().equals(toolUse.getId())) {
            return null;
        }
        return new SingleToolPair(toolUse);
    }

    private static class SingleToolPair {
        final String name;
        final String inputHash;
        SingleToolPair(ContentBlock toolUse) {
            this.name = toolUse.getName() != null ? toolUse.getName() : "";
            this.inputHash = toolUse.getInput() != null
                    ? Integer.toHexString(toolUse.getInput().toString().hashCode())
                    : "0";
        }
        boolean sameCallAs(SingleToolPair other) {
            return name.equals(other.name) && inputHash.equals(other.inputHash);
        }
    }

    // ==================== Rule 3 ====================

    /**
     * 3+ 连续 is_error=true 的 tool_result 折叠。
     * "连续"定义: 在 tool-call 序列中相邻 (可以跨越中间的 assistant tool_use 消息)。
     * 只修改 tool_result 那一侧 (role=user 的消息); 配对的 tool_use 保持不变以维护配对不变量。
     */
    private int foldFailedRetries(List<Message> working, int protectFromExclusive) {
        // 收集所有 "纯 error tool_result" 消息的索引 (只在保护窗口外)
        List<Integer> errorIndices = new ArrayList<>();
        int bound = Math.min(working.size(), protectFromExclusive);
        int folded = 0;
        for (int i = 0; i < bound; i++) {
            Message m = working.get(i);
            if (isAllErrorToolResultMessage(m)) {
                errorIndices.add(i);
            } else if (!isAssistantToolUseOnly(m)) {
                if (errorIndices.size() >= 3) {
                    folded += foldErrorIndices(working, errorIndices);
                }
                errorIndices.clear();
            }
        }
        if (errorIndices.size() >= 3) {
            folded += foldErrorIndices(working, errorIndices);
        }
        return folded;
    }

    /** 判断是否是纯 assistant tool_use 消息 (允许带 text 块)。 */
    private boolean isAssistantToolUseOnly(Message m) {
        if (m.getRole() != Message.Role.ASSISTANT) return false;
        if (!(m.getContent() instanceof List<?> blocks)) return false;
        boolean hasToolUse = false;
        for (Object o : blocks) {
            if (!(o instanceof ContentBlock cb)) return false;
            if ("tool_use".equals(cb.getType())) hasToolUse = true;
            else if (!"text".equals(cb.getType())) return false;
        }
        return hasToolUse;
    }

    private int foldErrorIndices(List<Message> working, List<Integer> errorIndices) {
        int total = errorIndices.size();
        int lastIdx = errorIndices.get(total - 1);
        Message lastError = working.get(lastIdx);
        String lastErrSummary = extractFirstErrorText(lastError);
        String trimmedLast = lastErrSummary.length() > 200
                ? lastErrSummary.substring(0, 200) + "…" : lastErrSummary;
        int folded = 0;
        for (int k = 0; k < total - 1; k++) {
            int idx = errorIndices.get(k);
            Message original = working.get(idx);
            Message replaced = foldedErrorMessage(original, trimmedLast, total);
            working.set(idx, replaced);
            folded++;
        }
        return folded;
    }

    private boolean isAllErrorToolResultMessage(Message m) {
        if (m.getRole() != Message.Role.USER) return false;
        if (!(m.getContent() instanceof List<?> blocks)) return false;
        if (blocks.isEmpty()) return false;
        for (Object o : blocks) {
            if (!(o instanceof ContentBlock cb)) return false;
            if (!"tool_result".equals(cb.getType())) return false;
            if (!Boolean.TRUE.equals(cb.getIsError())) return false;
        }
        return true;
    }

    private String extractFirstErrorText(Message m) {
        if (!(m.getContent() instanceof List<?> blocks)) return "";
        for (Object o : blocks) {
            if (o instanceof ContentBlock cb && "tool_result".equals(cb.getType())) {
                return cb.getContent() != null ? cb.getContent() : "";
            }
        }
        return "";
    }

    private Message foldedErrorMessage(Message original, String lastErrSummary, int total) {
        List<ContentBlock> newBlocks = new ArrayList<>();
        if (original.getContent() instanceof List<?> blocks) {
            for (Object o : blocks) {
                if (o instanceof ContentBlock cb && "tool_result".equals(cb.getType())) {
                    newBlocks.add(ContentBlock.toolResult(cb.getToolUseId(),
                            "[folded] " + total + " retries failed. Last error: " + lastErrSummary,
                            true));
                }
            }
        }
        Message replacement = new Message();
        replacement.setRole(Message.Role.USER);
        replacement.setContent(newBlocks);
        return replacement;
    }

    // ==================== Rule 4 ====================

    private int dropEmptyNarration(List<Message> working) {
        int dropped = 0;
        // 从前往后, 若命中就 remove 并保持 i 不变
        // 每次迭代重新计算 protection 边界 (working.size() 会随 remove 减小)
        int i = 0;
        while (true) {
            int protectFromExclusive = Math.max(0, working.size() - PROTECTION_WINDOW);
            if (i >= Math.min(working.size() - 1, protectFromExclusive)) break;
            Message m = working.get(i);
            Message next = working.get(i + 1);
            if (isNarrationAssistant(m) && nextIsToolUseAssistant(next)) {
                working.remove(i);
                dropped++;
                continue;
            }
            i++;
        }
        return dropped;
    }

    private boolean isNarrationAssistant(Message m) {
        if (m.getRole() != Message.Role.ASSISTANT) return false;
        String text;
        Object content = m.getContent();
        if (content instanceof String s) {
            text = s;
        } else if (content instanceof List<?> blocks) {
            // 只认全是 text block 且无 tool_use
            StringBuilder sb = new StringBuilder();
            for (Object o : blocks) {
                if (!(o instanceof ContentBlock cb)) return false;
                if ("tool_use".equals(cb.getType())) return false;
                if ("text".equals(cb.getType()) && cb.getText() != null) {
                    sb.append(cb.getText());
                }
            }
            text = sb.toString();
        } else {
            return false;
        }
        if (text == null) return false;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return false;
        if (trimmed.length() >= NARRATION_MAX_CHARS) return false;
        // 过渡语特征
        return NARRATION_PREFIXES.stream().anyMatch(trimmed::startsWith);
    }

    private static final Set<String> NARRATION_PREFIXES = new HashSet<>(List.of(
            "Let me", "let me", "I'll", "I will", "Now I", "Next I", "OK,", "Ok,", "Okay,",
            "接下来", "我来", "好的", "让我", "现在我", "下面我", "我先", "我将"
    ));

    private boolean nextIsToolUseAssistant(Message next) {
        if (next.getRole() != Message.Role.ASSISTANT) return false;
        if (!(next.getContent() instanceof List<?> blocks)) return false;
        for (Object o : blocks) {
            if (o instanceof ContentBlock cb && "tool_use".equals(cb.getType())) return true;
        }
        return false;
    }
}
