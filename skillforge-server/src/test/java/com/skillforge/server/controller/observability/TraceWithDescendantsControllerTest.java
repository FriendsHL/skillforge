package com.skillforge.server.controller.observability;

import com.skillforge.server.controller.observability.dto.LlmTraceSummaryDto;
import com.skillforge.server.controller.observability.dto.TraceWithDescendantsDto;
import com.skillforge.server.service.observability.TraceDescendantsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TraceWithDescendantsControllerTest {

    @Mock
    private TraceDescendantsService service;

    private TraceWithDescendantsController controller;

    @BeforeEach
    void setUp() {
        controller = new TraceWithDescendantsController(service);
    }

    private TraceWithDescendantsDto stubResponse() {
        LlmTraceSummaryDto root = new LlmTraceSummaryDto(
                "t-root", "s-root", 1L, 42L, "main", "main",
                "ok", null,
                Instant.parse("2026-05-03T10:00:00Z"),
                Instant.parse("2026-05-03T10:00:01Z"),
                1000L, 0, 0,
                0, 0, BigDecimal.ZERO, "live");
        return new TraceWithDescendantsDto(root, List.of(), List.of(), false);
    }

    @Test
    @DisplayName("controller forwards default max_depth=3 / max_descendants=20 to service")
    void getWithDescendants_defaultParams_passesDefaultsThrough() {
        TraceWithDescendantsDto stub = stubResponse();
        when(service.fetch("t-root", 3, 20, 42L)).thenReturn(stub);

        ResponseEntity<TraceWithDescendantsDto> resp =
                controller.getWithDescendants("t-root", 42L, 3, 20);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).isSameAs(stub);
        verify(service).fetch(eq("t-root"), eq(3), eq(20), eq(42L));
    }

    @Test
    @DisplayName("controller propagates 403 ResponseStatusException from ownership guard")
    void getWithDescendants_ownershipFail_propagatesAs403() {
        when(service.fetch("t-root", 3, 20, 99L))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Session not owned"));

        assertThatThrownBy(() -> controller.getWithDescendants("t-root", 99L, 3, 20))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }

    @Test
    @DisplayName("controller propagates 404 when service can't find trace")
    void getWithDescendants_traceMissing_propagatesAs404() {
        when(service.fetch("t-missing", 3, 20, 42L))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Trace not found"));

        assertThatThrownBy(() -> controller.getWithDescendants("t-missing", 42L, 3, 20))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    @DisplayName("controller forwards custom max_depth / max_descendants params")
    void getWithDescendants_customParams_forwarded() {
        TraceWithDescendantsDto stub = stubResponse();
        when(service.fetch("t-root", 5, 50, 42L)).thenReturn(stub);

        ResponseEntity<TraceWithDescendantsDto> resp =
                controller.getWithDescendants("t-root", 42L, 5, 50);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(service).fetch(eq("t-root"), eq(5), eq(50), eq(42L));
    }
}
