package com.skillforge.cli.commands;

import com.fasterxml.jackson.databind.JsonNode;
import com.skillforge.cli.ApiClient;
import com.skillforge.cli.SkillforgeCli;
import com.skillforge.cli.YamlMapper;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * One-shot chat: create a new session (or reuse an existing one),
 * send a prompt, poll until the session leaves running state, then
 * print the last assistant message to stdout.
 *
 * Polling has two safety properties:
 * <ol>
 *   <li>We don't treat the initial "status is missing" response as {@code idle};
 *       it maps to {@code unknown} so we keep polling while the engine spins up.</li>
 *   <li>We only accept a terminal status ({@code idle} / {@code error} /
 *       {@code waiting_user}) after we've observed either {@code running} OR
 *       a new message in the history. Otherwise an early poll that races the
 *       engine's {@code chatAsync} enqueue would exit with "no reply found".</li>
 * </ol>
 */
@Command(name = "chat",
        description = "Send a one-shot chat prompt and wait for the assistant reply.")
public class ChatCommand implements Callable<Integer> {

    @ParentCommand
    SkillforgeCli parent;

    @Parameters(index = "0", description = "Agent id") long agentId;
    @Parameters(index = "1", description = "Prompt text") String prompt;

    @Option(names = "--session", description = "Reuse an existing session id")
    String sessionId;

    @Option(names = "--poll-interval-ms", description = "Poll interval in ms (default 1000)", defaultValue = "1000")
    long pollMs;

    @Option(names = "--timeout-seconds", description = "Max wait in seconds (default 600)", defaultValue = "600")
    long timeoutSec;

    @Override
    public Integer call() throws Exception {
        ApiClient api = parent.apiClient();
        String sid = sessionId;
        if (sid == null) {
            JsonNode created = api.createSession(agentId);
            sid = created.path("id").asText();
            // Force auto mode so the engine can't block on ask_user. Any failure
            // here is fatal — we'd just hang the polling loop otherwise.
            try {
                api.setSessionMode(sid, "auto");
            } catch (Exception e) {
                throw new RuntimeException(
                        "Could not set session to auto mode (required for one-shot chat): "
                                + e.getMessage(), e);
            }
        }
        System.err.println("session: " + sid);

        // snapshot message count before send
        JsonNode before = api.getSessionMessages(sid);
        int beforeCount = before.isArray() ? before.size() : 0;

        api.sendMessage(sid, prompt);

        return pollUntilDone(api, sid, beforeCount);
    }

    /** Poll loop extracted so tests can drive it directly. */
    int pollUntilDone(ApiClient api, String sid, int beforeCount) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        String lastStatus = null;
        boolean sawRunningOrNewMessage = false;

        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(pollMs);

            JsonNode sess = api.getSession(sid);
            // "unknown" - NOT "idle" - for missing status so a pre-start poll
            // doesn't race the engine into a spurious exit.
            String status = sess.hasNonNull("runtimeStatus")
                    ? sess.path("runtimeStatus").asText("unknown")
                    : "unknown";
            if (!status.equals(lastStatus)) {
                System.err.println("[status] " + status);
                lastStatus = status;
            }

            // Track whether the engine has actually started processing our message.
            if ("running".equalsIgnoreCase(status)) {
                sawRunningOrNewMessage = true;
            }
            if (!sawRunningOrNewMessage) {
                JsonNode msgs = api.getSessionMessages(sid);
                int cur = msgs.isArray() ? msgs.size() : 0;
                if (cur > beforeCount) sawRunningOrNewMessage = true;
            }

            // Hard failure: engine errored out. Print and bail regardless of
            // whether we ever saw "running" (the error itself is proof it ran).
            if ("error".equalsIgnoreCase(status)) {
                String err = sess.path("runtimeError").asText("");
                System.err.println("[error] " + err);
                return 1;
            }

            // Only accept terminal non-error statuses after the engine started;
            // otherwise keep polling.
            boolean terminal = "idle".equalsIgnoreCase(status)
                    || "waiting_user".equalsIgnoreCase(status);
            if (terminal && sawRunningOrNewMessage) {
                JsonNode messages = api.getSessionMessages(sid);
                List<Map<String, Object>> list = api.toMapList(messages);
                String reply = findLastAssistantText(list, beforeCount);

                if (reply != null && !reply.isEmpty()) {
                    System.out.println(reply);
                } else {
                    System.err.println("[warn] no assistant reply found in message history");
                }

                if ("waiting_user".equalsIgnoreCase(status)) {
                    System.err.println("[note] session blocked on ask_user — cannot complete one-shot chat");
                    return 1;
                }
                if (reply == null || reply.isEmpty()) {
                    return 1;
                }
                return 0;
            }
        }
        System.err.println("[error] timed out after " + timeoutSec + "s");
        return 1;
    }

    /**
     * Walk messages newest-to-oldest (but not past the pre-send snapshot) and
     * return the first assistant role with non-empty extracted text content.
     */
    static String findLastAssistantText(List<Map<String, Object>> list, int beforeCount) {
        for (int i = list.size() - 1; i >= Math.max(0, beforeCount); i--) {
            Map<String, Object> m = list.get(i);
            if (!"assistant".equals(m.get("role"))) continue;
            String text = extractAssistantText(m.get("content"));
            if (text != null && !text.isEmpty()) return text;
        }
        return null;
    }

    /**
     * Extract the concatenated text from a message {@code content} field.
     *
     * The server returns {@code content} as either a plain {@link String} or
     * a {@code List<Map<String,Object>>} of content blocks (Anthropic-style
     * {@code [{type:"text", text:...}, {type:"tool_use", ...}]}). We only
     * want the text blocks; everything else is dropped.
     */
    static String extractAssistantText(Object content) {
        if (content == null) return "";
        if (content instanceof String) return (String) content;
        if (content instanceof List<?>) {
            StringBuilder sb = new StringBuilder();
            for (Object block : (List<?>) content) {
                if (block instanceof Map<?, ?>) {
                    Map<?, ?> m = (Map<?, ?>) block;
                    Object type = m.get("type");
                    if ("text".equals(type)) {
                        Object text = m.get("text");
                        if (text != null) sb.append(text);
                    }
                }
            }
            return sb.toString();
        }
        // Last-ditch: serialize as JSON so debug output is at least valid JSON
        // instead of a Java toString.
        try {
            return YamlMapper.json().writeValueAsString(content);
        } catch (Exception e) {
            return "";
        }
    }
}
