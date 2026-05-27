package com.skillforge.server.tool.attribution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.attribution.AttributionEventBroadcaster;
import com.skillforge.server.entity.OptimizationEventEntity;
import com.skillforge.server.entity.SessionPatternEntity;
import com.skillforge.server.repository.OptimizationEventRepository;
import com.skillforge.server.repository.SessionPatternRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProposeOptimizationToolTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-05-15T10:00:00Z");

    @Mock private OptimizationEventRepository eventRepository;
    @Mock private SessionPatternRepository patternRepository;
    @Mock private AttributionEventBroadcaster broadcaster;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private ProposeOptimizationTool tool;

    @BeforeEach
    void setUp() {
        Clock fixed = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        tool = new ProposeOptimizationTool(eventRepository, patternRepository, objectMapper, fixed, broadcaster);
    }

    private Map<String, Object> validInput() {
        Map<String, Object> m = new HashMap<>();
        m.put("patternId", 42);
        m.put("agentId", 7);
        m.put("surface", "skill");
        m.put("changeType", "rewrite_skill_md");
        m.put("description", "Member sessions show repeated Bash retries.");
        m.put("expectedImpact", "Expect failure rate to drop ~30%.");
        m.put("confidence", 0.72);
        m.put("risk", "medium");
        return m;
    }

    @Test
    @DisplayName("happy path: persists event with stage=proposal_pending + 24h cooldown")
    void execute_happyPath_writesProposalPending() throws Exception {
        when(patternRepository.findById(42L)).thenReturn(Optional.of(new SessionPatternEntity()));
        ArgumentCaptor<OptimizationEventEntity> captor = ArgumentCaptor.forClass(OptimizationEventEntity.class);
        when(eventRepository.save(captor.capture())).thenAnswer(inv -> {
            OptimizationEventEntity arg = inv.getArgument(0);
            arg.setId(123L);
            return arg;
        });

        SkillContext ctx = new SkillContext();
        ctx.setSessionId("sess-curator-1");
        SkillResult result = tool.execute(validInput(), ctx);

        assertThat(result.isSuccess()).isTrue();
        OptimizationEventEntity saved = captor.getValue();
        assertThat(saved.getPatternId()).isEqualTo(42L);
        assertThat(saved.getAgentId()).isEqualTo(7L);
        assertThat(saved.getSurfaceType()).isEqualTo(OptimizationEventEntity.SURFACE_SKILL);
        assertThat(saved.getStage()).isEqualTo(OptimizationEventEntity.STAGE_PROPOSAL_PENDING);
        assertThat(saved.getConfidence()).isEqualByComparingTo(new BigDecimal("0.72"));
        assertThat(saved.getRisk()).isEqualTo(OptimizationEventEntity.RISK_MEDIUM);
        assertThat(saved.getAttributionSessionId()).isEqualTo("sess-curator-1");
        assertThat(saved.getCooldownExpiresAt())
                .isEqualTo(FIXED_NOW.plus(ProposeOptimizationTool.COOLDOWN_DURATION));
    }

    @Test
    @DisplayName("schema exposes memoryContextHash and memoryIds as optional fields")
    @SuppressWarnings("unchecked")
    void schema_memoryContextFields_optional() {
        ToolSchema schema = tool.getToolSchema();

        Map<String, Object> inputSchema = schema.getInputSchema();
        Map<String, Object> properties = (Map<String, Object>) inputSchema.get("properties");
        List<String> required = (List<String>) inputSchema.get("required");

        assertThat(properties).containsKeys("memoryContextHash", "memoryIds");
        assertThat(required).doesNotContain("memoryContextHash", "memoryIds");
        assertThat((Map<String, Object>) properties.get("memoryIds"))
                .containsEntry("type", "array");
    }

    @Test
    @DisplayName("optional memory context hash and numeric ids are persisted as JSON audit fields")
    void execute_withMemoryContext_persistsHashAndIds() {
        when(patternRepository.findById(42L)).thenReturn(Optional.of(new SessionPatternEntity()));
        ArgumentCaptor<OptimizationEventEntity> captor = ArgumentCaptor.forClass(OptimizationEventEntity.class);
        when(eventRepository.save(captor.capture())).thenAnswer(inv -> {
            OptimizationEventEntity arg = inv.getArgument(0);
            arg.setId(125L);
            return arg;
        });
        Map<String, Object> in = validInput();
        in.put("memoryContextHash", "  abc123  ");
        in.put("memoryIds", List.of(9, "10", "not-a-number", -2, 9));

        SkillResult result = tool.execute(in, null);

        assertThat(result.isSuccess()).isTrue();
        OptimizationEventEntity saved = captor.getValue();
        assertThat(saved.getMemoryContextHash()).isEqualTo("abc123");
        assertThat(saved.getMemoryContextMemoryIds()).isEqualTo("[9,10]");
    }

    @Test
    @DisplayName("V4 Phase 1.4: surface='behavior_rule' accepted (ratify #6 widened — V3 attribution dispatcher already wired)")
    void proposeOptimization_behaviorRuleSurface_accepts() throws Exception {
        when(patternRepository.findById(42L)).thenReturn(Optional.of(new SessionPatternEntity()));
        ArgumentCaptor<OptimizationEventEntity> captor = ArgumentCaptor.forClass(OptimizationEventEntity.class);
        when(eventRepository.save(captor.capture())).thenAnswer(inv -> {
            OptimizationEventEntity arg = inv.getArgument(0);
            arg.setId(124L);
            return arg;
        });

        Map<String, Object> in = validInput();
        in.put("surface", "behavior_rule");
        in.put("changeType", "rewrite_behavior_rule");

        SkillResult result = tool.execute(in, null);

        assertThat(result.isSuccess()).isTrue();
        OptimizationEventEntity saved = captor.getValue();
        assertThat(saved.getSurfaceType()).isEqualTo(OptimizationEventEntity.SURFACE_BEHAVIOR_RULE);
        assertThat(saved.getStage()).isEqualTo(OptimizationEventEntity.STAGE_PROPOSAL_PENDING);
        assertThat(saved.getCooldownExpiresAt())
                .isEqualTo(FIXED_NOW.plus(ProposeOptimizationTool.COOLDOWN_DURATION));
    }

    @Test
    @DisplayName("confidence > 1.0 rejected (must be in [0,1])")
    void execute_confidenceOutOfRange_rejected() {
        Map<String, Object> in = validInput();
        in.put("confidence", 1.5);

        SkillResult result = tool.execute(in, null);

        assertThat(result.isSuccess()).isFalse();
        verify(eventRepository, never()).save(any());
    }

    @Test
    @DisplayName("pattern not found rejected (LLM-readable error before DB FK violation)")
    void execute_patternMissing_rejectedBeforeSave() {
        when(patternRepository.findById(anyLong())).thenReturn(Optional.empty());

        SkillResult result = tool.execute(validInput(), null);

        assertThat(result.isSuccess()).isFalse();
        verify(eventRepository, never()).save(any());
    }

    @Test
    @DisplayName("Phase 1.3 sentinel UPDATE: dispatch_initiated row is updated in place to proposal_pending (not new INSERT)")
    void propose_updatesSentinel_whenDispatchInitiatedExists() {
        when(patternRepository.findById(42L)).thenReturn(Optional.of(new SessionPatternEntity()));
        // Pre-existing sentinel row — id=555 already persisted by dispatcher.
        OptimizationEventEntity sentinel = new OptimizationEventEntity();
        sentinel.setId(555L);
        sentinel.setPatternId(42L);
        sentinel.setStage(OptimizationEventEntity.STAGE_DISPATCH_INITIATED);
        when(eventRepository.findByPatternIdAndStage(42L,
                OptimizationEventEntity.STAGE_DISPATCH_INITIATED))
                .thenReturn(List.of(sentinel));
        ArgumentCaptor<OptimizationEventEntity> captor = ArgumentCaptor.forClass(OptimizationEventEntity.class);
        when(eventRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        SkillResult result = tool.execute(validInput(), null);

        assertThat(result.isSuccess()).isTrue();
        OptimizationEventEntity saved = captor.getValue();
        // Crucial: same id as the sentinel — UPDATE not INSERT.
        assertThat(saved.getId()).isEqualTo(555L);
        assertThat(saved.getStage()).isEqualTo(OptimizationEventEntity.STAGE_PROPOSAL_PENDING);
        assertThat(saved.getCooldownExpiresAt())
                .isEqualTo(FIXED_NOW.plus(ProposeOptimizationTool.COOLDOWN_DURATION));
        // Curator's per-call payload populated on the same row.
        assertThat(saved.getDescription()).contains("Bash retries");
        assertThat(saved.getConfidence()).isEqualByComparingTo(new BigDecimal("0.72"));
    }

    @Test
    @DisplayName("Phase 1.3 sentinel guard: row found but with mismatched stage triggers wrapped IllegalStateException")
    void propose_returnsErrorWhenSentinelStageMismatched() {
        when(patternRepository.findById(42L)).thenReturn(Optional.of(new SessionPatternEntity()));
        // Hand-edited sentinel that somehow has stage=ab_running — defensive guard
        // ensures we don't silently overwrite mid-pipeline state.
        OptimizationEventEntity broken = new OptimizationEventEntity();
        broken.setId(666L);
        broken.setPatternId(42L);
        broken.setStage(OptimizationEventEntity.STAGE_AB_RUNNING);
        // Repository returns it because the test stubs it for the dispatch_initiated
        // query — simulates a corrupted finder result. In production the stage
        // filter would prevent this; the explicit guard catches the inconsistency.
        when(eventRepository.findByPatternIdAndStage(42L,
                OptimizationEventEntity.STAGE_DISPATCH_INITIATED))
                .thenReturn(List.of(broken));

        SkillResult result = tool.execute(validInput(), null);

        assertThat(result.isSuccess()).isFalse();
        // Wrapped by the tool's catch-all into SkillResult.error.
        assertThat(result.getError()).contains("Expected dispatch_initiated sentinel");
        verify(eventRepository, never()).save(any());
    }
}
