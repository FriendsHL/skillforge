package com.skillforge.server.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ContentBlock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Accumulates ACP {@code tool_call} / {@code tool_call_update} into paired
 * SkillForge tool_use + tool_result {@link ContentBlock}s for the persisted ACP
 * run sub-session (ACP-EXTERNAL-AGENT P1b).
 *
 * <p>Each tool call (correlated by {@code toolCallId}) becomes a tool_use block
 * (id = toolCallId, name = the cc tool name from {@code _meta.claudeCode.toolName},
 * input = {@code rawInput}) plus, on completion, a tool_result block
 * (tool_use_id = toolCallId, content = {@code rawOutput}/content text).
 *
 * <p><b>INV-1 (tool_use ↔ tool_result pairing):</b> {@link #buildToolUseBlocks()}
 * and {@link #buildToolResultBlocks()} emit, in start order, exactly one
 * tool_result per tool_use (matched by id). A tool that never completes by run end
 * gets a SYNTHESIZED error tool_result so the persisted sub-session never has an
 * orphan tool_use (renders + can be loaded/compacted).
 *
 * <p><b>AC-2a (subagent count):</b> a cc subagent dispatch is a tool_call whose
 * tool name is {@code Task}; {@link #subagentCount()} returns how many were seen.
 *
 * <p>Not thread-safe by itself: instances are mutated only from the cc reader
 * thread (the update listener), then read on the caller thread after the prompt
 * future completes (the same happens-before edge the runner relies on for text).
 */
public class AcpToolCallAccumulator {

    /** cc's subagent dispatch tool name (AC-2a). */
    public static final String TASK_TOOL_NAME = "Task";

    /** Synthetic tool_result text for a tool_use that never completed (INV-1 guard). */
    static final String INCOMPLETE_RESULT =
            "[external agent] tool call did not complete before the run ended";

    private final ObjectMapper objectMapper;

    /** Insertion-ordered by toolCallId so persisted blocks keep call order. */
    private final Map<String, ToolCallState> calls = new LinkedHashMap<>();
    private int subagentCount;

    public AcpToolCallAccumulator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** A {@code tool_call} (pending) — start a tool_use, capture name/input. */
    public void onToolCall(AcpUpdate.ToolCall tc) {
        String id = tc.toolCallId();
        if (id == null || id.isBlank()) {
            return;
        }
        ToolCallState state = calls.computeIfAbsent(id, k -> new ToolCallState(id));
        String toolName = toolName(tc.raw(), tc.title());
        if (toolName != null) {
            state.name = toolName;
        }
        Map<String, Object> input = rawInput(tc.raw());
        if (input != null) {
            state.input = input;
        }
        if (TASK_TOOL_NAME.equals(state.name) && !state.countedAsSubagent) {
            state.countedAsSubagent = true;
            subagentCount++;
        }
    }

    /**
     * A {@code tool_call_update}. When {@code status=completed} → record the
     * tool_result content. Intermediate updates (rawInput filled, not completed)
     * refine the captured input — they do NOT add a second block (no double
     * persist).
     */
    public void onToolCallUpdate(AcpUpdate.ToolCallUpdate tcu) {
        String id = tcu.toolCallId();
        if (id == null || id.isBlank()) {
            return;
        }
        // An update can arrive before the initial tool_call (defensive: create state).
        ToolCallState state = calls.computeIfAbsent(id, k -> new ToolCallState(id));
        Map<String, Object> input = rawInput(tcu.raw());
        if (input != null) {
            state.input = input;
        }
        String name = toolName(tcu.raw(), null);
        if (name != null && state.name == null) {
            state.name = name;
            if (TASK_TOOL_NAME.equals(name) && !state.countedAsSubagent) {
                state.countedAsSubagent = true;
                subagentCount++;
            }
        }
        if ("completed".equals(tcu.status()) || "failed".equals(tcu.status())) {
            state.completed = true;
            state.isError = "failed".equals(tcu.status());
            state.resultText = resultText(tcu.raw());
        }
    }

    /** True when at least one tool_call was seen this run (drives block shape at persist). */
    public boolean hasAnyToolCalls() {
        return !calls.isEmpty();
    }

    public int subagentCount() {
        return subagentCount;
    }

    /**
     * Total number of distinct cc tool calls seen this run (every tool, not just
     * {@code Task}). Used as the {@code toolCalls} count in the
     * {@link com.skillforge.server.service.event.SessionLoopFinishedEvent} /
     * SubAgent registry callback when cc runs as a SubAgent (P1c-1).
     */
    public int toolCallCount() {
        return calls.size();
    }

    /** Current input snapshot for a tool call (for live UI updates); may be null. */
    public Map<String, Object> inputOf(String toolCallId) {
        ToolCallState s = calls.get(toolCallId);
        return s != null ? s.input : null;
    }

    public String nameOf(String toolCallId) {
        ToolCallState s = calls.get(toolCallId);
        return s != null ? s.name : null;
    }

    /**
     * The tool_use blocks (in call order) for the ASSISTANT message. Pairs with
     * {@link #buildToolResultBlocks()} by id (INV-1).
     */
    public List<ContentBlock> buildToolUseBlocks() {
        List<ContentBlock> blocks = new ArrayList<>(calls.size());
        for (ToolCallState s : calls.values()) {
            String name = s.name != null ? s.name : "tool";
            Map<String, Object> input = s.input != null ? s.input : Map.of();
            blocks.add(ContentBlock.toolUse(s.id, name, input));
        }
        return blocks;
    }

    /**
     * The tool_result blocks (same order/ids as {@link #buildToolUseBlocks()}) for
     * the following USER message — the canonical SkillForge/Anthropic shape that
     * the compact pairing logic recognizes. INV-1: exactly one tool_result per
     * tool_use, matched by id; an incomplete call gets a synthesized error result
     * so no orphan tool_use is persisted.
     */
    public List<ContentBlock> buildToolResultBlocks() {
        List<ContentBlock> blocks = new ArrayList<>(calls.size());
        for (ToolCallState s : calls.values()) {
            if (s.completed) {
                blocks.add(ContentBlock.toolResult(
                        s.id, s.resultText != null ? s.resultText : "", s.isError));
            } else {
                blocks.add(ContentBlock.toolResult(s.id, INCOMPLETE_RESULT, true));
            }
        }
        return blocks;
    }

    // ───────────────────────── extraction helpers ─────────────────────────

    /** cc tool name from {@code _meta.claudeCode.toolName}; falls back to title. */
    private static String toolName(JsonNode raw, String title) {
        if (raw != null) {
            JsonNode meta = raw.get("_meta");
            if (meta != null) {
                JsonNode claudeCode = meta.get("claudeCode");
                if (claudeCode != null) {
                    JsonNode tn = claudeCode.get("toolName");
                    if (tn != null && tn.isTextual() && !tn.asText().isBlank()) {
                        return tn.asText();
                    }
                }
            }
        }
        return null; // title is a human label, not the canonical tool name → don't use as name
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> rawInput(JsonNode raw) {
        if (raw == null) {
            return null;
        }
        JsonNode rawInput = raw.get("rawInput");
        if (rawInput == null || rawInput.isNull() || !rawInput.isObject() || rawInput.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.convertValue(rawInput, Map.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Result text from a completion update: prefer {@code rawOutput} (string), else
     * concatenate {@code content[].content.text} text blocks.
     */
    private static String resultText(JsonNode raw) {
        if (raw == null) {
            return "";
        }
        JsonNode rawOutput = raw.get("rawOutput");
        if (rawOutput != null && !rawOutput.isNull()) {
            return rawOutput.isTextual() ? rawOutput.asText() : rawOutput.toString();
        }
        JsonNode content = raw.get("content");
        if (content != null && content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode entry : content) {
                JsonNode inner = entry.get("content");
                JsonNode textNode = inner != null ? inner.get("text") : null;
                if (textNode != null && textNode.isTextual()) {
                    if (sb.length() > 0) {
                        sb.append("\n");
                    }
                    sb.append(textNode.asText());
                }
            }
            return sb.toString();
        }
        return "";
    }

    /** Mutable per-call accumulation state. */
    private static final class ToolCallState {
        final String id;
        String name;
        Map<String, Object> input;
        boolean completed;
        boolean isError;
        String resultText;
        boolean countedAsSubagent;

        ToolCallState(String id) {
            this.id = id;
        }
    }
}
