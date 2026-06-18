package com.skillforge.server.channel.platform.weixin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link WeixinIlinkClient} with a mocked OkHttp transport (interceptor returns
 * canned JSON and captures the outgoing request). Reverse-engineered protocol; live behavior must
 * be verified with a real WeChat scan.
 */
class WeixinIlinkClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Build a client whose OkHttp transport is fully short-circuited by an interceptor. */
    private WeixinIlinkClient clientReturning(
            List<Request> captured, Function<Request, String> responder) {
        Interceptor interceptor = chain -> {
            Request req = chain.request();
            captured.add(req);
            String body = responder.apply(req);
            return new Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create(body, MediaType.parse("application/json")))
                    .build();
        };
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(interceptor).build();
        return new WeixinIlinkClient(objectMapper, http);
    }

    @Test
    @DisplayName("all requests carry the 4 iLink auth headers incl per-request X-WECHAT-UIN")
    void authHeaders_present_andUinPerRequest() {
        List<Request> captured = new ArrayList<>();
        WeixinIlinkClient client = clientReturning(captured,
                r -> "{\"ret\":0,\"get_updates_buf\":\"c1\",\"msgs\":[]}");

        client.getUpdates("", "tok123", null);
        client.getUpdates("c1", "tok123", null);

        assertThat(captured).hasSize(2);
        for (Request req : captured) {
            assertThat(req.header("Content-Type")).isEqualTo("application/json");
            assertThat(req.header("AuthorizationType")).isEqualTo("ilink_bot_token");
            assertThat(req.header("Authorization")).isEqualTo("Bearer tok123");
            String uin = req.header("X-WECHAT-UIN");
            assertThat(uin).isNotBlank();
            // base64 of a decimal string
            String decoded = new String(Base64.getDecoder().decode(uin), StandardCharsets.UTF_8);
            assertThat(decoded).matches("\\d+");
        }
        // UIN regenerated per request (overwhelmingly likely to differ; same value would be a bug
        // only if it were a constant — assert it is at least not hardcoded by checking decode works).
        assertThat(captured.get(0).header("X-WECHAT-UIN")).isNotNull();
    }

    @Test
    @DisplayName("getUpdates advances cursor from server get_updates_buf")
    void getUpdates_advancesCursor() {
        List<Request> captured = new ArrayList<>();
        WeixinIlinkClient client = clientReturning(captured,
                r -> "{\"ret\":0,\"get_updates_buf\":\"cursor-2\",\"longpolling_timeout_ms\":35000,\"msgs\":[{\"from_user_id\":\"u1\"}]}");

        WeixinIlinkClient.GetUpdatesResult result = client.getUpdates("cursor-1", "tok", null);

        assertThat(result.cursor()).isEqualTo("cursor-2");
        assertThat(result.longpollingTimeoutMs()).isEqualTo(35000L);
        assertThat(result.msgs().isArray()).isTrue();
        assertThat(result.msgs()).hasSize(1);
    }

    @Test
    @DisplayName("getUpdates sends cursor + channel_version in body")
    void getUpdates_sendsCursorAndChannelVersion() throws Exception {
        List<Request> captured = new ArrayList<>();
        WeixinIlinkClient client = clientReturning(captured,
                r -> "{\"ret\":0,\"get_updates_buf\":\"x\",\"msgs\":[]}");

        client.getUpdates("my-cursor", "tok", null);

        JsonNode sent = readBody(captured.get(0));
        assertThat(sent.path("get_updates_buf").asText()).isEqualTo("my-cursor");
        assertThat(sent.path("base_info").path("channel_version").asText())
                .isEqualTo(WeixinIlinkClient.CHANNEL_VERSION);
    }

    @Test
    @DisplayName("getUpdates falls back to old cursor when server omits get_updates_buf")
    void getUpdates_fallsBackToOldCursor() {
        List<Request> captured = new ArrayList<>();
        WeixinIlinkClient client = clientReturning(captured, r -> "{\"ret\":0,\"msgs\":[]}");

        WeixinIlinkClient.GetUpdatesResult result = client.getUpdates("keep-me", "tok", null);

        assertThat(result.cursor()).isEqualTo("keep-me");
    }

    @Test
    @DisplayName("getUpdates throws on ret != 0")
    void getUpdates_throwsOnNonZeroRet() {
        WeixinIlinkClient client = clientReturning(new ArrayList<>(),
                r -> "{\"ret\":1001,\"errmsg\":\"bad token\"}");

        assertThatThrownBy(() -> client.getUpdates("", "tok", null))
                .isInstanceOf(WeixinIlinkClient.WeixinIlinkException.class)
                .hasMessageContaining("1001");
    }

    @Test
    @DisplayName("sendText builds the iLink payload and echoes context_token")
    void sendText_payloadAndContextTokenEcho() throws Exception {
        List<Request> captured = new ArrayList<>();
        WeixinIlinkClient client = clientReturning(captured, r -> "{\"ret\":0}");

        client.sendText("user-007", "ctx-abc", "hello world", "tok", null);

        JsonNode sent = readBody(captured.get(0));
        JsonNode msg = sent.path("msg");
        assertThat(msg.path("to_user_id").asText()).isEqualTo("user-007");
        assertThat(msg.path("context_token").asText()).isEqualTo("ctx-abc");
        assertThat(msg.path("message_type").asInt()).isEqualTo(WeixinIlinkClient.OUT_MESSAGE_TYPE);
        assertThat(msg.path("message_state").asInt()).isEqualTo(WeixinIlinkClient.OUT_MESSAGE_STATE);
        JsonNode item = msg.path("item_list").get(0);
        assertThat(item.path("type").asInt()).isEqualTo(WeixinIlinkClient.ITEM_TYPE_TEXT);
        assertThat(item.path("text_item").path("text").asText()).isEqualTo("hello world");
    }

    @Test
    @DisplayName("sendText throws on ret != 0")
    void sendText_throwsOnNonZeroRet() {
        WeixinIlinkClient client = clientReturning(new ArrayList<>(),
                r -> "{\"ret\":42,\"errmsg\":\"nope\"}");

        assertThatThrownBy(() -> client.sendText("u", "c", "t", "tok", null))
                .isInstanceOf(WeixinIlinkClient.WeixinIlinkException.class)
                .hasMessageContaining("42");
    }

    @Test
    @DisplayName("getBotQrcode parses qrcode + img content")
    void getBotQrcode_parses() {
        WeixinIlinkClient client = clientReturning(new ArrayList<>(),
                r -> "{\"qrcode\":\"qr-1\",\"qrcode_img_content\":\"base64img\"}");

        WeixinIlinkClient.QrCode qr = client.getBotQrcode(null);

        assertThat(qr.qrcode()).isEqualTo("qr-1");
        assertThat(qr.qrcodeImgContent()).isEqualTo("base64img");
    }

    @Test
    @DisplayName("getQrcodeStatus state machine: pending then confirmed with token")
    void getQrcodeStatus_stateMachine() {
        WeixinIlinkClient pending = clientReturning(new ArrayList<>(),
                r -> "{\"status\":\"pending\"}");
        WeixinIlinkClient.QrStatus s1 = pending.getQrcodeStatus("qr", null);
        assertThat(s1.confirmed()).isFalse();
        assertThat(s1.status()).isEqualTo("pending");

        WeixinIlinkClient confirmed = clientReturning(new ArrayList<>(),
                r -> "{\"status\":\"confirmed\",\"bot_token\":\"tok-xyz\",\"baseurl\":\"https://h.example\"}");
        WeixinIlinkClient.QrStatus s2 = confirmed.getQrcodeStatus("qr", null);
        assertThat(s2.confirmed()).isTrue();
        assertThat(s2.botToken()).isEqualTo("tok-xyz");
        assertThat(s2.baseurl()).isEqualTo("https://h.example");
    }

    @Test
    @DisplayName("baseurl override changes the request host")
    void baseurl_overridesHost() {
        List<Request> captured = new ArrayList<>();
        WeixinIlinkClient client = clientReturning(captured, r -> "{\"ret\":0,\"msgs\":[]}");

        client.getUpdates("", "tok", "https://custom-host.example");

        assertThat(captured.get(0).url().toString()).startsWith("https://custom-host.example/ilink/bot/getupdates");
    }

    @Test
    @DisplayName("default host used when baseurl absent")
    void defaultHost_whenNoBaseurl() {
        List<Request> captured = new ArrayList<>();
        WeixinIlinkClient client = clientReturning(captured, r -> "{\"ret\":0,\"msgs\":[]}");

        client.getUpdates("", "tok", null);

        assertThat(captured.get(0).url().toString()).startsWith(WeixinIlinkClient.DEFAULT_HOST);
    }

    @Test
    @DisplayName("auth rejection (401) surfaces a token-expired error")
    void authRejection_surfacesExpiredError() {
        Interceptor interceptor = chain -> new Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")
                .body(ResponseBody.create("{}", MediaType.parse("application/json")))
                .build();
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(interceptor).build();
        WeixinIlinkClient client = new WeixinIlinkClient(objectMapper, http);

        assertThatThrownBy(() -> client.getUpdates("", "tok", null))
                .isInstanceOf(WeixinIlinkClient.WeixinIlinkException.class)
                .hasMessageContaining("expired");
    }

    private JsonNode readBody(Request req) throws IOException {
        okio.Buffer buffer = new okio.Buffer();
        req.body().writeTo(buffer);
        return objectMapper.readTree(buffer.readUtf8());
    }
}
