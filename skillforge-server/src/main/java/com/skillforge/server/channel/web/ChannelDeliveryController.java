package com.skillforge.server.channel.web;

import com.skillforge.server.channel.delivery.DeliveryTransactionHelper;
import com.skillforge.server.entity.ChannelDeliveryEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Operator controls over outbound deliveries.
 * <p>
 * Drop: mark a delivery as terminally failed with reason {@code user_ignored}.
 * The V17 CHECK constraint restricts status to PENDING/IN_FLIGHT/RETRY/DELIVERED/FAILED,
 * so "drop" is represented as FAILED + failure reason rather than a new DROPPED status.
 */
@RestController
@RequestMapping("/api/channel-deliveries")
public class ChannelDeliveryController {

    private static final String USER_IGNORED_REASON = "user_ignored";

    private final DeliveryTransactionHelper tx;

    public ChannelDeliveryController(DeliveryTransactionHelper tx) {
        this.tx = tx;
    }

    @PostMapping("/{id}/drop")
    public ResponseEntity<?> drop(@PathVariable String id) {
        ChannelDeliveryEntity record = tx.findById(id);
        if (record == null) {
            return ResponseEntity.status(404).body(Map.of("error", "not found"));
        }
        if ("DELIVERED".equals(record.getStatus())) {
            return ResponseEntity.status(409)
                    .body(Map.of("error", "already delivered, cannot drop"));
        }
        if ("FAILED".equals(record.getStatus())) {
            return ResponseEntity.ok(Map.of("status", "FAILED", "reason", record.getLastError()));
        }
        tx.markFailed(id, USER_IGNORED_REASON);
        return ResponseEntity.ok(Map.of("status", "FAILED", "reason", USER_IGNORED_REASON));
    }

    /**
     * Operator-initiated retry. Resets status to PENDING with scheduledAt=NOW() so
     * the scheduled poller picks it up on the next tick. retryCount is preserved
     * (deliberately — this is a manual "try again", not a fresh attempt budget).
     * <p>
     * Guards: DELIVERED (already succeeded) and IN_FLIGHT (currently being delivered)
     * both return 409. FAILED / RETRY / PENDING are all requeueable.
     */
    @PostMapping("/{id}/retry")
    public ResponseEntity<?> retry(@PathVariable String id) {
        ChannelDeliveryEntity record = tx.findById(id);
        if (record == null) {
            return ResponseEntity.status(404).body(Map.of("error", "not found"));
        }
        String status = record.getStatus();
        if ("DELIVERED".equals(status)) {
            return ResponseEntity.status(409)
                    .body(Map.of("error", "already delivered"));
        }
        if ("IN_FLIGHT".equals(status)) {
            return ResponseEntity.status(409)
                    .body(Map.of("error", "delivery in flight"));
        }
        int n = tx.requeueForRetry(id, Instant.now());
        if (n == 0) {
            return ResponseEntity.status(404).body(Map.of("error", "not found"));
        }
        return ResponseEntity.ok(Map.of(
                "status", "PENDING",
                "retryCount", record.getRetryCount()));
    }
}
