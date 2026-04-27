package com.skillforge.server.memory;

import com.skillforge.server.config.MemoryProperties;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class IdleSessionMemoryScanner {

    private static final Logger log = LoggerFactory.getLogger(IdleSessionMemoryScanner.class);
    private static final int BATCH_SIZE = 100;

    private final SessionRepository sessionRepository;
    private final SessionDigestExtractor sessionDigestExtractor;
    private final MemoryProperties memoryProperties;

    public IdleSessionMemoryScanner(SessionRepository sessionRepository,
                                    SessionDigestExtractor sessionDigestExtractor,
                                    MemoryProperties memoryProperties) {
        this.sessionRepository = sessionRepository;
        this.sessionDigestExtractor = sessionDigestExtractor;
        this.memoryProperties = memoryProperties;
    }

    @Scheduled(
            fixedDelayString = "${skillforge.memory.extraction.idle-scanner-interval-minutes:10}",
            initialDelayString = "${skillforge.memory.extraction.idle-scanner-interval-minutes:10}",
            timeUnit = TimeUnit.MINUTES)
    public void scanIdleSessions() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(idleWindowMinutes()));
        List<SessionEntity> candidates = sessionRepository.findIdleExtractionCandidates(
                cutoff, PageRequest.of(0, BATCH_SIZE));
        if (candidates.isEmpty()) {
            return;
        }
        log.info("IdleSessionMemoryScanner: found {} idle extraction candidates", candidates.size());
        for (SessionEntity session : candidates) {
            sessionDigestExtractor.triggerExtractionAsync(session.getId());
        }
    }

    @Scheduled(cron = "0 0 3 * * *")
    public void scanDailyFallback() {
        Instant digestCutoff = Instant.now().minus(Duration.ofHours(24));
        List<SessionEntity> candidates = sessionRepository.findDailyExtractionCandidates(
                digestCutoff, PageRequest.of(0, BATCH_SIZE));
        if (candidates.isEmpty()) {
            return;
        }
        log.info("IdleSessionMemoryScanner: found {} daily fallback extraction candidates", candidates.size());
        for (SessionEntity session : candidates) {
            sessionDigestExtractor.triggerExtractionAsync(session.getId());
        }
    }

    private int idleWindowMinutes() {
        return Math.max(1, memoryProperties.getExtraction().getIdleWindowMinutes());
    }
}
