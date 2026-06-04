package com.skillforge.server.evolve;

import com.skillforge.observability.entity.LlmSpanEntity;
import com.skillforge.observability.repository.LlmSpanRepository;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * BC-M2b: {@link BadCaseClusterService} returns one representative
 * {@code (sessionId, failingSpanId)} per {@code (tool + error signature)}
 * cluster, sorted by occurrence count DESC.
 */
@ExtendWith(MockitoExtension.class)
class BadCaseClusterServiceTest {

    @Mock private SessionRepository sessionRepository;
    @Mock private LlmSpanRepository spanRepository;

    private BadCaseClusterService service;

    @BeforeEach
    void setUp() {
        service = new BadCaseClusterService(sessionRepository, spanRepository);
    }

    private SessionEntity prodSession(String id) {
        SessionEntity s = new SessionEntity();
        s.setId(id);
        s.setAgentId(7L);
        s.setOrigin(SessionEntity.ORIGIN_PRODUCTION);
        s.setCreatedAt(LocalDateTime.now());
        return s;
    }

    private LlmSpanEntity errorSpan(String spanId, String sessionId, String name,
                                    String error, long startedAtSec) {
        LlmSpanEntity s = new LlmSpanEntity();
        s.setSpanId(spanId);
        s.setSessionId(sessionId);
        s.setKind("tool");
        s.setName(name);
        s.setError(error);
        s.setStartedAt(Instant.ofEpochSecond(startedAtSec));
        return s;
    }

    @Test
    @DisplayName("clusters by (tool + masked signature): one representative per cluster, hottest first")
    void clustersBySignature() {
        when(sessionRepository.findByAgentId(7L)).thenReturn(List.of(prodSession("s1")));
        // Two Edit failures (paths differ — masked into one signature) + one Grep failure.
        when(spanRepository.findBySessionIdInOrderByStartedAtAsc(anyList())).thenReturn(List.of(
                errorSpan("e1", "s1", "Edit", "old_string not found in file", 100),
                errorSpan("e2", "s1", "Edit", "old_string not found in file", 200),
                errorSpan("g1", "s1", "Grep", "Path is not a directory: /tmp/eval/x/a.txt", 300)));

        List<BadCaseClusterService.RepresentativeSpan> reps = service.representativeSpans(7L, 30);

        assertThat(reps).hasSize(2);
        // Edit cluster (count 2) sorts before Grep cluster (count 1).
        assertThat(reps.get(0).toolName()).isEqualTo("Edit");
        assertThat(reps.get(0).failingSpanId()).isEqualTo("e1");   // earliest in cluster
        assertThat(reps.get(0).occurrenceCount()).isEqualTo(2);
        assertThat(reps.get(1).toolName()).isEqualTo("Grep");
        assertThat(reps.get(1).failingSpanId()).isEqualTo("g1");
    }

    @Test
    @DisplayName("no production sessions → empty list (no span query)")
    void noSessions_empty() {
        when(sessionRepository.findByAgentId(7L)).thenReturn(List.of());
        assertThat(service.representativeSpans(7L, 30)).isEmpty();
    }

    @Test
    @DisplayName("ignores successful + non-tool spans")
    void ignoresNonFailures() {
        when(sessionRepository.findByAgentId(7L)).thenReturn(List.of(prodSession("s1")));
        LlmSpanEntity ok = errorSpan("ok", "s1", "Edit", null, 100);   // no error
        LlmSpanEntity llm = errorSpan("llm", "s1", "model", "boom", 200);
        llm.setKind("llm");
        when(spanRepository.findBySessionIdInOrderByStartedAtAsc(anyList()))
                .thenReturn(List.of(ok, llm));

        assertThat(service.representativeSpans(7L, 30)).isEmpty();
    }
}
