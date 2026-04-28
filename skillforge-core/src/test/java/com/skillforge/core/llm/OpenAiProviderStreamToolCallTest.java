package com.skillforge.core.llm;

import com.skillforge.core.model.ToolUseBlock;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiProviderStreamToolCallTest {

    @Test
    @DisplayName("qwen stream blank id/name deltas do not overwrite first valid tool_call identity")
    void qwenStream_blankIdentityDeltas_preserveFirstValidIdentity() throws Exception {
        OpenAiProvider provider = new OpenAiProvider(
                "test-key", "http://localhost:1", "qwen3.5-plus",
                "bailian", "BAILIAN_API_KEY", 60, 1);
        RecordingStreamHandler handler = new RecordingStreamHandler();

        invokeProcessSse(provider, sse(
                event("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1c92880586144bbf932ac619\",\"type\":\"function\",\"function\":{\"name\":\"WebFetch\",\"arguments\":\"{\\\"url\\\":\\\"https://example.com\\\"\"}}]},\"finish_reason\":null}]}"),
                event("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"\",\"function\":{\"name\":\"\",\"arguments\":\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}"),
                "data: [DONE]\n\n"
        ), handler);

        assertThat(handler.completed).isNotNull();
        assertThat(handler.completed.isToolUse()).isTrue();
        assertThat(handler.completed.getValidToolUseBlocks()).hasSize(1);

        ToolUseBlock block = handler.completed.getValidToolUseBlocks().get(0);
        assertThat(block.getId()).isEqualTo("call_1c92880586144bbf932ac619");
        assertThat(block.getName()).isEqualTo("WebFetch");
        assertThat(block.getInput()).containsEntry("url", "https://example.com");

        assertThat(handler.startedIds).containsExactly("call_1c92880586144bbf932ac619");
        assertThat(handler.endedIds).containsExactly("call_1c92880586144bbf932ac619");
    }

    private static void invokeProcessSse(OpenAiProvider provider, String body, LlmStreamHandler handler)
            throws Exception {
        Method processSse = OpenAiProvider.class.getDeclaredMethod(
                "processSSEStream", Response.class, LlmStreamHandler.class);
        processSse.setAccessible(true);
        processSse.invoke(provider, response(body), handler);
    }

    private static Response response(String body) {
        return new Response.Builder()
                .request(new Request.Builder().url("http://localhost/stream").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(body, MediaType.get("text/event-stream")))
                .build();
    }

    private static String event(String json) {
        return "data: " + json + "\n\n";
    }

    private static String sse(String... events) {
        return String.join("", events);
    }

    private static final class RecordingStreamHandler implements LlmStreamHandler {
        private final List<String> startedIds = new ArrayList<>();
        private final List<String> endedIds = new ArrayList<>();
        private LlmResponse completed;

        @Override public void onText(String text) {
        }

        @Override public void onToolUse(ToolUseBlock block) {
        }

        @Override public void onComplete(LlmResponse fullResponse) {
            this.completed = fullResponse;
        }

        @Override public void onError(Throwable error) {
            throw new AssertionError(error);
        }

        @Override public void onToolUseStart(String toolUseId, String name) {
            startedIds.add(toolUseId);
        }

        @Override public void onToolUseEnd(String toolUseId, Map<String, Object> parsedInput) {
            endedIds.add(toolUseId);
        }
    }
}
