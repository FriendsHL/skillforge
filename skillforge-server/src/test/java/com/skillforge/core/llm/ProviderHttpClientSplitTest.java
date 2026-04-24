package com.skillforge.core.llm;

import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUG-E: Provider must hold two distinct {@link OkHttpClient} instances — one for
 * non-stream {@code chat()} (total-response readTimeout) and one for stream
 * {@code chatStream()} (inter-chunk idle readTimeout). Sharing a client made the
 * single {@code readTimeout} config ambiguous and blocked independent tuning.
 */
class ProviderHttpClientSplitTest {

    @Test
    void openAiProvider_httpClient_isDistinctFromStreamHttpClient() throws Exception {
        OpenAiProvider provider = new OpenAiProvider("test-key", "http://localhost:1", "gpt-4o",
                60, 1);
        OkHttpClient chat = readField(provider, "httpClient");
        OkHttpClient stream = readField(provider, "streamHttpClient");

        assertThat(chat).isNotNull();
        assertThat(stream).isNotNull();
        assertThat(stream).isNotSameAs(chat);
    }

    @Test
    void claudeProvider_httpClient_isDistinctFromStreamHttpClient() throws Exception {
        ClaudeProvider provider = new ClaudeProvider("test-key", "http://localhost:1",
                "claude-sonnet-4-20250514", 60, 1);
        OkHttpClient chat = readField(provider, "httpClient");
        OkHttpClient stream = readField(provider, "streamHttpClient");

        assertThat(chat).isNotNull();
        assertThat(stream).isNotNull();
        assertThat(stream).isNotSameAs(chat);
    }

    private static OkHttpClient readField(Object obj, String name) throws Exception {
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (OkHttpClient) f.get(obj);
    }
}
