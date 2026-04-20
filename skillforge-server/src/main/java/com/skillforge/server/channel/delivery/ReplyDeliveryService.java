package com.skillforge.server.channel.delivery;

import com.skillforge.server.channel.registry.ChannelAdapterRegistry;
import com.skillforge.server.channel.ChannelConfigService;
import com.skillforge.server.channel.spi.ChannelAdapter;
import com.skillforge.server.channel.spi.ChannelConfigDecrypted;
import com.skillforge.server.channel.spi.ChannelReply;
import com.skillforge.server.channel.spi.DeliveryResult;
import com.skillforge.server.entity.ChannelDeliveryEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Delivers AgentLoop replies to external platforms. Survives restarts via
 * t_channel_delivery persistence + @Scheduled poll.
 * <p>
 * 3-phase transaction pattern:
 * <ol>
 *   <li>Short tx: persistRecord / claimBatch → releases DB connection</li>
 *   <li>Outside tx: HTTP deliver (seconds to tens of seconds)</li>
 *   <li>Short tx: applyResult → writes final status</li>
 * </ol>
 */
@Service
public class ReplyDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(ReplyDeliveryService.class);
    private static final int BATCH_SIZE = 50;
    private static final long ORPHAN_CUTOFF_SEC = 120;

    private final DeliveryTransactionHelper tx;
    private final ChannelAdapterRegistry adapterRegistry;
    private final ChannelConfigService configService;

    public ReplyDeliveryService(DeliveryTransactionHelper tx,
                                ChannelAdapterRegistry adapterRegistry,
                                ChannelConfigService configService) {
        this.tx = tx;
        this.adapterRegistry = adapterRegistry;
        this.configService = configService;
    }

    /** First attempt right after event receipt; falls back to poll on failure. */
    public void deliver(ChannelReply reply, ChannelAdapter adapter,
                        ChannelConfigDecrypted config, String sessionId) {
        ChannelDeliveryEntity record = buildRecord(reply, sessionId);
        tx.persistRecord(record);

        DeliveryResult result;
        try {
            result = adapter.deliver(reply, config);
        } catch (Exception e) {
            log.error("Delivery [{}] threw: {}", record.getId(), e.getMessage(), e);
            result = DeliveryResult.retry(0, e.getMessage());
        }
        tx.applyResult(record.getId(), result, 0, adapter.maxRetries());
    }

    @Scheduled(fixedDelay = 30_000)
    public void pollAndRetry() {
        List<String> claimed = tx.claimBatch(Instant.now(), BATCH_SIZE);
        if (claimed.isEmpty()) return;

        for (String id : claimed) {
            try {
                ChannelDeliveryEntity record = tx.findById(id);
                if (record == null) continue;

                Optional<ChannelAdapter> adapterOpt = adapterRegistry.get(record.getPlatform());
                Optional<ChannelConfigDecrypted> cfgOpt =
                        configService.getDecryptedConfig(record.getPlatform());
                if (adapterOpt.isEmpty() || cfgOpt.isEmpty()) {
                    tx.markFailed(id, "adapter or config missing for " + record.getPlatform());
                    continue;
                }
                ChannelAdapter adapter = adapterOpt.get();
                ChannelConfigDecrypted cfg = cfgOpt.get();

                ChannelReply reply = new ChannelReply(
                        record.getInboundMessageId(),
                        record.getPlatform(),
                        record.getConversationId(),
                        record.getReplyText(),
                        true,
                        null);

                DeliveryResult result;
                try {
                    result = adapter.deliver(reply, cfg);
                } catch (Exception e) {
                    log.error("Retry HTTP [{}] threw: {}", id, e.getMessage(), e);
                    result = DeliveryResult.retry(0, e.getMessage());
                }
                tx.applyResult(id, result, record.getRetryCount(), adapter.maxRetries());
            } catch (Exception e) {
                log.error("Retry loop crashed for [{}]: {}", id, e.getMessage(), e);
                try {
                    tx.markFailed(id, "retry loop error: " + e.getMessage());
                } catch (Exception inner) {
                    log.error("Failed to mark [{}] failed: {}", id, inner.getMessage());
                }
            }
        }
    }

    /** Resets stuck IN_FLIGHT rows from crashed workers. */
    @Scheduled(fixedDelay = 120_000)
    public void recoverOrphanedInFlight() {
        int n = tx.resetOrphaned(Instant.now().minusSeconds(ORPHAN_CUTOFF_SEC));
        if (n > 0) log.info("Reset {} orphaned IN_FLIGHT delivery records", n);
    }

    private ChannelDeliveryEntity buildRecord(ChannelReply reply, String sessionId) {
        ChannelDeliveryEntity e = new ChannelDeliveryEntity();
        e.setId(UUID.randomUUID().toString());
        e.setPlatform(reply.platform());
        e.setConversationId(reply.conversationId());
        e.setInboundMessageId(reply.inboundMessageId());
        e.setSessionId(sessionId);
        e.setReplyText(reply.markdownText());
        // Insert as IN_FLIGHT so the 30s poller can't race the first HTTP attempt.
        // The poller's SKIP LOCKED claim only picks up PENDING/RETRY rows; we hand
        // off to RETRY/FAILED/DELIVERED via applyResult after the HTTP call.
        // Orphaned IN_FLIGHT rows (crashed worker) are re-armed by
        // recoverOrphanedInFlight after ORPHAN_CUTOFF_SEC (120s).
        e.setStatus("IN_FLIGHT");
        e.setRetryCount(0);
        Instant now = Instant.now();
        e.setScheduledAt(now);
        e.setCreatedAt(now);
        return e;
    }
}
