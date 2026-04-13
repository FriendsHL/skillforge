package com.skillforge.core.compact;

import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmRequest;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Full 压缩策略:LLM 驱动的总结式压缩。
 * <p>保留最后 {@link #YOUNG_GEN_KEEP} 条为 "young generation", 其前面的消息作为压缩窗口;
 * 边界必须落在安全位置(不得切割 tool_use↔tool_result 配对)。
 * <p>如果默认 young-gen 下找不到安全边界, 会向右"扩大 young-gen"重试, 直到找到
 * 安全点或 young-gen 覆盖整条历史为止(此时返回 no-op)。
 */
public class FullCompactStrategy {

    private static final Logger log = LoggerFactory.getLogger(FullCompactStrategy.class);

    public static final int YOUNG_GEN_KEEP = 20;
    public static final int MAX_SUMMARY_TOKENS = 800;

    private static final String SUMMARY_SYSTEM_PROMPT =
            "You are a conversation compressor. Your job is to summarize the provided conversation " +
            "history while strictly preserving: " +
            "(a) the user's original intent and requirements, " +
            "(b) completed milestones and work products, " +
            "(c) key facts learned from tool executions, " +
            "(d) any open questions or unresolved items, " +
            "(e) agreed-upon next steps. " +
            "Do NOT invent information. Keep the summary under 800 tokens. " +
            "Output only the summary text, without preface or quotation marks.\n\n" +
            "IMPORTANT identity preservation rules:\n" +
            "- Preserve all opaque identifiers exactly as written (no shortening or reconstruction), " +
            "including UUIDs, hashes, IDs, tokens, API keys, hostnames, IPs, ports, URLs, and file paths.\n\n" +
            "MUST preserve in summary:\n" +
            "- Active tasks and their current status (in-progress, blocked, pending)\n" +
            "- Batch operation progress (e.g., '5/17 items completed')\n" +
            "- The last thing the user requested and what was being done about it\n" +
            "- Decisions made and their rationale\n" +
            "- TODOs, open questions, and constraints\n" +
            "- Any commitments or follow-ups promised\n" +
            "Prioritize recent context over older history.";

    public CompactResult apply(List<Message> messages, int contextWindowTokens,
                               LlmProvider provider, String modelId) {
        if (messages == null || messages.size() <= YOUNG_GEN_KEEP) {
            int tokens = TokenEstimator.estimate(messages);
            return new CompactResult(messages, tokens, tokens,
                    messages == null ? 0 : messages.size(),
                    messages == null ? 0 : messages.size(),
                    new ArrayList<>());
        }

        int beforeTokens = TokenEstimator.estimate(messages);
        int beforeCount = messages.size();

        // 初始 young-gen = YOUNG_GEN_KEEP, 如果边界不安全就扩大 young-gen 重试
        int youngGenKeep = YOUNG_GEN_KEEP;
        int rightEdge = -1;
        while (youngGenKeep < messages.size()) {
            int initial = messages.size() - youngGenKeep;
            int candidate = findSafeBoundary(messages, initial);
            if (candidate > 0) {
                rightEdge = candidate;
                break;
            }
            // 向右扩大 young-gen, 相当于把"压缩窗口"右边界向左一格
            youngGenKeep++;
        }
        if (rightEdge <= 0) {
            log.info("FullCompactStrategy: no safe boundary even after growing young-gen, no-op");
            return new CompactResult(messages, beforeTokens, beforeTokens, beforeCount, beforeCount,
                    new ArrayList<>());
        }

        List<Message> window = messages.subList(0, rightEdge);
        List<Message> youngGen = messages.subList(rightEdge, messages.size());

        String windowSerialized = serializeWindow(window);
        String summary = callLlm(provider, modelId, windowSerialized);
        if (summary == null || summary.isBlank()) {
            log.warn("FullCompactStrategy: LLM returned empty summary, returning original");
            return new CompactResult(messages, beforeTokens, beforeTokens, beforeCount, beforeCount,
                    new ArrayList<>());
        }

        String summaryPrefix = "[Context summary from " + window.size() + " messages compacted at "
                + Instant.now() + "]\n" + summary.trim();

        List<Message> compacted = new ArrayList<>(youngGen.size() + 1);

        // 防"两条连续 user"消息: 若 young-gen 第一条是 user, 把摘要合并到它前面而不是单独插一条;
        // 否则正常插入为 user 消息 (因为是"之前对话的背景"角色上契合 user).
        if (!youngGen.isEmpty() && youngGen.get(0).getRole() == Message.Role.USER) {
            Message originalFirst = youngGen.get(0);
            Message merged = mergeSummaryIntoUser(originalFirst, summaryPrefix);
            compacted.add(merged);
            for (int i = 1; i < youngGen.size(); i++) {
                compacted.add(youngGen.get(i));
            }
        } else {
            Message synthetic = Message.user(summaryPrefix);
            compacted.add(synthetic);
            compacted.addAll(youngGen);
        }

        int afterTokens = TokenEstimator.estimate(compacted);
        List<String> applied = new ArrayList<>();
        applied.add("llm-summary");
        return new CompactResult(compacted, beforeTokens, afterTokens, beforeCount, compacted.size(), applied);
    }

    /**
     * 把摘要作为前缀合并到 young-gen 第一条 user 消息里, 避免连续两条 user role 触发
     * Anthropic/Gemini 的 invalid payload 校验。
     */
    @SuppressWarnings("unchecked")
    private Message mergeSummaryIntoUser(Message originalFirst, String summaryPrefix) {
        Object content = originalFirst.getContent();
        Message merged = new Message();
        merged.setRole(Message.Role.USER);
        if (content instanceof String s) {
            merged.setContent(summaryPrefix + "\n\n---\n\n" + s);
        } else if (content instanceof List<?> blocks) {
            // tool_result 形态的 user 消息: 不方便 inline prefix, 改成插入一个 text 块在最前
            List<Object> newBlocks = new ArrayList<>();
            newBlocks.add(ContentBlock.text(summaryPrefix));
            newBlocks.addAll((List<Object>) blocks);
            merged.setContent(newBlocks);
        } else {
            merged.setContent(summaryPrefix);
        }
        return merged;
    }

    /**
     * 向左寻找一个不会切割 tool_use/tool_result 配对的边界。
     * 优先落在 user 消息后或无 open tool_use 的 assistant text 后。
     */
    int findSafeBoundary(List<Message> messages, int initial) {
        int idx = Math.min(initial, messages.size());
        while (idx > 0) {
            if (isBoundarySafe(messages, idx)) {
                return idx;
            }
            idx--;
        }
        return 0;
    }

    /**
     * 判断 prefix [0, idx) 是否所有 tool_use 都已在 prefix 内被配对上.
     * <p>支持两种 block 形态: 反序列化后的 {@link ContentBlock} 对象 和 Jackson 读 messagesJson
     * 时产生的原始 {@code Map<String, Object>}. 如果只识别 ContentBlock 会让 Map-形式的所有
     * 边界被误判为"无 open pair", 导致静默切割配对.
     */
    private boolean isBoundarySafe(List<Message> messages, int idx) {
        java.util.Set<String> openToolUseIds = new java.util.HashSet<>();
        for (int i = 0; i < idx; i++) {
            Message m = messages.get(i);
            if (!(m.getContent() instanceof List<?> blocks)) continue;
            for (Object o : blocks) {
                if (o instanceof ContentBlock cb) {
                    if ("tool_use".equals(cb.getType()) && cb.getId() != null) {
                        openToolUseIds.add(cb.getId());
                    } else if ("tool_result".equals(cb.getType()) && cb.getToolUseId() != null) {
                        openToolUseIds.remove(cb.getToolUseId());
                    }
                } else if (o instanceof Map<?, ?> mm) {
                    Object type = mm.get("type");
                    if (type == null) continue;
                    if ("tool_use".equals(type.toString())) {
                        Object id = mm.get("id");
                        if (id != null) openToolUseIds.add(id.toString());
                    } else if ("tool_result".equals(type.toString())) {
                        Object tuid = mm.get("tool_use_id");
                        if (tuid == null) tuid = mm.get("toolUseId");
                        if (tuid != null) openToolUseIds.remove(tuid.toString());
                    }
                }
            }
        }
        return openToolUseIds.isEmpty();
    }

    private String serializeWindow(List<Message> window) {
        StringBuilder sb = new StringBuilder();
        for (Message m : window) {
            String role = m.getRole() != null ? m.getRole().name().toLowerCase() : "unknown";
            sb.append("[").append(role).append("] ");
            Object content = m.getContent();
            if (content instanceof String s) {
                sb.append(s);
            } else if (content instanceof List<?> blocks) {
                for (Object o : blocks) {
                    if (o instanceof ContentBlock cb) {
                        if ("text".equals(cb.getType()) && cb.getText() != null) {
                            sb.append(cb.getText()).append(" ");
                        } else if ("tool_use".equals(cb.getType())) {
                            sb.append("<tool_use name=").append(cb.getName()).append("> ");
                        } else if ("tool_result".equals(cb.getType())) {
                            String c = cb.getContent() != null ? cb.getContent() : "";
                            if (c.length() > 500) c = c.substring(0, 500) + "…";
                            sb.append("<tool_result ")
                              .append(Boolean.TRUE.equals(cb.getIsError()) ? "error=true" : "")
                              .append("> ").append(c).append(" ");
                        }
                    } else if (o instanceof Map<?, ?> mm) {
                        Object type = mm.get("type");
                        if ("text".equals(String.valueOf(type)) && mm.get("text") != null) {
                            sb.append(mm.get("text")).append(" ");
                        } else if ("tool_use".equals(String.valueOf(type))) {
                            sb.append("<tool_use name=").append(mm.get("name")).append("> ");
                        } else if ("tool_result".equals(String.valueOf(type))) {
                            Object c = mm.get("content");
                            String cs = c != null ? c.toString() : "";
                            if (cs.length() > 500) cs = cs.substring(0, 500) + "…";
                            sb.append("<tool_result> ").append(cs).append(" ");
                        }
                    }
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String callLlm(LlmProvider provider, String modelId, String windowText) {
        try {
            LlmRequest req = new LlmRequest();
            req.setSystemPrompt(SUMMARY_SYSTEM_PROMPT);
            req.setMessages(Collections.singletonList(
                    Message.user("Please summarize the following conversation history:\n\n" + windowText)));
            req.setModel(modelId);
            req.setMaxTokens(MAX_SUMMARY_TOKENS + 200);
            req.setTemperature(0.2);
            LlmResponse resp = provider.chat(req);
            return resp != null ? resp.getContent() : null;
        } catch (Exception e) {
            log.error("FullCompactStrategy: LLM summarization call failed", e);
            return null;
        }
    }
}
