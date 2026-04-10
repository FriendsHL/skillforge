package com.skillforge.server.init;

import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.List;

/**
 * One-shot startup hook: backfill {@code lastUserMessageAt} for sessions created
 * before the column existed (2026-04-09). The column was added so the auto-compact
 * B3 idle-gap trigger could compute "time since user last spoke" without confusing
 * it with internal status writes that bump {@code @LastModifiedDate updatedAt}.
 *
 * <p>Old rows have {@code lastUserMessageAt = NULL}. The B3 check correctly skips
 * NULL (so it's not a crash bug), but it also means B3 never fires for legacy
 * sessions until the user sends a brand new message — the user could come back
 * to a 30-day-old session and it would not get an idle-gap compact on the next
 * turn. This backfill seeds those NULLs with {@code updatedAt} as a reasonable
 * approximation: not perfectly accurate (an internal write may have bumped it)
 * but always within an order of magnitude of actual last-user-activity.
 *
 * <p>The query is idempotent: after the first run all rows have non-null values
 * and subsequent runs update zero rows. Logged once per server boot.
 */
@Component
@Order(50) // before SubAgentStartupRecovery (@Order 100)
public class LastUserMessageAtBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LastUserMessageAtBackfill.class);

    private final SessionRepository sessionRepository;

    public LastUserMessageAtBackfill(SessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            // lastUserMessageAt is Instant; updatedAt is LocalDateTime. JPQL
            // bulk UPDATE can't auto-convert, so we iterate in Java.
            List<SessionEntity> stale = sessionRepository.findByLastUserMessageAtIsNull();
            if (stale.isEmpty()) {
                log.debug("LastUserMessageAtBackfill: no rows needed backfill");
                return;
            }
            ZoneId zone = ZoneId.systemDefault();
            int updated = 0;
            for (SessionEntity s : stale) {
                if (s.getUpdatedAt() != null) {
                    s.setLastUserMessageAt(s.getUpdatedAt().atZone(zone).toInstant());
                    sessionRepository.save(s);
                    updated++;
                }
            }
            log.info("LastUserMessageAtBackfill: seeded {} session row(s) from updatedAt", updated);
        } catch (Exception e) {
            // backfill is best-effort — never block boot
            log.warn("LastUserMessageAtBackfill failed (continuing boot)", e);
        }
    }
}
