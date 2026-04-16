package com.skillforge.server.improve.event;

import com.skillforge.core.engine.ChatEventBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class PromptEventBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(PromptEventBroadcaster.class);

    private final ChatEventBroadcaster broadcaster;

    public PromptEventBroadcaster(ChatEventBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPromoted(PromptPromotedEvent event) {
        log.info("Broadcasting prompt_promoted: agentId={}, versionId={}, delta={}",
                event.agentId(), event.versionId(), event.deltaPassRate());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "prompt_promoted");
        payload.put("agentId", event.agentId());
        payload.put("versionId", event.versionId());
        payload.put("deltaPassRate", event.deltaPassRate());
        payload.put("versionNumber", event.versionNumber());
        if (event.userId() != null) {
            broadcaster.userEvent(event.userId(), payload);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaused(ImprovePausedEvent event) {
        log.info("Broadcasting improve_paused: agentId={}, declineCount={}",
                event.agentId(), event.abDeclineCount());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "improve_paused");
        payload.put("agentId", event.agentId());
        payload.put("abDeclineCount", event.abDeclineCount());
        // Broadcast to the triggering user; if not available, send to all connected users
        if (event.triggeredByUserId() != null) {
            broadcaster.userEvent(event.triggeredByUserId(), payload);
        }
    }
}
