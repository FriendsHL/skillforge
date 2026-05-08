package com.skillforge.server.controller;

import com.skillforge.server.memory.MemoryConsolidationScheduler;
import com.skillforge.server.memory.MemoryConsolidationScheduler.ConsolidationSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminMemoryConsolidationController")
class AdminMemoryConsolidationControllerTest {

    @Mock private MemoryConsolidationScheduler scheduler;

    @InjectMocks private AdminMemoryConsolidationController controller;

    @Test
    @DisplayName("triggerConsolidation without userId scans all active users")
    void triggerConsolidation_noFilter_scansAll() {
        when(scheduler.runOnce((Long) null)).thenReturn(new ConsolidationSummary(3, 3, 0));

        ResponseEntity<?> response = controller.triggerConsolidation(null);

        verify(scheduler).runOnce((Long) null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("ok", true)
                .containsEntry("ran", "memory-consolidation")
                .containsEntry("eligible", 3)
                .containsEntry("succeeded", 3)
                .containsEntry("failed", 0);
        assertThat(body.get("userIdFilter")).isNull();
    }

    @Test
    @DisplayName("triggerConsolidation with userId targets that single user")
    void triggerConsolidation_withFilter_targetsOne() {
        when(scheduler.runOnce(99L)).thenReturn(new ConsolidationSummary(1, 1, 0));

        ResponseEntity<?> response = controller.triggerConsolidation(99L);

        verify(scheduler).runOnce(99L);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("userIdFilter", 99L)
                .containsEntry("eligible", 1);
    }

    @Test
    @DisplayName("scheduler throws → 500 with safe error body")
    void triggerConsolidation_schedulerThrows_returns500() {
        when(scheduler.runOnce((Long) null)).thenThrow(new RuntimeException("boom"));

        ResponseEntity<?> response = controller.triggerConsolidation(null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("ok", false)
                .containsEntry("error", "boom");
    }
}
