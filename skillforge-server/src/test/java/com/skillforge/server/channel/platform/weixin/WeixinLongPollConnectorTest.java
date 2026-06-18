package com.skillforge.server.channel.platform.weixin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.channel.platform.feishu.FeishuWsReconnectPolicy;
import com.skillforge.server.channel.router.ChannelSessionRouter;
import com.skillforge.server.channel.spi.ChannelConfigDecrypted;
import com.skillforge.server.channel.spi.ChannelMessage;
import com.skillforge.server.repository.ChannelMessageDedupRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeixinLongPollConnectorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ChannelConfigDecrypted config() {
        return new ChannelConfigDecrypted(
                7L, "weixin", "",
                "{\"bot_token\":\"tok\",\"baseurl\":null}",
                "{\"mode\":\"push\"}", 100L);
    }

    private JsonNode msgs(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("routes a text msg, advances cursor, persists it; stops cleanly")
    void routesTextAndPersistsCursor() throws Exception {
        WeixinIlinkClient client = mock(WeixinIlinkClient.class);
        WeixinMessageParser parser = new WeixinMessageParser();
        WeixinCursorStore cursorStore = mock(WeixinCursorStore.class);
        ChannelMessageDedupRepository dedup = mock(ChannelMessageDedupRepository.class);
        ChannelSessionRouter router = mock(ChannelSessionRouter.class);

        when(cursorStore.readCursor(anyString())).thenReturn("");
        when(dedup.tryInsert(eq("weixin"), anyString())).thenReturn(true);

        JsonNode batch = msgs("""
                [ {"from_user_id":"userA","message_type":1,"context_token":"ctx",
                   "item_list":[{"type":1,"text_item":{"text":"hi"}}]} ]
                """);

        CountDownLatch persisted = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger(0);
        when(client.getUpdates(anyString(), anyString(), any(), any())).thenAnswer(inv -> {
            if (calls.getAndIncrement() == 0) {
                return new WeixinIlinkClient.GetUpdatesResult("cursor-2", 35000L, batch);
            }
            // subsequent polls: empty, slow enough that the test stops first
            Thread.sleep(50);
            return new WeixinIlinkClient.GetUpdatesResult("cursor-2", 35000L, msgs("[]"));
        });
        // signal once writeCursor is called with the advanced cursor
        org.mockito.Mockito.doAnswer(inv -> {
            if ("cursor-2".equals(inv.getArgument(1))) {
                persisted.countDown();
            }
            return null;
        }).when(cursorStore).writeCursor(eq(7L), anyString());

        WeixinLongPollConnector connector = new WeixinLongPollConnector(
                client, parser, cursorStore, dedup, router, objectMapper,
                FeishuWsReconnectPolicy.defaultPolicy());

        connector.start(config());
        boolean ok = persisted.await(3, TimeUnit.SECONDS);
        connector.stop();

        assertThat(ok).as("writeCursor(cursor-2) called").isTrue();
        verify(router, atLeastOnce()).routeAsync(any(ChannelMessage.class), any());
        verify(cursorStore, atLeastOnce()).writeCursor(eq(7L), eq("cursor-2"));
    }

    @Test
    @DisplayName("UNSUPPORTED media msg is NOT routed (no empty agent turn)")
    void unsupportedMediaNotRouted() throws Exception {
        WeixinIlinkClient client = mock(WeixinIlinkClient.class);
        WeixinMessageParser parser = new WeixinMessageParser();
        WeixinCursorStore cursorStore = mock(WeixinCursorStore.class);
        ChannelMessageDedupRepository dedup = mock(ChannelMessageDedupRepository.class);
        ChannelSessionRouter router = mock(ChannelSessionRouter.class);

        when(cursorStore.readCursor(anyString())).thenReturn("");

        JsonNode batch = msgs("""
                [ {"from_user_id":"userB","message_type":1,"context_token":"c",
                   "item_list":[{"type":2,"image_item":{"url":"http://x"}}]} ]
                """);

        CountDownLatch persisted = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger(0);
        when(client.getUpdates(anyString(), anyString(), any(), any())).thenAnswer(inv -> {
            if (calls.getAndIncrement() == 0) {
                return new WeixinIlinkClient.GetUpdatesResult("c2", 35000L, batch);
            }
            Thread.sleep(50);
            return new WeixinIlinkClient.GetUpdatesResult("c2", 35000L, msgs("[]"));
        });
        org.mockito.Mockito.doAnswer(inv -> {
            persisted.countDown();
            return null;
        }).when(cursorStore).writeCursor(eq(7L), anyString());

        WeixinLongPollConnector connector = new WeixinLongPollConnector(
                client, parser, cursorStore, dedup, router, objectMapper,
                FeishuWsReconnectPolicy.defaultPolicy());

        connector.start(config());
        persisted.await(3, TimeUnit.SECONDS);
        connector.stop();

        // UNSUPPORTED skipped before dedup + routing
        verify(router, never()).routeAsync(any(ChannelMessage.class), any());
        verify(dedup, never()).tryInsert(anyString(), anyString());
    }
}
