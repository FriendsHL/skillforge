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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArkVideoGenerationClientTest {
    @TempDir Path temp;
    @Test void submitPollAndDownload_validResponses_succeeds() throws Exception {
        Interceptor api = chain -> {
            String path = chain.request().url().encodedPath();
            if (path.endsWith("/tasks")) return response(chain, 200, "application/json", "{\"id\":\"provider-1\"}");
            if (path.endsWith("provider-1")) return response(chain, 200, "application/json", "{\"status\":\"succeeded\",\"content\":{\"video_url\":\"https://media.example/result.mp4\"}}");
            return response(chain, 200, "video/mp4", "0000ftypisomvideo");
        };
        ArkVideoProperties p = properties(); p.setAllowedDownloadHosts(List.of("media.example"));
        ArkVideoGenerationClient client = new ArkVideoGenerationClient(p, new ObjectMapper(), new OkHttpClient.Builder().addInterceptor(api).build());
        var submitted = client.submit(new VideoGenerationProvider.VideoRequest("cat", 5, "480p", "16:9", false));
        var status = client.get(submitted.providerJobId());
        Path out = temp.resolve("result.mp4"); client.download(status.resultUrl(), out);
        assertThat(submitted.providerJobId()).isEqualTo("provider-1");
        assertThat(status.state()).isEqualTo("succeeded");
        assertThat(Files.size(out)).isPositive();
    }
    @Test void download_untrustedHost_rejected() {
        ArkVideoGenerationClient client = new ArkVideoGenerationClient(properties(), new ObjectMapper(), new OkHttpClient());
        assertThatThrownBy(() -> client.download("https://evil.example/a.mp4", temp.resolve("a.mp4")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not allowed");
    }
    @Test void productionConstructor_standardEndpoint_rejected() {
        ArkVideoProperties p = properties(); p.setBaseUrl("https://ark.cn-beijing.volces.com/api/v3");
        assertThatThrownBy(() -> new ArkVideoGenerationClient(p, new ObjectMapper()))
                .hasMessageContaining("subscription /api/plan/v3");
    }
    private static ArkVideoProperties properties() { ArkVideoProperties p = new ArkVideoProperties(); p.setEnabled(true); p.setApiKey("secret"); return p; }
    private static Response response(Interceptor.Chain chain, int code, String mime, String body) {
        return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .body(ResponseBody.create(body, MediaType.get(mime))).build();
    }
}
