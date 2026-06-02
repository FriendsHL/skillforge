package com.skillforge.server.improve;

import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.LlmRequest;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.BehaviorRuleVersionEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.BehaviorRuleVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
// LENIENT because the annotation-only structural test
// (requiresNewTxIsolation_annotationIsPresent) doesn't invoke the service —
// so the shared @BeforeEach stubs (versionRepository.save) are unused there
// and would trip Mockito's STRICT_STUBS default. The behavior tests
// (happy path + LLM failure) still rely on those stubs.
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BehaviorRuleImproverService")
class BehaviorRuleImproverServiceTest {

    @Mock private AgentRepository agentRepository;
    @Mock private BehaviorRuleVersionRepository versionRepository;

    private LlmProviderFactory llmProviderFactory;
    private BehaviorRuleImproverService service;
    private CapturingProvider provider;
    private final Map<String, BehaviorRuleVersionEntity> savedVersions = new HashMap<>();

    @BeforeEach
    void setUp() {
        llmProviderFactory = new LlmProviderFactory();
        provider = new CapturingProvider();
        llmProviderFactory.registerProvider("test", provider);

        LlmProperties props = new LlmProperties();
        props.setDefaultProvider("test");

        service = new BehaviorRuleImproverService(
                agentRepository, versionRepository, llmProviderFactory, props);

        when(versionRepository.save(any(BehaviorRuleVersionEntity.class)))
                .thenAnswer(inv -> {
                    BehaviorRuleVersionEntity e = inv.getArgument(0);
                    savedVersions.put(e.getId(), e);
                    return e;
                });
    }

    @Test
    @DisplayName("happy path: synchronous LLM fill produces a candidate with rulesJson populated + audit fields set")
    void happyPath_syncLlmFill_persistsCandidateWithRulesJson() {
        // Phase 1.1 reviewer fix W2: generateCandidateRulesFromAttribution no
        // longer reads agent state, so agent.setSystemPrompt(...) is no longer
        // needed as test setup — keeping only the id since AgentEntity lookup
        // by id is still done by startImprovementFromAttribution.
        AgentEntity agent = new AgentEntity();
        agent.setId(10L);
        when(agentRepository.findById(10L)).thenReturn(Optional.of(agent));

        // No active baseline in DB — first attribution for this agent.
        when(versionRepository.findByAgentIdAndStatus("10", BehaviorRuleVersionEntity.STATUS_ACTIVE))
                .thenReturn(Optional.empty());
        when(versionRepository.findMaxVersionNumber("10")).thenReturn(Optional.of(3));

        ImprovementStartResult result = service.startImprovementFromAttribution(
                42L, "10", "Agent should refuse destructive file ops on user-owned dirs.", 7L);

        assertThat(result.agentId()).isEqualTo("10");
        assertThat(result.abRunId()).isNull();  // V4 Phase 1.1: no A/B trigger yet
        assertThat(result.promptVersionId()).isNotBlank();
        assertThat(result.status()).isEqualTo("PENDING");

        // LLM prompt was constructed correctly.
        assertThat(provider.lastUserMessage)
                .contains("Current rules JSON:")
                .contains("[]")  // empty baseline propagated
                .contains("Attribution rationale (from curator):")
                .contains("destructive file ops");

        // Persisted entity has the LLM output + audit fields.
        ArgumentCaptor<BehaviorRuleVersionEntity> captor =
                ArgumentCaptor.forClass(BehaviorRuleVersionEntity.class);
        verify(versionRepository).save(captor.capture());

        BehaviorRuleVersionEntity saved = captor.getValue();
        assertThat(saved.getAgentId()).isEqualTo("10");
        assertThat(saved.getVersionNumber()).isEqualTo(4);  // max(3) + 1
        assertThat(saved.getStatus()).isEqualTo(BehaviorRuleVersionEntity.STATUS_CANDIDATE);
        assertThat(saved.getSource()).isEqualTo(BehaviorRuleVersionEntity.SOURCE_ATTRIBUTION);
        assertThat(saved.getSourceEventId()).isEqualTo(42L);
        assertThat(saved.getRulesJson()).isEqualTo("[{\"id\":\"r-improved\"}]");
        assertThat(saved.getImprovementRationale())
                .isEqualTo("Agent should refuse destructive file ops on user-owned dirs.");
        // baselineVersionId is null because no prior active row existed.
        assertThat(saved.getBaselineVersionId()).isNull();
    }

    @Test
    @DisplayName("startImprovementFromBaseVersion: loads the SPECIFIED base version's rules + sets baselineVersionId (§8 #1)")
    void startFromBaseVersion_loadsSpecifiedBaseAndSetsBaselineVersionId() {
        AgentEntity agent = new AgentEntity();
        agent.setId(10L);
        when(agentRepository.findById(10L)).thenReturn(Optional.of(agent));

        // The carry-forward base is a SPECIFIC version (the current best), NOT the
        // agent's active baseline — its rulesJson must reach the LLM prompt.
        BehaviorRuleVersionEntity base = new BehaviorRuleVersionEntity();
        base.setId("best-v");
        base.setAgentId("10");
        base.setRulesJson("[{\"id\":\"best-rule\"}]");
        when(versionRepository.findById("best-v")).thenReturn(Optional.of(base));
        when(versionRepository.findMaxVersionNumber("10")).thenReturn(Optional.of(5));

        ImprovementStartResult result = service.startImprovementFromBaseVersion(
                42L, "10", "best-v", "Tighten the refusal rule.", 7L);

        assertThat(result.promptVersionId()).isNotBlank();
        assertThat(result.status()).isEqualTo("PENDING");
        // The SPECIFIED base's rules were fed to the LLM (not "[]").
        assertThat(provider.lastUserMessage).contains("best-rule");

        ArgumentCaptor<BehaviorRuleVersionEntity> captor =
                ArgumentCaptor.forClass(BehaviorRuleVersionEntity.class);
        verify(versionRepository).save(captor.capture());
        BehaviorRuleVersionEntity saved = captor.getValue();
        assertThat(saved.getVersionNumber()).isEqualTo(6);     // max(5)+1
        assertThat(saved.getBaselineVersionId()).isEqualTo("best-v");
        assertThat(saved.getSourceEventId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("startImprovementFromBaseVersion: base version owned by another agent fails loud (W7)")
    void startFromBaseVersion_crossAgent_throws() {
        AgentEntity agent = new AgentEntity();
        agent.setId(10L);
        when(agentRepository.findById(10L)).thenReturn(Optional.of(agent));
        BehaviorRuleVersionEntity base = new BehaviorRuleVersionEntity();
        base.setId("foreign-v");
        base.setAgentId("99");   // belongs to a different agent
        base.setRulesJson("[]");
        when(versionRepository.findById("foreign-v")).thenReturn(Optional.of(base));

        assertThatThrownBy(() -> service.startImprovementFromBaseVersion(
                42L, "10", "foreign-v", "rationale", 7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("belongs to agent 99");
    }

    @Test
    @DisplayName("LLM failure: persists row with rulesJson=\"[]\" for audit then rethrows so outer tx records candidate_failed")
    void llmFailure_persistsAuditRowAndRethrows() {
        AgentEntity agent = new AgentEntity();
        agent.setId(11L);
        when(agentRepository.findById(11L)).thenReturn(Optional.of(agent));
        when(versionRepository.findByAgentIdAndStatus("11", BehaviorRuleVersionEntity.STATUS_ACTIVE))
                .thenReturn(Optional.empty());
        when(versionRepository.findMaxVersionNumber("11")).thenReturn(Optional.of(0));

        // Force the LLM to throw — mirrors a provider 5xx / timeout in
        // production. The service must still save() the row (audit-trail
        // preservation, same V3.1 lesson) before propagating the exception.
        provider.throwOnNextChat = true;

        assertThatThrownBy(() -> service.startImprovementFromAttribution(
                99L, "11", "Some rationale", 7L))
                .isInstanceOf(RuntimeException.class);

        // Exactly one save() with rulesJson="[]" — the audit row.
        ArgumentCaptor<BehaviorRuleVersionEntity> captor =
                ArgumentCaptor.forClass(BehaviorRuleVersionEntity.class);
        verify(versionRepository).save(captor.capture());

        BehaviorRuleVersionEntity saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(BehaviorRuleVersionEntity.STATUS_CANDIDATE);
        assertThat(saved.getRulesJson()).isEqualTo("[]");
        assertThat(saved.getSourceEventId()).isEqualTo(99L);
    }

    @Test
    @DisplayName("startImprovementFromAttribution is annotated @Transactional(REQUIRES_NEW) for V3.1-style tx isolation")
    void requiresNewTxIsolation_annotationIsPresent() throws NoSuchMethodException {
        // V3.1 commit 91c3108's lesson: this method must own its own tx
        // boundary so that when AttributionApprovalService.approve catches
        // a RuntimeException to write stage=candidate_failed, the failure
        // record commits independently of the outer tx's rollback. We
        // assert the annotation as a structural guard against future
        // accidental removal (a runtime check would require @SpringBootTest).
        Method method = BehaviorRuleImproverService.class.getMethod(
                "startImprovementFromAttribution", Long.class, String.class, String.class, Long.class);
        Transactional txAnn = method.getAnnotation(Transactional.class);
        assertThat(txAnn)
                .as("startImprovementFromAttribution must be @Transactional(REQUIRES_NEW); "
                        + "see V3.1 commit 91c3108 / persistence-shape-invariant javadoc")
                .isNotNull();
        assertThat(txAnn.propagation())
                .as("propagation must be REQUIRES_NEW (V3 W2 lesson — same rationale as PromptImproverService)")
                .isEqualTo(Propagation.REQUIRES_NEW);
    }

    private static final class CapturingProvider implements LlmProvider {
        private String lastUserMessage;
        private boolean throwOnNextChat = false;

        @Override
        public String getName() {
            return "test";
        }

        @Override
        public LlmResponse chat(LlmRequest request) {
            if (throwOnNextChat) {
                throwOnNextChat = false;
                throw new RuntimeException("simulated LLM 5xx");
            }
            lastUserMessage = request.getMessages().isEmpty()
                    ? null
                    : String.valueOf(request.getMessages().get(0).getContent());
            LlmResponse response = new LlmResponse();
            response.setContent("[{\"id\":\"r-improved\"}]");
            return response;
        }

        @Override
        public void chatStream(LlmRequest request, com.skillforge.core.llm.LlmStreamHandler handler) {
            throw new UnsupportedOperationException();
        }
    }
}
