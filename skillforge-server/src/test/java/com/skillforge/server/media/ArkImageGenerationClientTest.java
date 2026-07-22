package com.skillforge.server.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArkImageGenerationClientTest {

    @TempDir Path tempDir;

    @Test
    void generateAndDownload_validResponses_writesBoundedImage() throws Exception {
        List<okhttp3.Request> requests = new ArrayList<>();
        Interceptor interceptor = chain -> {
            requests.add(chain.request());
            if (chain.request().url().encodedPath().endsWith("/images/generations")) {
                return response(chain, 200, "application/json", """
                        {"data":[{"url":"https://media.example/result.jpg","size":"2048x2048"}],
                         "usage":{"output_tokens":16384}}
                        """);
            }
            return response(chain, 200, "image/jpeg", "jpeg-bytes");
        };
        ArkImageProperties properties = properties();
        properties.setAllowedDownloadHosts(List.of("media.example"));
        ArkImageGenerationClient client = new ArkImageGenerationClient(
                properties, new ObjectMapper(), new OkHttpClient.Builder().addInterceptor(interceptor).build());

        ArkImageGenerationClient.GeneratedImage generated = client.generate("blue anvil", "2K", true);
        Path target = tempDir.resolve("result.jpg");
        client.download(generated, target);

        assertThat(generated.size()).isEqualTo("2048x2048");
        assertThat(generated.outputTokens()).isEqualTo(16384);
        assertThat(Files.readString(target)).isEqualTo("jpeg-bytes");
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).header("Authorization")).isEqualTo("Bearer secret");
        assertThat(requests.get(0).body()).isNotNull();
    }

    @Test
    void download_untrustedHost_rejectsBeforeNetworkCall() {
        ArkImageGenerationClient client = new ArkImageGenerationClient(
                properties(), new ObjectMapper(), new OkHttpClient());

        assertThatThrownBy(() -> client.download(
                new ArkImageGenerationClient.GeneratedImage("https://evil.example/a.jpg", "2K", 1),
                tempDir.resolve("a.jpg")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Ark image result host is not allowed");
    }

    @Test
    void download_streamExceedsLimit_removesPartialFile() {
        Interceptor interceptor = chain -> response(chain, 200, "image/jpeg", "too-large");
        ArkImageProperties properties = properties();
        properties.setAllowedDownloadHosts(List.of("media.example"));
        properties.setMaxDownloadBytes(4);
        ArkImageGenerationClient client = new ArkImageGenerationClient(
                properties, new ObjectMapper(), new OkHttpClient.Builder().addInterceptor(interceptor).build());
        Path target = tempDir.resolve("large.jpg");

        assertThatThrownBy(() -> client.download(
                new ArkImageGenerationClient.GeneratedImage("https://media.example/a.jpg", "2K", 1), target))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("download limit");
        assertThat(target).doesNotExist();
    }

    @Test
    void productionConstructor_standardApiEndpoint_rejectsPotentialExtraBilling() {
        ArkImageProperties properties = properties();
        properties.setBaseUrl("https://ark.cn-beijing.volces.com/api/v3");

        assertThatThrownBy(() -> new ArkImageGenerationClient(properties, new ObjectMapper()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("subscription /api/plan/v3 endpoint");
    }

    @Test
    void generate_endpointChangedAfterConstruction_rejectsPotentialExtraBilling() {
        ArkImageProperties properties = properties();
        ArkImageGenerationClient client = new ArkImageGenerationClient(
                properties, new ObjectMapper(), new OkHttpClient.Builder()
                .addInterceptor(chain -> { throw new AssertionError("network must not be called"); })
                .build());
        properties.setBaseUrl("https://ark.cn-beijing.volces.com/api/v3");

        assertThatThrownBy(() -> client.generate("blue anvil", "2K", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("subscription /api/plan/v3 endpoint");
    }

    private static ArkImageProperties properties() {
        ArkImageProperties properties = new ArkImageProperties();
        properties.setEnabled(true);
        properties.setApiKey("secret");
        properties.setBaseUrl("https://ark.cn-beijing.volces.com/api/plan/v3");
        return properties;
    }

    private static Response response(Interceptor.Chain chain, int code, String contentType, String body) {
        return new Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("test")
                .body(ResponseBody.create(body, MediaType.get(contentType)))
                .build();
    }
}
