package com.skillforge.server.memory;

import com.skillforge.server.config.MemoryProperties;
import com.skillforge.server.repository.SessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MemoryConsolidationScheduler")
class MemoryConsolidationSchedulerTest {

    @Mock private SessionRepository sessionRepository;
    @Mock private MemoryConsolidator memoryConsolidator;

    private MemoryConsolidationScheduler newScheduler(boolean enabled) {
        return new MemoryConsolidationScheduler(
                sessionRepository, memoryConsolidator, new MemoryProperties(), enabled);
    }

    @Test
    @DisplayName("happy path: consolidates each eligible user")
    void runOnce_happy_consolidatesEach() {
        when(sessionRepository.findDistinctUserIdsWithRecentUserMessage(any(Instant.class)))
                .thenReturn(List.of(11L, 22L));

        var summary = newScheduler(true).runOnce();

        verify(memoryConsolidator).consolidate(11L);
        verify(memoryConsolidator).consolidate(22L);
        assertThat(summary.eligible()).isEqualTo(2);
        assertThat(summary.succeeded()).isEqualTo(2);
        assertThat(summary.failed()).isEqualTo(0);
    }

    @Test
    @DisplayName("yaml off: short-circuits without query")
    void runOnce_yamlOff_skipsAll() {
        var summary = newScheduler(false).runOnce();

        verify(sessionRepository, never()).findDistinctUserIdsWithRecentUserMessage(any());
        verify(memoryConsolidator, never()).consolidate(anyLong());
        assertThat(summary.eligible()).isEqualTo(0);
    }

    @Test
    @DisplayName("0 eligible users: no consolidation runs")
    void runOnce_noEligibleUsers_noop() {
        when(sessionRepository.findDistinctUserIdsWithRecentUserMessage(any(Instant.class)))
                .thenReturn(List.of());

        var summary = newScheduler(true).runOnce();

        verify(memoryConsolidator, never()).consolidate(anyLong());
        assertThat(summary.eligible()).isEqualTo(0);
    }

    @Test
    @DisplayName("single user failure logs warn, continues to next user (INV-2)")
    void runOnce_oneUserFails_othersContinue() {
        when(sessionRepository.findDistinctUserIdsWithRecentUserMessage(any(Instant.class)))
                .thenReturn(List.of(11L, 22L));
        doThrow(new RuntimeException("DB glitch")).when(memoryConsolidator).consolidate(11L);

        var summary = newScheduler(true).runOnce();

        // Both users attempted, second still runs despite first throwing.
        verify(memoryConsolidator).consolidate(11L);
        verify(memoryConsolidator).consolidate(22L);
        assertThat(summary.eligible()).isEqualTo(2);
        assertThat(summary.succeeded()).isEqualTo(1);
        assertThat(summary.failed()).isEqualTo(1);
    }

    @Test
    @DisplayName("session repo throws: returns empty summary, no consolidate calls")
    void runOnce_repoThrows_returnsEmpty() {
        when(sessionRepository.findDistinctUserIdsWithRecentUserMessage(any(Instant.class)))
                .thenThrow(new RuntimeException("query failed"));

        var summary = newScheduler(true).runOnce();

        verify(memoryConsolidator, never()).consolidate(anyLong());
        assertThat(summary.eligible()).isEqualTo(0);
    }

    @Test
    @DisplayName("userId filter: targets that user only, skips repo lookup")
    void runOnce_withFilter_targetsSingleUser() {
        var summary = newScheduler(true).runOnce(99L);

        verify(sessionRepository, never()).findDistinctUserIdsWithRecentUserMessage(any());
        verify(memoryConsolidator, times(1)).consolidate(99L);
        verify(memoryConsolidator, never()).consolidate(eq(11L));
        assertThat(summary.eligible()).isEqualTo(1);
        assertThat(summary.succeeded()).isEqualTo(1);
    }
}
