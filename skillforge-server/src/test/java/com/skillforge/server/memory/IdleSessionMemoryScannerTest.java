package com.skillforge.server.memory;

import com.skillforge.server.config.MemoryProperties;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IdleSessionMemoryScannerTest {

    private MemoryProperties memoryProperties;
    private List<SessionEntity> idleCandidates;
    private List<SessionEntity> dailyCandidates;
    private final List<String> triggeredSessionIds = new ArrayList<>();
    private IdleSessionMemoryScanner scanner;

    @BeforeEach
    void setUp() {
        memoryProperties = new MemoryProperties();
        idleCandidates = List.of();
        dailyCandidates = List.of();
        triggeredSessionIds.clear();

        SessionRepository sessionRepository = (SessionRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{SessionRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findIdleExtractionCandidates" -> {
                        assertThat(args[0]).isInstanceOf(Instant.class);
                        assertThat(args[1]).isInstanceOf(Pageable.class);
                        yield idleCandidates;
                    }
                    case "findDailyExtractionCandidates" -> {
                        assertThat(args[0]).isInstanceOf(Instant.class);
                        assertThat(args[1]).isInstanceOf(Pageable.class);
                        yield dailyCandidates;
                    }
                    default -> null;
                }
        );

        SessionDigestExtractor digestExtractor = new SessionDigestExtractor(
                null, null, null, null, null, memoryProperties, null, null) {
            @Override
            public void triggerExtractionAsync(String sessionId) {
                triggeredSessionIds.add(sessionId);
            }
        };

        scanner = new IdleSessionMemoryScanner(sessionRepository, digestExtractor, memoryProperties);
    }

    @Test
    @DisplayName("idle scanner dispatches extraction for idle candidates")
    void scanIdleSessions_dispatchesCandidates() {
        idleCandidates = List.of(session("s1"), session("s2"));

        scanner.scanIdleSessions();

        assertThat(triggeredSessionIds).containsExactly("s1", "s2");
    }

    @Test
    @DisplayName("daily fallback dispatches stale unextracted candidates")
    void scanDailyFallback_dispatchesCandidates() {
        dailyCandidates = List.of(session("s3"));

        scanner.scanDailyFallback();

        assertThat(triggeredSessionIds).containsExactly("s3");
    }

    private SessionEntity session(String id) {
        SessionEntity session = new SessionEntity();
        session.setId(id);
        return session;
    }
}
