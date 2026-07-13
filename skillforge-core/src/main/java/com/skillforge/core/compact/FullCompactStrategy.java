package com.skillforge.core.compact;

import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmRequest;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.core.engine.AssistantAttachmentRefSanitizer;
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
 *
 * <p>3-phase support: use {@link #prepareCompact} (Phase 1, pure Java, under stripe lock)
 * followed by {@link #applyPrepared} (Phase 2, LLM call, outside stripe lock) to avoid
 * holding the stripe lock during the blocking LLM call.
 */
public class FullCompactStrategy {

    /**
     * Snapshot produced by Phase 1 boundary detection — no LLM involvement.
     * Passed from Phase 1 (under stripe lock) to Phase 2 (LLM call, lock released).
     */
    public record PreparedCompact(
            int rightEdge,
            List<Message> window,
            List<Message> youngGen,
            int beforeTokens,
            int beforeCount,
            int contextWindowTokens
    ) {}

    private static final Logger log = LoggerFactory.getLogger(FullCompactStrategy.class);

    public static final int YOUNG_GEN_KEEP = 20;
    /**
     * Output token cap for the summary LLM call. The structured CC-aligned template (10 sections,
     * incl. verbatim "All User Messages" + "Pending Tool Calls") needs more room than the old
     * free-form prose summary. The rolling-merge keeps only ONE active summary message in the
     * compacted history, so the extra output tokens are bounded (not accumulated per compaction).
     * <p>Two bounds at play: the prompt asks for a SOFT target (~1800 tokens), while the HARD bound
     * is the API {@code maxTokens} ({@link #MAX_SUMMARY_TOKENS} + 200 = 2200; see callLlm). Section
     * 10 (Pending Tool Calls / INV-1, the verbatim pending-tool-use args) sits LAST, so the prompt's
     * "if space is tight prioritize recent context and sections 6–10" instruction is the guard
     * against a truncated output losing the pending-tool payload the agent needs to retry.
     */
    public static final int MAX_SUMMARY_TOKENS = 2000;

    /**
     * COMPACT-IDEMPOTENCY-FIX ③: reserve this many tokens for the system prompt + output budget +
     * safety margin when deciding the per-call input budget for the summary LLM call. Subtracted
     * from the model's context window to get the max window text size a single summary call may
     * receive before map-reduce chunking kicks in.
     * <p>Breakdown: structured CC-aligned system prompt (~950 tokens) + output cap
     * ({@link #MAX_SUMMARY_TOKENS} + 200 = 2200) + ~1500-token safety buffer ≈ 5500. Raised from
     * 4000 because the structured template grew the system prompt; at 4000 the headroom had dropped
     * to ~850, too tight.
     */
    static final int SUMMARY_INPUT_RESERVE_TOKENS = 5_500;

    /**
     * Fallback per-call input budget (tokens) used when the model context window is unknown / not
     * positive. Conservative so an over-large window never blows the model context once ②
     * lets the compactable window grow on tool-heavy sessions.
     */
    static final int SUMMARY_INPUT_FALLBACK_BUDGET_TOKENS = 24_000;

    private static final String SUMMARY_SYSTEM_PROMPT =
            "You are a conversation compressor. Produce a STRUCTURED summary of the conversation so " +
            "the agent can seamlessly continue after the older turns are dropped. Write the summary " +
            "in the conversation's primary language. Do NOT invent information.\n\n" +
            "Output EXACTLY these numbered sections, each with its header, in order. If a section " +
            "genuinely has no content, keep the header followed by \"—\".\n\n" +
            "## 1. Primary Request and Intent\n" +
            "All of the user's explicit requests and the underlying intent, in chronological detail.\n\n" +
            "## 2. Key Concepts and Facts\n" +
            "Important technical concepts, tools, systems, and facts learned from tool executions.\n\n" +
            "## 3. Files and Resources\n" +
            "Files / resources / data examined, created, or modified, with WHY each matters and key " +
            "snippets. Preserve paths verbatim.\n\n" +
            "## 4. Errors and Fixes\n" +
            "Errors encountered, how each was fixed, and any user feedback or correction on the fix.\n\n" +
            "## 5. Problem Solving\n" +
            "Problems solved and ongoing troubleshooting or analysis.\n\n" +
            "## 6. All User Messages\n" +
            "EVERY non-tool-result user message, verbatim (condense only if a single message is very " +
            "long). This is the primary safeguard against intent drift — do not drop any.\n\n" +
            "## 7. Pending Tasks\n" +
            "Explicitly requested work not yet completed.\n\n" +
            "## 8. Current Work\n" +
            "Precisely what was being worked on immediately before this summary (file names, commands, " +
            "state).\n\n" +
            "## 9. Next Step\n" +
            "The next step, tightly tied to the most recent work, with a short quote of the relevant " +
            "user or assistant line.\n\n" +
            "## 10. Pending Tool Calls\n" +
            "For any tool_use that was emitted but did NOT yet return a tool_result: preserve the tool " +
            "name AND the COMPLETE input arguments VERBATIM. Do NOT summarize file contents, scripts, " +
            "paths, or string literals inside Write / Edit / Bash inputs — keep them byte-for-byte. The " +
            "agent needs the full payload to retry the call after compaction.\n\n" +
            "Identity preservation: keep ALL opaque identifiers exactly as written (no shortening or " +
            "reconstruction) — UUIDs, hashes, IDs, tokens, API keys, hostnames, IPs, ports, URLs, file " +
            "paths.\n\n" +
            "Output only the structured summary, without preface or quotation marks. Target under " +
            "~1800 tokens; if space is tight prioritize recent context and sections 6–10.";

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
        String summary = summarizeWithWindowGuard(provider, modelId, windowSerialized,
                contextWindowTokens);
        if (summary == null || summary.isBlank()) {
            log.warn("FullCompactStrategy: LLM returned empty summary, returning original");
            return new CompactResult(messages, beforeTokens, beforeTokens, beforeCount, beforeCount,
                    new ArrayList<>());
        }

        String summaryPrefix = "[Context summary from " + window.size() + " messages compacted at "
                + Instant.now() + "]\n" + summary.trim();

        // Summary is always emitted as a *standalone* user message followed by the
        // unmodified young-gen (matches Claude Code / OpenClaw layout). Modern
        // Claude / OpenAI / DeepSeek accept consecutive user messages.
        List<Message> compacted = new ArrayList<>(youngGen.size() + 1);
        compacted.add(Message.user(summaryPrefix));
        compacted.addAll(youngGen);

        int afterTokens = TokenEstimator.estimate(compacted);
        List<String> applied = new ArrayList<>();
        applied.add("llm-summary");
        return new CompactResult(compacted, beforeTokens, afterTokens, beforeCount, compacted.size(), applied);
    }

    // ── 3-phase API ──────────────────────────────────────────────────────────

    /**
     * Phase 1: boundary detection only, no LLM. Returns {@code null} if history is too
     * short or no safe boundary is found (caller should treat as no-op).
     * <p>This method is pure Java and safe to call under the stripe lock.
     */
    public PreparedCompact prepareCompact(List<Message> messages, int contextWindowTokens) {
        if (messages == null || messages.size() <= YOUNG_GEN_KEEP) {
            return null;
        }
        int beforeTokens = TokenEstimator.estimate(messages);
        int beforeCount = messages.size();
        int youngGenKeep = YOUNG_GEN_KEEP;
        int rightEdge = -1;
        while (youngGenKeep < messages.size()) {
            int initial = messages.size() - youngGenKeep;
            int candidate = findSafeBoundary(messages, initial);
            if (candidate > 0) {
                rightEdge = candidate;
                break;
            }
            youngGenKeep++;
        }
        if (rightEdge <= 0) {
            log.info("FullCompactStrategy.prepareCompact: no safe boundary, returning null (no-op)");
            return null;
        }
        List<Message> window = new ArrayList<>(messages.subList(0, rightEdge));
        List<Message> youngGen = new ArrayList<>(messages.subList(rightEdge, messages.size()));
        return new PreparedCompact(rightEdge, window, youngGen, beforeTokens, beforeCount,
                contextWindowTokens);
    }

    /**
     * Phase 2: LLM call + result assembly. Takes the snapshot from {@link #prepareCompact}.
     * Returns {@code null} if the LLM returns empty (caller should treat as no-op).
     * <p>This method blocks on the LLM call and must be called outside the stripe lock.
     */
    public CompactResult applyPrepared(PreparedCompact prep, LlmProvider provider, String modelId) {
        return applyPrepared(prep, provider, modelId, null);
    }

    /**
     * INCREMENTAL-SUMMARY overload: when {@code priorSummary} is non-blank (range-model rolling
     * merge), the LLM is asked to EXTEND the existing summary with the new turns rather than
     * re-summarize the whole window from scratch — this stops the "summary of a summary" drift that
     * loses task state across repeated compactions.
     *
     * <p>Under the range model the window's first message is itself the prior summary (the derived
     * model view injects {@code Message.user(activeSummaryText)} as the head). Feeding it again
     * inside the serialized window would double it, so the leading window message is stripped when it
     * is a String-content USER message whose text equals {@code priorSummary} (the same exact-text
     * match the reconciliation path uses). The prior summary is then handed to the LLM as the labeled
     * existing summary instead.
     */
    public CompactResult applyPrepared(PreparedCompact prep, LlmProvider provider, String modelId,
                                       String priorSummary) {
        boolean incremental = priorSummary != null && !priorSummary.isBlank();
        List<Message> windowForSummary = incremental
                ? stripLeadingPriorSummary(prep.window(), priorSummary)
                : prep.window();
        String windowSerialized = serializeWindow(windowForSummary);
        String summary = incremental
                ? summarizeIncremental(provider, modelId, priorSummary, windowSerialized,
                        prep.contextWindowTokens())
                : summarizeWithWindowGuard(provider, modelId, windowSerialized,
                        prep.contextWindowTokens());
        if (summary == null || summary.isBlank()) {
            log.warn("FullCompactStrategy.applyPrepared: LLM returned empty summary, no-op");
            return null;
        }
        String summaryPrefix = "[Context summary from " + prep.window().size()
                + " messages compacted at " + Instant.now() + "]\n" + summary.trim();
        // Same standalone-summary layout as apply().
        List<Message> compacted = new ArrayList<>(prep.youngGen().size() + 1);
        compacted.add(Message.user(summaryPrefix));
        compacted.addAll(prep.youngGen());
        int afterTokens = TokenEstimator.estimate(compacted);
        List<String> applied = new ArrayList<>();
        applied.add("llm-summary");
        return new CompactResult(compacted, prep.beforeTokens(), afterTokens,
                prep.beforeCount(), compacted.size(), applied);
    }

    /**
     * Return a copy of {@code window} with the leading message removed when it is a String-content
     * USER message whose text equals {@code priorSummary} (the injected active-summary head under the
     * range model). Otherwise return {@code window} unchanged — never throws, never mutates the input.
     *
     * <p>Relies on EXACT text equality: the derived model view injects the head as
     * {@code Message.user(activeSummary.getSummaryText())} and {@code priorSummary} is that same
     * persisted {@code summaryText} (no reminder wrapping, no whitespace transform on the summary
     * message), so {@code s.equals(priorSummary)} matches the head byte-for-byte. If they ever
     * diverged, this would simply not strip (the prior summary would appear once in the window text
     * too — verbose, never lossy).
     */
    private List<Message> stripLeadingPriorSummary(List<Message> window, String priorSummary) {
        if (window == null || window.isEmpty()) {
            return window;
        }
        Message first = window.get(0);
        if (first != null && first.getRole() == Message.Role.USER
                && first.getContent() instanceof String s && s.equals(priorSummary)) {
            return new ArrayList<>(window.subList(1, window.size()));
        }
        return window;
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * 向左寻找一个不会切割 tool_use/tool_result 配对的边界。
     * 优先落在 user 消息后或无 open tool_use 的 assistant text 后。
     */
    public int findSafeBoundary(List<Message> messages, int initial) {
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
                        } else {
                            String attachmentText = attachmentText(m, cb);
                            if (attachmentText != null) sb.append(attachmentText).append(" ");
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
                        } else {
                            String attachmentText = attachmentText(m, mm);
                            if (attachmentText != null) sb.append(attachmentText).append(" ");
                        }
                    }
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String attachmentText(Message message, Object block) {
        if (message.getRole() == Message.Role.ASSISTANT) {
            return AssistantAttachmentRefSanitizer.placeholderText(block);
        }
        Message singleBlock = new Message();
        singleBlock.setRole(message.getRole());
        singleBlock.setContent(List.of(block));
        String text = singleBlock.getTextContent();
        return text.isEmpty() ? null : text;
    }

    /** Max map-reduce reduce-recursion depth before accepting an over-budget single call (#2 guard). */
    static final int MAX_SUMMARY_REDUCE_DEPTH = 3;

    /**
     * COMPACT-IDEMPOTENCY-FIX ③: window-aware summarization. Estimates the input token size of
     * {@code windowText}; if it fits the per-call budget (model context window − reserve), summarize
     * in one call. Otherwise map-reduce: split the window text into budget-sized chunks, summarize
     * each, then summarize the concatenation of the chunk summaries. This prevents a
     * context-length-exceeded failure once ② lets the compactable window grow on tool-heavy
     * sessions. The single-call path is byte-for-byte the prior behavior for normal-sized windows.
     */
    public String summarizeWithWindowGuard(LlmProvider provider, String modelId, String windowText,
                                           int contextWindowTokens) {
        return summarizeWithWindowGuard(provider, modelId, windowText, contextWindowTokens, 0);
    }

    /**
     * {@code depth} bounds the reduce recursion (#2): each time the concatenated chunk summaries are
     * themselves over budget we recurse once more, but never beyond {@link #MAX_SUMMARY_REDUCE_DEPTH}.
     * On exhaustion we make a single best-effort call with the over-budget text (let the provider
     * truncate) rather than recursing forever — guards against an LLM that returns near-verbatim
     * partials so {@code combined} never shrinks.
     */
    private String summarizeWithWindowGuard(LlmProvider provider, String modelId, String windowText,
                                            int contextWindowTokens, int depth) {
        if (windowText == null || windowText.isBlank()) {
            return null;
        }
        int perCallBudget = (contextWindowTokens > 0)
                ? contextWindowTokens - SUMMARY_INPUT_RESERVE_TOKENS
                : SUMMARY_INPUT_FALLBACK_BUDGET_TOKENS;
        if (perCallBudget < 1_000) {
            // window − reserve underflowed (pathologically small / unknown window): use the
            // conservative fallback budget so we still chunk sensibly. (#5a: the prior
            // Math.max(1_000, FALLBACK) was dead — FALLBACK is always the larger value.)
            perCallBudget = SUMMARY_INPUT_FALLBACK_BUDGET_TOKENS;
        }
        int estTokens = TokenEstimator.estimateString(windowText);
        if (estTokens <= perCallBudget) {
            return callLlm(provider, modelId, windowText);
        }

        // Map phase: chunk and summarize each chunk.
        List<String> chunks = chunkByBudget(windowText, perCallBudget);
        log.info("FullCompactStrategy: window {} tokens exceeds per-call budget {} — map-reduce over {} chunks (depth={})",
                estTokens, perCallBudget, chunks.size(), depth);
        List<String> partials = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            String partial = callLlm(provider, modelId, chunks.get(i));
            if (partial != null && !partial.isBlank()) {
                partials.add("[Part " + (i + 1) + "/" + chunks.size() + "]\n" + partial.trim());
            }
        }
        if (partials.isEmpty()) {
            return null;
        }
        if (partials.size() == 1) {
            return partials.get(0);
        }

        // Reduce phase: summarize the concatenation of chunk summaries. If even the concatenation
        // exceeds the budget (very large histories), recurse — but bounded by MAX_SUMMARY_REDUCE_DEPTH.
        String combined = String.join("\n\n", partials);
        if (TokenEstimator.estimateString(combined) > perCallBudget) {
            if (depth + 1 < MAX_SUMMARY_REDUCE_DEPTH) {
                return summarizeWithWindowGuard(provider, modelId, combined, contextWindowTokens, depth + 1);
            }
            // Depth exhausted (e.g. provider returns near-verbatim partials so combined never
            // shrinks): make one best-effort over-budget call and let the provider truncate,
            // falling back to the concatenated partials if it yields nothing.
            log.warn("FullCompactStrategy: reduce depth {} exhausted; single best-effort over-budget call",
                    MAX_SUMMARY_REDUCE_DEPTH);
            String best = callLlm(provider, modelId, combined);
            return (best != null && !best.isBlank()) ? best : combined;
        }
        String reduced = callLlm(provider, modelId, combined);
        // If the reduce call yields nothing, fall back to the concatenated partials rather than
        // losing the whole summary.
        return (reduced != null && !reduced.isBlank()) ? reduced : combined;
    }

    /**
     * Split {@code text} into chunks each estimated at &lt;= {@code budgetTokens}, preferring line
     * boundaries (serializeWindow emits one line per message). A single line larger than the budget
     * is hard-split by characters so no chunk ever exceeds the budget.
     *
     * <p>java-W1: keeps a running token counter for the accumulator instead of re-concatenating and
     * re-estimating the whole buffer each line — O(N) over the (large) window text rather than O(N²).
     */
    private List<String> chunkByBudget(String text, int budgetTokens) {
        List<String> chunks = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        StringBuilder current = new StringBuilder();
        int currentTokens = 0;                 // running estimate of `current` content
        for (String line : lines) {
            int lineTokens = TokenEstimator.estimateString(line);
            // +1 approximates the '\n' joiner token between accumulated lines.
            int joinerTokens = current.length() == 0 ? 0 : 1;
            if (currentTokens + joinerTokens + lineTokens <= budgetTokens) {
                if (current.length() > 0) current.append('\n');
                current.append(line);
                currentTokens += joinerTokens + lineTokens;
                continue;
            }
            // Candidate would overflow. Flush current first.
            if (current.length() > 0) {
                chunks.add(current.toString());
                current.setLength(0);
                currentTokens = 0;
            }
            if (lineTokens <= budgetTokens) {
                current.append(line);
                currentTokens = lineTokens;
            } else {
                // Single oversized line: hard-split by characters proportional to the budget.
                chunks.addAll(hardSplit(line, budgetTokens));
            }
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    /**
     * Hard-split an oversized single line by character count approximating the token budget.
     *
     * <p>INV-8 (UTF-16 surrogate safety): the cut point is rolled back by one if it would land
     * between a high surrogate and its trailing low surrogate (supplementary code points such as
     * emoji / non-BMP CJK occupy two chars). A mid-surrogate cut would later make Jackson throw
     * {@code MismatchedSurrogateException} when {@code callLlm} serializes the request. Same logic
     * as {@code FileStateCache.safeCutLen}.
     */
    private List<String> hardSplit(String line, int budgetTokens) {
        List<String> out = new ArrayList<>();
        // ~4 chars per token is a conservative average for cl100k_base on mixed content.
        int approxCharsPerChunk = Math.max(1_000, budgetTokens * 4);
        int i = 0;
        int len = line.length();
        while (i < len) {
            int end = Math.min(len, i + approxCharsPerChunk);
            // Surrogate-safe: if `end` would split a pair (char at end-1 is a high surrogate and
            // its low surrogate sits at index `end`), step back one so the pair is not cut.
            if (end < len && Character.isHighSurrogate(line.charAt(end - 1))) {
                end--;
            }
            // Defensive: if stepping back collapsed the window to nothing (chunk would be empty),
            // include the full pair so we always make forward progress.
            if (end <= i) {
                end = Math.min(len, i + approxCharsPerChunk + 1);
            }
            out.add(line.substring(i, end));
            i = end;
        }
        return out;
    }

    private static final String INCREMENTAL_SUMMARY_SYSTEM_PROMPT =
            "You are a conversation compressor performing an INCREMENTAL update. You are given an " +
            "EXISTING summary of the earlier conversation, plus the NEW conversation turns that " +
            "happened since. Produce an UPDATED summary that covers BOTH — the existing summary's " +
            "content AND the new turns — using the SAME section structure as the existing summary. " +
            "Write in the conversation's primary language. Do NOT invent information.\n\n" +
            "CRITICAL: Do NOT drop task state from the EXISTING summary. Carry forward every still-" +
            "relevant request, pending task, pending tool call, file path, and identifier. Merge new " +
            "information into the matching sections; only remove an item from the existing summary if " +
            "the new turns clearly show it is now completed or obsolete.\n\n" +
            "Keep ALL opaque identifiers exactly as written (no shortening or reconstruction) — UUIDs, " +
            "hashes, IDs, tokens, API keys, hostnames, IPs, ports, URLs, file paths. For any tool_use " +
            "that was emitted but did NOT yet return a tool_result, preserve the tool name AND the " +
            "COMPLETE input arguments VERBATIM.\n\n" +
            "Output only the updated structured summary, without preface or quotation marks. Target " +
            "under ~1800 tokens; if space is tight prioritize recent context and pending work.";

    /**
     * INCREMENTAL-SUMMARY: merge {@code priorSummary} with the serialized new turns into an updated
     * summary. If the combined input fits the per-call budget, a single merge call is made; otherwise
     * the new turns are first reduced via {@link #summarizeWithWindowGuard} (reusing the bounded
     * map-reduce) and then merged with the prior summary in one final call. Falls back to the prior
     * summary itself if the LLM yields nothing, so an existing summary is never lost.
     */
    private String summarizeIncremental(LlmProvider provider, String modelId, String priorSummary,
                                        String newWindowText, int contextWindowTokens) {
        int perCallBudget = (contextWindowTokens > 0)
                ? contextWindowTokens - SUMMARY_INPUT_RESERVE_TOKENS
                : SUMMARY_INPUT_FALLBACK_BUDGET_TOKENS;
        if (perCallBudget < 1_000) {
            perCallBudget = SUMMARY_INPUT_FALLBACK_BUDGET_TOKENS;
        }
        String effectiveNewWindow = (newWindowText == null) ? "" : newWindowText;
        int combinedTokens = TokenEstimator.estimateString(priorSummary)
                + TokenEstimator.estimateString(effectiveNewWindow);
        if (combinedTokens > perCallBudget && !effectiveNewWindow.isBlank()) {
            // New turns alone are large — reduce them first (bounded map-reduce) before merging, so
            // the final merge call stays within budget.
            String reducedNew = summarizeWithWindowGuard(provider, modelId, effectiveNewWindow,
                    contextWindowTokens);
            effectiveNewWindow = (reducedNew != null && !reducedNew.isBlank()) ? reducedNew : "";
        }
        String mergeInput = "## EXISTING SUMMARY\n" + priorSummary.trim()
                + "\n\n## NEW CONVERSATION TURNS\n"
                + (effectiveNewWindow.isBlank() ? "(none)" : effectiveNewWindow.trim());
        String merged = callLlmWithSystem(provider, modelId, INCREMENTAL_SUMMARY_SYSTEM_PROMPT, mergeInput);
        // Never lose the existing summary: if the merge call yields nothing, keep the prior summary.
        return (merged != null && !merged.isBlank()) ? merged : priorSummary;
    }

    private String callLlm(LlmProvider provider, String modelId, String windowText) {
        return callLlmWithSystem(provider, modelId, SUMMARY_SYSTEM_PROMPT,
                "Please summarize the following conversation history:\n\n" + windowText);
    }

    private String callLlmWithSystem(LlmProvider provider, String modelId, String systemPrompt,
                                     String userText) {
        // BUG-A / BUG-A prerequisite: previously this catch swallowed the exception and
        // returned null, which upstream treated as a normal "empty summary" no-op.
        // Under the BUG-A fix (performed=false is neutral, not a failure), a real LLM
        // backend failure would never increment the engine's compact breaker — the
        // breaker would never open. Rethrow so CompactionService Phase 2 catches,
        // rethrows with session context, and the engine records a real failure.
        LlmRequest req = new LlmRequest();
        req.setSystemPrompt(systemPrompt);
        req.setMessages(Collections.singletonList(Message.user(userText)));
        req.setModel(modelId);
        req.setMaxTokens(MAX_SUMMARY_TOKENS + 200);
        req.setTemperature(0.2);
        LlmResponse resp = provider.chat(req);
        return resp != null ? resp.getContent() : null;
    }
}
