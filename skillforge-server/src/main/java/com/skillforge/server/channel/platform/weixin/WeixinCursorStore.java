package com.skillforge.server.channel.platform.weixin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.server.repository.ChannelConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists the weixin long-poll cursor ({@code get_updates_buf}) into the channel config's
 * {@code config_json} so a restart resumes from the last delivered message and we never replay
 * history (INV-1). Read from {@code config.configJson()} at start; written after each successful
 * poll batch.
 *
 * <p>Read-modify-write only touches the {@code cursor} key — other config_json fields
 * ({@code mode}, {@code channel_version}, ...) are preserved.
 */
@Component
public class WeixinCursorStore {

    private static final Logger log = LoggerFactory.getLogger(WeixinCursorStore.class);
    static final String CURSOR_KEY = "cursor";

    private final ChannelConfigRepository configRepo;
    private final ObjectMapper objectMapper;

    public WeixinCursorStore(ChannelConfigRepository configRepo, ObjectMapper objectMapper) {
        this.configRepo = configRepo;
        this.objectMapper = objectMapper;
    }

    /** Extract the persisted cursor from a raw config_json; "" if absent/invalid. */
    public String readCursor(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return "";
        }
        try {
            JsonNode node = objectMapper.readTree(configJson);
            return node.path(CURSOR_KEY).asText("");
        } catch (Exception e) {
            log.warn("weixin cursor parse failed, starting from empty cursor: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Persist the advanced cursor for the given config id. Best-effort: a failure here is logged
     * and swallowed so the poll loop keeps running (it will re-persist on the next batch). The
     * in-memory cursor in the connector remains authoritative for the running process.
     */
    @Transactional
    public void writeCursor(Long configId, String cursor) {
        if (configId == null) {
            return;
        }
        try {
            String raw = configRepo.findConfigJsonById(configId).orElse(null);
            if (raw == null && !configRepo.existsById(configId)) {
                return; // config deleted
            }
            ObjectNode node;
            if (raw == null || raw.isBlank()) {
                node = objectMapper.createObjectNode();
            } else {
                JsonNode parsed = objectMapper.readTree(raw);
                node = parsed.isObject() ? (ObjectNode) parsed : objectMapper.createObjectNode();
            }
            node.put(CURSOR_KEY, cursor == null ? "" : cursor);
            // Targeted update of config_json only — must NOT full-save the entity, which would
            // clobber credentials_json (bot_token) written concurrently by the QR-confirm bind.
            configRepo.updateConfigJson(configId, objectMapper.writeValueAsString(node));
        } catch (Exception e) {
            log.warn("weixin cursor persist failed for config {}: {}", configId, e.getMessage());
        }
    }
}
