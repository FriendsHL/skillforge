package com.skillforge.server.service;

import com.skillforge.core.skill.SkillPackageLoader;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.repository.SkillRepository;
import com.skillforge.server.skill.SkillStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plan r2 §7 — verify the new recordUsage(name, success, errorType) overload
 * uses atomic SQL UPDATE (not entity read-modify-write) and tolerates missing rows.
 */
@ExtendWith(MockitoExtension.class)
class SkillServiceRecordUsageTest {

    @Mock private SkillRepository skillRepository;
    @Mock private SkillRegistry skillRegistry;
    @Mock private SkillPackageLoader packageLoader;
    @Mock private SkillStorageService skillStorageService;

    private SkillService service;

    @BeforeEach
    void setUp() {
        service = new SkillService(skillRepository, skillRegistry, packageLoader, skillStorageService);
    }

    @Test
    @DisplayName("recordUsage success → atomic UPDATE with successInc=1, failureInc=0")
    void success_incrementsSuccessAndUsage() {
        when(skillRepository.incrementUsageByName(eq("MySkill"), anyInt(), anyInt())).thenReturn(1);

        service.recordUsage("MySkill", true, null);

        verify(skillRepository).incrementUsageByName("MySkill", 1, 0);
    }

    @Test
    @DisplayName("recordUsage failure (NOT_ALLOWED) → atomic UPDATE with successInc=0, failureInc=1")
    void failure_incrementsFailureAndUsage() {
        when(skillRepository.incrementUsageByName(eq("DeniedSkill"), anyInt(), anyInt())).thenReturn(1);

        service.recordUsage("DeniedSkill", false, "NOT_ALLOWED");

        verify(skillRepository).incrementUsageByName("DeniedSkill", 0, 1);
    }

    @Test
    @DisplayName("recordUsage with no matching row (e.g. built-in Java Tool) → noop, no exception")
    void unknownSkillName_silentlyNoop() {
        when(skillRepository.incrementUsageByName(eq("Bash"), anyInt(), anyInt())).thenReturn(0);

        // Must not throw — telemetry MUST NOT cause tool calls to fail.
        service.recordUsage("Bash", true, null);

        verify(skillRepository).incrementUsageByName("Bash", 1, 0);
    }

    @Test
    @DisplayName("recordUsage(null) is a defensive noop — no DB call")
    void nullName_isNoop() {
        service.recordUsage(null, true, null);
        service.recordUsage("", false, "EXECUTION");

        verify(skillRepository, never()).incrementUsageByName(org.mockito.ArgumentMatchers.any(),
                anyInt(), anyInt());
    }

    @Test
    @DisplayName("DB exception is swallowed — telemetry never breaks tool execution")
    void dbException_swallowed() {
        when(skillRepository.incrementUsageByName(eq("Boom"), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("DB down"));

        // Must not throw.
        assertThat(service).isNotNull();
        service.recordUsage("Boom", false, "EXECUTION");
    }
}
