package com.skillforge.server.improve;

import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.PromptVersionEntity;
import com.skillforge.server.improve.event.PromptPromotedEvent;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.PromptAbRunRepository;
import com.skillforge.server.repository.PromptVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AUTOEVOLVE-CLOSE-LOOP P1 — unit tests for
 * {@link PromptPromotionService#promoteByHuman}: it performs the atomic promote
 * while SKIPPING the four auto gates, is idempotent on an already-active
 * version, resets {@code abDeclineCount}, and publishes a delta=null event.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PromptPromotionService.promoteByHuman")
class PromptPromotionServicePromoteByHumanTest {

    @Mock private PromptAbRunRepository promptAbRunRepository;
    @Mock private PromptVersionRepository promptVersionRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private PromptPromotionService service;

    @BeforeEach
    void setUp() {
        service = new PromptPromotionService(
                promptAbRunRepository, promptVersionRepository, agentRepository, eventPublisher,
                new com.skillforge.server.config.EvolveThresholdProperties());
    }

    private PromptVersionEntity version(String id, String agentId, String status, int versionNumber) {
        PromptVersionEntity v = new PromptVersionEntity();
        v.setId(id);
        v.setAgentId(agentId);
        v.setStatus(status);
        v.setContent("NEW SYSTEM PROMPT");
        v.setVersionNumber(versionNumber);
        return v;
    }

    private AgentEntity agent(long id, String activeVersionId) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setActivePromptVersionId(activeVersionId);
        return a;
    }

    @Test
    @DisplayName("promotes a candidate while skipping the 4 auto gates (cooldown/today/paused/delta)")
    void promoteByHuman_skipsAutoGates_promotes() {
        PromptVersionEntity candidate = version("v-new", "7", "candidate", 5);
        PromptVersionEntity oldActive = version("v-old", "7", "active", 4);
        // Agent state that WOULD block an automatic promote: paused + just promoted.
        AgentEntity a = agent(7L, "v-old");
        a.setAutoImprovePaused(true);
        a.setLastPromotedAt(Instant.now());           // inside 24h cooldown
        a.setAbDeclineCount(2);

        when(promptVersionRepository.findById("v-new")).thenReturn(Optional.of(candidate));
        when(agentRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(a));
        when(promptVersionRepository.findById("v-old")).thenReturn(Optional.of(oldActive));

        PromotionResult result = service.promoteByHuman("v-new", "7", 42L);

        assertThat(result.status()).isEqualTo("promoted");
        // candidate activated
        assertThat(candidate.getStatus()).isEqualTo("active");
        assertThat(candidate.getPromotedAt()).isNotNull();
        // old active deprecated
        assertThat(oldActive.getStatus()).isEqualTo("deprecated");
        assertThat(oldActive.getDeprecatedAt()).isNotNull();
        // agent updated + decline streak reset
        assertThat(a.getActivePromptVersionId()).isEqualTo("v-new");
        assertThat(a.getSystemPrompt()).isEqualTo("NEW SYSTEM PROMPT");
        assertThat(a.getLastPromotedAt()).isNotNull();
        assertThat(a.getAbDeclineCount()).isEqualTo(0);
        // never touched the A/B run repo (no abRun in this path)
        verify(promptAbRunRepository, never()).save(any());
    }

    @Test
    @DisplayName("publishes a PromptPromotedEvent with delta=null and the operator userId")
    void promoteByHuman_publishesEvent_withNullDelta() {
        PromptVersionEntity candidate = version("v-new", "7", "candidate", 5);
        AgentEntity a = agent(7L, null);   // no prior active version

        when(promptVersionRepository.findById("v-new")).thenReturn(Optional.of(candidate));
        when(agentRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(a));

        service.promoteByHuman("v-new", "7", 42L);

        ArgumentCaptor<PromptPromotedEvent> captor = ArgumentCaptor.forClass(PromptPromotedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        PromptPromotedEvent event = captor.getValue();
        assertThat(event.agentId()).isEqualTo("7");
        assertThat(event.versionId()).isEqualTo("v-new");
        assertThat(event.deltaPassRate()).isNull();
        assertThat(event.versionNumber()).isEqualTo(5);
        assertThat(event.userId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("idempotent: an already-active version is a no-op (no write, no event)")
    void promoteByHuman_alreadyActive_isNoop() {
        PromptVersionEntity candidate = version("v-new", "7", "active", 5);
        when(promptVersionRepository.findById("v-new")).thenReturn(Optional.of(candidate));

        PromotionResult result = service.promoteByHuman("v-new", "7", 42L);

        assertThat(result.status()).isEqualTo("promoted");
        // no agent lock, no save, no event
        verify(agentRepository, never()).findByIdForUpdate(any());
        verify(promptVersionRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("ownership: a version belonging to another agent → IllegalArgumentException")
    void promoteByHuman_ownershipMismatch_throws() {
        PromptVersionEntity candidate = version("v-new", "9", "candidate", 5);  // belongs to agent 9
        when(promptVersionRepository.findById("v-new")).thenReturn(Optional.of(candidate));

        assertThatThrownBy(() -> service.promoteByHuman("v-new", "7", 42L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("belongs to agent 9");
        verify(agentRepository, never()).findByIdForUpdate(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("version not found → IllegalArgumentException")
    void promoteByHuman_versionNotFound_throws() {
        when(promptVersionRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.promoteByHuman("missing", "7", 42L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Prompt version not found");
    }
}
