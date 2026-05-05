package com.skillforge.server.eval;

import java.util.ArrayList;
import java.util.List;

/**
 * EVAL-V2 M2: in-memory transcript of a multi-turn case execution.
 *
 * <p>Contains the user/system turns from the spec (verbatim) plus assistant turns
 * with their {@code <placeholder>} content replaced by the agent's actual response.
 * Used by {@link EvalJudgeTool#judgeMultiTurnConversation} to build the prompt the
 * LLM judge sees. Not persisted — the canonical runtime conversation lives in
 * {@code t_session_message} via the regular agent loop path.
 */
public class MultiTurnTranscript {

    private final List<Entry> entries = new ArrayList<>();

    public List<Entry> getEntries() {
        return entries;
    }

    public void add(String role, String content) {
        entries.add(new Entry(role, content));
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Renders a {@code [role] content} block per turn, joined by blank lines. */
    public String render() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (i > 0) sb.append("\n\n");
            sb.append("[").append(e.role).append("] ").append(e.content == null ? "" : e.content);
        }
        return sb.toString();
    }

    public static class Entry {
        private final String role;
        private final String content;

        public Entry(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public String getRole() { return role; }
        public String getContent() { return content; }
    }
}
