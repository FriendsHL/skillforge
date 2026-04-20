package com.skillforge.server.channel.platform.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.ws.pb.Pbbp2;
import com.skillforge.server.channel.router.ChannelSessionRouter;
import com.skillforge.server.channel.spi.ChannelConfigDecrypted;
import com.skillforge.server.channel.spi.ChannelMessage;
import com.skillforge.server.repository.ChannelMessageDedupRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FeishuWsEventDispatcherTest {

    @Test
    @DisplayName("dispatch should route event and keep service_id in ack")
    void dispatch_event_routesAndAcksWithServiceId() throws Exception {
        FeishuEventParser parser = mock(FeishuEventParser.class);
        ChannelMessageDedupRepository dedupRepository = mock(ChannelMessageDedupRepository.class);
        ChannelSessionRouter router = mock(ChannelSessionRouter.class);
        FeishuWsEventDispatcher dispatcher = new FeishuWsEventDispatcher(
                new ObjectMapper(), parser, dedupRepository, router);

        ChannelConfigDecrypted config = new ChannelConfigDecrypted(
                1L, "feishu", "", "{}", "{\"mode\":\"websocket\"}", 100L);
        ChannelMessage message = new ChannelMessage(
                "feishu",
                "oc_xxx",
                "ou_xxx",
                "om_123",
                ChannelMessage.MessageType.TEXT,
                "hello",
                null,
                Instant.now(),
                Map.of());
        when(parser.parse(any(byte[].class), eq(config))).thenReturn(Optional.of(message));
        when(dedupRepository.tryInsert("feishu", "om_123")).thenReturn(true);

        byte[] frameBytes = frameBytes("event", "svc_001", "{\"event\":{}}");

        Optional<byte[]> ackBytes = dispatcher.dispatch(frameBytes, config);

        assertThat(ackBytes).isPresent();
        Pbbp2.Frame ackFrame = Pbbp2.Frame.parseFrom(ackBytes.orElseThrow());
        Map<String, String> ackHeaders = headers(ackFrame);
        assertThat(ackHeaders.get("biz_rt")).isEqualTo("0");
        assertThat(ackHeaders.get("service_id")).isEqualTo("svc_001");
        verify(router).routeAsync(message, config);
    }

    @Test
    @DisplayName("dispatch should ack duplicate event without routing")
    void dispatch_eventDedupHit_doesNotRoute() throws Exception {
        FeishuEventParser parser = mock(FeishuEventParser.class);
        ChannelMessageDedupRepository dedupRepository = mock(ChannelMessageDedupRepository.class);
        ChannelSessionRouter router = mock(ChannelSessionRouter.class);
        FeishuWsEventDispatcher dispatcher = new FeishuWsEventDispatcher(
                new ObjectMapper(), parser, dedupRepository, router);

        ChannelConfigDecrypted config = new ChannelConfigDecrypted(
                1L, "feishu", "", "{}", "{\"mode\":\"websocket\"}", 100L);
        ChannelMessage message = new ChannelMessage(
                "feishu",
                "oc_xxx",
                "ou_xxx",
                "om_duplicated",
                ChannelMessage.MessageType.TEXT,
                "hello",
                null,
                Instant.now(),
                Map.of());
        when(parser.parse(any(byte[].class), eq(config))).thenReturn(Optional.of(message));
        when(dedupRepository.tryInsert("feishu", "om_duplicated")).thenReturn(false);

        byte[] frameBytes = frameBytes("event", "svc_dup", "{\"event\":{}}");

        Optional<byte[]> ackBytes = dispatcher.dispatch(frameBytes, config);

        assertThat(ackBytes).isPresent();
        Pbbp2.Frame ackFrame = Pbbp2.Frame.parseFrom(ackBytes.orElseThrow());
        Map<String, String> ackHeaders = headers(ackFrame);
        assertThat(ackHeaders.get("biz_rt")).isEqualTo("0");
        verifyNoInteractions(router);
    }

    @Test
    @DisplayName("dispatch should reply pong for ping frame without routing")
    void dispatch_ping_repliesPong() throws Exception {
        FeishuEventParser parser = mock(FeishuEventParser.class);
        ChannelMessageDedupRepository dedupRepository = mock(ChannelMessageDedupRepository.class);
        ChannelSessionRouter router = mock(ChannelSessionRouter.class);
        FeishuWsEventDispatcher dispatcher = new FeishuWsEventDispatcher(
                new ObjectMapper(), parser, dedupRepository, router);

        ChannelConfigDecrypted config = new ChannelConfigDecrypted(
                1L, "feishu", "", "{}", "{\"mode\":\"websocket\"}", 100L);
        byte[] frameBytes = frameBytes("ping", "svc_ping", "");

        Optional<byte[]> pongBytes = dispatcher.dispatch(frameBytes, config);

        assertThat(pongBytes).isPresent();
        Pbbp2.Frame pongFrame = Pbbp2.Frame.parseFrom(pongBytes.orElseThrow());
        Map<String, String> pongHeaders = headers(pongFrame);
        assertThat(pongHeaders.get("type")).isEqualTo("pong");
        assertThat(pongHeaders.get("service_id")).isEqualTo("svc_ping");
        assertThat(pongHeaders.get("biz_rt")).isEqualTo("0");
        verifyNoInteractions(parser, dedupRepository, router);
    }

    @Test
    @DisplayName("dispatch should return empty on malformed frame")
    void dispatch_malformedFrame_returnsEmpty() {
        FeishuEventParser parser = mock(FeishuEventParser.class);
        ChannelMessageDedupRepository dedupRepository = mock(ChannelMessageDedupRepository.class);
        ChannelSessionRouter router = mock(ChannelSessionRouter.class);
        FeishuWsEventDispatcher dispatcher = new FeishuWsEventDispatcher(
                new ObjectMapper(), parser, dedupRepository, router);
        ChannelConfigDecrypted config = new ChannelConfigDecrypted(
                1L, "feishu", "", "{}", "{\"mode\":\"websocket\"}", 100L);

        Optional<byte[]> out = dispatcher.dispatch("not-a-protobuf-frame".getBytes(StandardCharsets.UTF_8), config);

        assertThat(out).isEmpty();
        verifyNoInteractions(parser, dedupRepository, router);
    }

    private static byte[] frameBytes(String type, String serviceId, String payloadText) {
        Pbbp2.Frame.Builder builder = Pbbp2.Frame.newBuilder()
                .setSeqID(1L)
                .setLogID(1L)
                .setService(1)
                .setMethod(1)
                .addHeaders(header("type", type))
                .addHeaders(header("service_id", serviceId));
        if (payloadText != null && !payloadText.isEmpty()) {
            builder.setPayload(com.google.protobuf.ByteString.copyFromUtf8(payloadText));
        }
        return builder.build().toByteArray();
    }

    private static Pbbp2.Header header(String key, String value) {
        return Pbbp2.Header.newBuilder()
                .setKey(key)
                .setValue(value)
                .build();
    }

    private static Map<String, String> headers(Pbbp2.Frame frame) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (Pbbp2.Header header : frame.getHeadersList()) {
            headers.put(header.getKey(), header.getValue());
        }
        return headers;
    }
}
