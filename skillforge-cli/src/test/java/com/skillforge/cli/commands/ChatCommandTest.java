package com.skillforge.cli.commands;

import com.skillforge.cli.ApiClient;
import com.skillforge.cli.SkillforgeCli;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@link ChatCommand} polling loop and assistant text extraction.
 *
 * <p>Two layers:
 * <ul>
 *   <li>Pure unit tests for the static {@code extractAssistantText} helper —
 *       these are the most important since they cover the {@code List<Map>}
 *       content shape that broke the previous implementation.</li>
 *   <li>End-to-end driven via {@link MockWebServer} for the polling state
 *       machine: happy path, error termination, waiting_user, empty history.</li>
 * </ul>
 */
class ChatCommandTest {

    MockWebServer server;
    String base;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        String url = server.url("").toString();
        base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private int execute(ByteArrayOutputStream stdout, ByteArrayOutputStream stderr, String... args) {
        SkillforgeCli cli = new SkillforgeCli();
        cli.setApiClient(new ApiClient(base, 1L, false));
        CommandLine cmd = new CommandLine(cli);
        PrintStream origOut = System.out;
        PrintStream origErr = System.err;
        System.setOut(new PrintStream(stdout));
        System.setErr(new PrintStream(stderr));
        try {
            return cmd.execute(args);
        } finally {
            System.setOut(origOut);
            System.setErr(origErr);
        }
    }

    // ============ extractAssistantText (pure unit) ============

    @Test
    void extractAssistantText_handlesPlainString() {
        String text = ChatCommand.extractAssistantText("hello world");
        assertThat(text).isEqualTo("hello world");
    }

    @Test
    void extractAssistantText_concatsTextBlocksFromList() {
        // Anthropic-style content: text block + tool_use block. Only the text
        // should be picked up; the tool_use must be ignored entirely.
        Map<String, Object> textBlock = new HashMap<>();
        textBlock.put("type", "text");
        textBlock.put("text", "Here is the answer:");
        Map<String, Object> toolBlock = new HashMap<>();
        toolBlock.put("type", "tool_use");
        toolBlock.put("id", "tool-1");
        toolBlock.put("name", "Bash");
        toolBlock.put("input", Map.of("command", "ls"));

        String text = ChatCommand.extractAssistantText(Arrays.asList(textBlock, toolBlock));
        assertThat(text).isEqualTo("Here is the answer:");
        assertThat(text).doesNotContain("tool_use");
        assertThat(text).doesNotContain("Bash");
    }

    @Test
    void extractAssistantText_concatsMultipleTextBlocks() {
        Map<String, Object> a = new HashMap<>();
        a.put("type", "text");
        a.put("text", "first ");
        Map<String, Object> b = new HashMap<>();
        b.put("type", "text");
        b.put("text", "second");
        String text = ChatCommand.extractAssistantText(Arrays.asList(a, b));
        assertThat(text).isEqualTo("first second");
    }

    @Test
    void extractAssistantText_returnsEmptyForNullAndEmptyList() {
        assertThat(ChatCommand.extractAssistantText(null)).isEmpty();
        assertThat(ChatCommand.extractAssistantText(List.of())).isEmpty();
    }

    @Test
    void extractAssistantText_listWithOnlyToolUseReturnsEmpty() {
        Map<String, Object> toolBlock = new HashMap<>();
        toolBlock.put("type", "tool_use");
        toolBlock.put("name", "Bash");
        // Critical: previous implementation would `toString()` this and dump
        // a `[{type=tool_use, name=Bash}]` literal. New impl returns "".
        assertThat(ChatCommand.extractAssistantText(List.of(toolBlock))).isEmpty();
    }

    // ============ findLastAssistantText (pure unit) ============

    @Test
    void findLastAssistantText_returnsLatestAssistantNotMessagesBeforeSnapshot() {
        // 3 pre-snapshot messages then 2 new assistant messages added by this turn
        Map<String, Object> oldUser = Map.of("role", "user", "content", "earlier");
        Map<String, Object> oldAsst = Map.of("role", "assistant", "content", "stale reply");
        Map<String, Object> oldUser2 = Map.of("role", "user", "content", "another");
        Map<String, Object> newUser = Map.of("role", "user", "content", "this turn's prompt");
        Map<String, Object> newAsst = Map.of("role", "assistant", "content", "fresh reply");

        List<Map<String, Object>> all = Arrays.asList(oldUser, oldAsst, oldUser2, newUser, newAsst);

        // beforeCount=3 means we should ignore indices 0..2 entirely.
        String text = ChatCommand.findLastAssistantText(all, 3);
        assertThat(text).isEqualTo("fresh reply");
        assertThat(text).isNotEqualTo("stale reply");
    }

    @Test
    void findLastAssistantText_returnsNullWhenNoNewAssistantMessages() {
        Map<String, Object> oldAsst = Map.of("role", "assistant", "content", "stale");
        Map<String, Object> newUser = Map.of("role", "user", "content", "prompt");
        // Only user message after snapshot, no new assistant reply.
        List<Map<String, Object>> all = Arrays.asList(oldAsst, newUser);
        String text = ChatCommand.findLastAssistantText(all, 1);
        assertThat(text).isNull();
    }

    // ============ End-to-end via MockWebServer ============

    /**
     * Helper: enqueue the standard "create session, set mode auto, get
     * messages snapshot (=0), POST chat" sequence for a fresh chat command.
     */
    private void enqueueChatStartup() {
        // 1. createSession → POST /api/chat/sessions
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"test-sid\"}"));
        // 2. setSessionMode("auto") → PATCH /api/chat/sessions/test-sid/mode
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{}"));
        // 3. snapshot getSessionMessages → empty list
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("[]"));
        // 4. sendMessage → 202 accepted
        server.enqueue(new MockResponse()
                .setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"sessionId\":\"test-sid\",\"status\":\"accepted\"}"));
    }

    @Test
    void chatHappyPath_runningThenIdleThenPrintsAssistantText() {
        enqueueChatStartup();
        // 5. first poll: getSession → running
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"test-sid\",\"runtimeStatus\":\"running\"}"));
        // 6. second poll: getSession → idle
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"test-sid\",\"runtimeStatus\":\"idle\"}"));
        // 7. fetch messages on terminal status: user + assistant text reply
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("[{\"role\":\"user\",\"content\":\"hi\"}," +
                        "{\"role\":\"assistant\",\"content\":\"Hello there!\"}]"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = execute(out, err, "chat", "1", "hi", "--poll-interval-ms", "10", "--timeout-seconds", "10");

        assertThat(code).isEqualTo(0);
        assertThat(out.toString().trim()).isEqualTo("Hello there!");
        // session id should land on stderr so `>` redirect captures only the reply
        assertThat(err.toString()).contains("test-sid");
    }

    @Test
    void chatExtractsTextFromListShapedContent() {
        enqueueChatStartup();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"test-sid\",\"runtimeStatus\":\"running\"}"));
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"test-sid\",\"runtimeStatus\":\"idle\"}"));
        // assistant message with Anthropic-style content blocks
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("[{\"role\":\"user\",\"content\":\"hi\"}," +
                        "{\"role\":\"assistant\",\"content\":[" +
                        "{\"type\":\"text\",\"text\":\"the answer is 42\"}," +
                        "{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"Bash\",\"input\":{\"command\":\"echo 42\"}}" +
                        "]}]"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = execute(out, err, "chat", "1", "hi", "--poll-interval-ms", "10", "--timeout-seconds", "10");

        assertThat(code).isEqualTo(0);
        assertThat(out.toString().trim()).isEqualTo("the answer is 42");
        // Critical: must NOT contain the literal toString() form
        assertThat(out.toString()).doesNotContain("type=tool_use");
        assertThat(out.toString()).doesNotContain("[{");
    }

    @Test
    void chatErrorTermination_exitsNonZeroAndPrintsError() {
        enqueueChatStartup();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"test-sid\",\"runtimeStatus\":\"running\"}"));
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"test-sid\",\"runtimeStatus\":\"error\"," +
                        "\"runtimeError\":\"LLM call failed: timeout\"}"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = execute(out, err, "chat", "1", "hi", "--poll-interval-ms", "10", "--timeout-seconds", "10");

        assertThat(code).isNotEqualTo(0);
        assertThat(err.toString()).contains("LLM call failed: timeout");
    }

    @Test
    void chatWaitingUser_exitsNonZeroWithClearMessage() {
        enqueueChatStartup();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"test-sid\",\"runtimeStatus\":\"running\"}"));
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"test-sid\",\"runtimeStatus\":\"waiting_user\"}"));
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("[{\"role\":\"user\",\"content\":\"hi\"}," +
                        "{\"role\":\"assistant\",\"content\":\"What did you mean?\"}]"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = execute(out, err, "chat", "1", "hi", "--poll-interval-ms", "10", "--timeout-seconds", "10");

        assertThat(code).isNotEqualTo(0);
        assertThat(err.toString()).containsIgnoringCase("ask_user");
    }

    @Test
    void chatEmptyHistory_exitsNonZero() {
        enqueueChatStartup();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"test-sid\",\"runtimeStatus\":\"running\"}"));
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"test-sid\",\"runtimeStatus\":\"idle\"}"));
        // Idle but no new assistant message — only the user message we sent.
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("[{\"role\":\"user\",\"content\":\"hi\"}]"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = execute(out, err, "chat", "1", "hi", "--poll-interval-ms", "10", "--timeout-seconds", "10");

        assertThat(code).isNotEqualTo(0);
        assertThat(err.toString()).containsIgnoringCase("no assistant reply");
    }
}
