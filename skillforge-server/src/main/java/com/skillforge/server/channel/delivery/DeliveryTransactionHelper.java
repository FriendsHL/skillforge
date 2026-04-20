package com.skillforge.server.channel.delivery;

import com.skillforge.server.channel.spi.DeliveryResult;
import com.skillforge.server.entity.ChannelDeliveryEntity;
import com.skillforge.server.repository.ChannelDeliveryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Hosts the short DB transactions for delivery lifecycle. Separate @Service so
 * {@link ReplyDeliveryService#pollAndRetry()} invokes these via a Spring proxy
 * (avoids self-invocation breaking @Transactional).
 */
@Service
public class DeliveryTransactionHelper {

    private static final Logger log = LoggerFactory.getLogger(DeliveryTransactionHelper.class);

    private final ChannelDeliveryRepository deliveryRepo;

    public DeliveryTransactionHelper(ChannelDeliveryRepository deliveryRepo) {
        this.deliveryRepo = deliveryRepo;
    }

    @Transactional
    public ChannelDeliveryEntity persistRecord(ChannelDeliveryEntity record) {
        return deliveryRepo.save(record);
    }

    @Transactional
    public List<String> claimBatch(Instant now, int batchSize) {
        return deliveryRepo.claimBatch(now, batchSize);
    }

    @Transactional(readOnly = true)
    public ChannelDeliveryEntity findById(String id) {
        return deliveryRepo.findById(id).orElse(null);
    }

    @Transactional
    public void applyResult(String deliveryId, DeliveryResult result,
                            int attempt, int maxRetries) {
        if (result.success()) {
            deliveryRepo.markDelivered(deliveryId, Instant.now());
            return;
        }
        if (result.permanent() || attempt >= maxRetries) {
            String reason = "permanent=" + result.permanent()
                    + ", attempt=" + attempt
                    + (result.errorMessage() != null ? ", err=" + result.errorMessage() : "");
            deliveryRepo.markFailed(deliveryId, reason);
            log.warn("Delivery [{}] failed terminally: {}", deliveryId, reason);
            return;
        }
        long delayMs = result.retryAfterMs() > 0
                ? result.retryAfterMs()
                : (long) Math.pow(4, attempt) * 60_000L;
        deliveryRepo.scheduleRetry(deliveryId, attempt + 1,
                Instant.now().plusMillis(delayMs),
                result.errorMessage());
    }

    @Transactional
    public void markFailed(String id, String reason) {
        deliveryRepo.markFailed(id, reason);
    }

    /** Operator-initiated retry: reset to PENDING now; poller picks it up next tick. */
    @Transactional
    public int requeueForRetry(String id, Instant now) {
        return deliveryRepo.requeue(id, now);
    }

    @Transactional
    public int resetOrphaned(Instant cutoff) {
        return deliveryRepo.resetOrphanedInFlight(cutoff);
    }
}
