package com.skillforge.server.mobile;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Component
public class MobileNotificationWorker {
    private static final Logger log = LoggerFactory.getLogger(MobileNotificationWorker.class);
    private static final int MAX_ATTEMPTS = 5;
    private final MobileNotificationRepository notificationRepository;
    private final MobileNotificationDeliveryRepository deliveryRepository;
    private final MobilePushTokenRepository tokenRepository;
    private final MobileDeviceRepository deviceRepository;
    private final MobilePushTokenCipher cipher;
    private final ApnsClient apnsClient;
    private final ObjectMapper objectMapper;

    public MobileNotificationWorker(MobileNotificationRepository notificationRepository,
                                    MobileNotificationDeliveryRepository deliveryRepository,
                                    MobilePushTokenRepository tokenRepository,
                                    MobileDeviceRepository deviceRepository,
                                    MobilePushTokenCipher cipher, ApnsClient apnsClient,
                                    ObjectMapper objectMapper) {
        this.notificationRepository = notificationRepository; this.deliveryRepository = deliveryRepository;
        this.tokenRepository = tokenRepository; this.deviceRepository = deviceRepository;
        this.cipher = cipher; this.apnsClient = apnsClient; this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${skillforge.mobile.push.poll-ms:10000}",
            initialDelayString = "${skillforge.mobile.push.initial-delay-ms:15000}")
    public void poll() {
        if (!cipher.isConfigured() || !apnsClient.isConfigured()) return;
        fanOutMissingDeliveries();
        for (MobileNotificationDeliveryEntity delivery : deliveryRepository
                .findTop50ByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                        List.of("pending", "sending"), Instant.now())) {
            deliver(delivery);
        }
    }

    private void fanOutMissingDeliveries() {
        for (MobileNotificationEntity notification : notificationRepository.findTop100ByOrderByCreatedAtDesc()) {
            Set<UUID> activeDevices = new HashSet<>();
            for (MobileDeviceEntity device : deviceRepository.findByUserIdAndStatus(
                    notification.getUserId(), MobileDeviceEntity.STATUS_ACTIVE)) activeDevices.add(device.getId());
            for (MobilePushTokenEntity token : tokenRepository.findByStatus(MobilePushTokenEntity.STATUS_ACTIVE)) {
                if (!activeDevices.contains(token.getDeviceId())
                        || deliveryRepository.existsByNotificationIdAndDeviceId(notification.getId(), token.getDeviceId())) continue;
                Instant now = Instant.now();
                MobileNotificationDeliveryEntity row = new MobileNotificationDeliveryEntity();
                row.setId(UUID.randomUUID()); row.setNotificationId(notification.getId()); row.setDeviceId(token.getDeviceId());
                row.setPushTokenId(token.getId()); row.setStatus("pending"); row.setNextAttemptAt(now);
                row.setCreatedAt(now); row.setUpdatedAt(now);
                try {
                    deliveryRepository.save(row);
                } catch (org.springframework.dao.DataIntegrityViolationException duplicate) {
                    log.debug("Mobile notification delivery already exists: notificationId={} deviceId={}",
                            notification.getId(), token.getDeviceId());
                }
            }
        }
    }

    private void deliver(MobileNotificationDeliveryEntity delivery) {
        MobileNotificationEntity notification = notificationRepository.findById(delivery.getNotificationId()).orElse(null);
        MobilePushTokenEntity token = tokenRepository.findById(delivery.getPushTokenId()).orElse(null);
        if (notification == null || token == null || !MobilePushTokenEntity.STATUS_ACTIVE.equals(token.getStatus())) {
            fail(delivery, "notification or active token missing", true); return;
        }
        delivery.setStatus("sending"); delivery.setAttempts(delivery.getAttempts() + 1);
        delivery.setUpdatedAt(Instant.now()); deliveryRepository.save(delivery);
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "aps", Map.of("alert", Map.of("title", notification.getTitle(), "body", notification.getBody()),
                            "sound", "default"),
                    "sessionId", notification.getSessionId(), "kind", notification.getKind()));
            ApnsClient.ApnsResult result = apnsClient.send(cipher.decrypt(token.getTokenCiphertext()), token.getEnvironment(), payload);
            if (result.delivered()) {
                delivery.setStatus("delivered"); delivery.setDeliveredAt(Instant.now());
                delivery.setApnsId(result.apnsId()); delivery.setLastError(null);
            } else {
                if (result.permanentFailure()) {
                    token.setStatus(MobilePushTokenEntity.STATUS_INACTIVE); token.setInvalidatedAt(Instant.now()); tokenRepository.save(token);
                }
                fail(delivery, result.reason(), result.permanentFailure()); return;
            }
            delivery.setUpdatedAt(Instant.now()); deliveryRepository.save(delivery);
        } catch (Exception e) {
            log.warn("Mobile push delivery failed: notificationId={} deviceId={} reason={}",
                    delivery.getNotificationId(), delivery.getDeviceId(), e.getClass().getSimpleName());
            fail(delivery, e.getClass().getSimpleName(), false);
        }
    }

    private void fail(MobileNotificationDeliveryEntity delivery, String reason, boolean permanent) {
        boolean exhausted = delivery.getAttempts() >= MAX_ATTEMPTS;
        delivery.setStatus(permanent || exhausted ? "failed" : "pending");
        delivery.setLastError(truncate(reason)); delivery.setUpdatedAt(Instant.now());
        delivery.setNextAttemptAt(Instant.now().plus(Math.min(300, 5L << Math.min(5, delivery.getAttempts())), ChronoUnit.SECONDS));
        deliveryRepository.save(delivery);
    }

    private static String truncate(String value) {
        if (value == null) return null; return value.length() <= 256 ? value : value.substring(0, 256);
    }
}
