package com.skillforge.server.channel.platform.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.lark.oapi.ws.pb.Pbbp2;
import com.skillforge.server.channel.router.ChannelSessionRouter;
import com.skillforge.server.channel.spi.ChannelConfigDecrypted;
import com.skillforge.server.channel.spi.ChannelMessage;
import com.skillforge.server.repository.ChannelMessageDedupRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Handles Feishu websocket frames (protobuf Pbbp2.Frame):
 * ping/pong response, callback payload parsing, dedup, async routing and ACK generation.
 */
@Component
public class FeishuWsEventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(FeishuWsEventDispatcher.class);

    private final ObjectMapper objectMapper;
    private final FeishuEventParser parser;
    private final ChannelMessageDedupRepository dedupRepository;
    private final ChannelSessionRouter router;

    public FeishuWsEventDispatcher(
            ObjectMapper objectMapper,
            FeishuEventParser parser,
            ChannelMessageDedupRepository dedupRepository,
            ChannelSessionRouter router) {
        this.objectMapper = objectMapper;
        this.parser = parser;
        this.dedupRepository = dedupRepository;
        this.router = router;
    }

    public Optional<byte[]> dispatch(
            byte[] frameBytes,
            ChannelConfigDecrypted config) {
        try {
            Pbbp2.Frame frame = Pbbp2.Frame.parseFrom(frameBytes);
            Map<String, String> headers = headerMap(frame);
            String type = headers.getOrDefault("type", "");

            if ("ping".equalsIgnoreCase(type)) {
                return Optional.of(buildPongFrame(frame, headers));
            }

            byte[] payload = frame.getPayload().toByteArray();
            if ("event".equalsIgnoreCase(type) && payload.length > 0) {
                Optional<ChannelMessage> parsed = parser.parse(payload, config);
                if (parsed.isPresent()) {
                    ChannelMessage message = parsed.get();
                    boolean fresh = dedupRepository.tryInsert(message.platform(), message.platformMessageId());
                    if (fresh) {
                        router.routeAsync(message, config);
                    } else {
                        log.debug("WS dedup hit: platform={} msgId={}", message.platform(), message.platformMessageId());
                    }
                }
            }

            return Optional.of(buildAckFrame(frame, headers));
        } catch (Exception e) {
            log.warn("Failed to dispatch Feishu WS frame", e);
            return Optional.empty();
        }
    }

    private Map<String, String> headerMap(Pbbp2.Frame frame) {
        Map<String, String> map = new LinkedHashMap<>();
        for (Pbbp2.Header h : frame.getHeadersList()) {
            map.put(h.getKey(), h.getValue());
        }
        return map;
    }

    private byte[] buildAckFrame(Pbbp2.Frame frame, Map<String, String> requestHeaders) throws Exception {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("statusCode", 200);
        response.put("headers", Map.of());
        response.put("data", null);
        byte[] responsePayload = objectMapper.writeValueAsString(response).getBytes(StandardCharsets.UTF_8);

        // Keep request correlation headers intact; add biz_rt to mark ACK success.
        Map<String, String> responseHeaders = new LinkedHashMap<>(requestHeaders);
        responseHeaders.put("biz_rt", "0");

        return rebuildFrame(frame, responseHeaders, ByteString.copyFrom(responsePayload));
    }

    private byte[] buildPongFrame(Pbbp2.Frame frame, Map<String, String> requestHeaders) {
        Map<String, String> responseHeaders = new LinkedHashMap<>(requestHeaders);
        responseHeaders.put("type", "pong");
        responseHeaders.put("biz_rt", "0");
        return rebuildFrame(frame, responseHeaders, ByteString.EMPTY);
    }

    private byte[] rebuildFrame(Pbbp2.Frame frame, Map<String, String> headers, ByteString payload) {
        Pbbp2.Frame.Builder builder = frame.toBuilder()
                .clearHeaders()
                .setPayload(payload);
        toHeaders(headers).forEach(builder::addHeaders);
        return builder.build().toByteArray();
    }

    private List<Pbbp2.Header> toHeaders(Map<String, String> headers) {
        return headers.entrySet().stream()
                .map(entry -> Pbbp2.Header.newBuilder()
                        .setKey(entry.getKey())
                        .setValue(entry.getValue())
                        .build())
                .toList();
    }
}
