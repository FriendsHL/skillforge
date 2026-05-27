package com.skillforge.server.improve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.LlmRequest;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.core.skill.SkillPackageLoader;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.entity.SkillDraftEntity;
import com.skillforge.server.memory.context.MemoryContextProvider;
import com.skillforge.server.memory.context.MemoryContextSnapshot;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SkillDraftRepository;
import com.skillforge.server.repository.SkillRepository;
import com.skillforge.server.skill.SkillCreatorService;
import com.skillforge.server.skill.SkillStorageService;
import com.skillforge.server.websocket.UserWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V3 ATTRIBUTION-AGENT Phase 1.3 — unit tests for the
 * {@link SkillDraftService#createDraftFromAttribution} entry point.
 *
 * <p>FLYWHEEL-LOOP-CLOSURE Phase 1.1 (2026-05-16) — updated to cover the new
 * sync LLM fill path (xiaomi-mimo / mimo-v2.5-pro / maxTokens=4000) +
 * audit-trail rethrow. The pre-Phase-1.1 deterministic stub behavior is gone;
 * happyPath now exercises the LLM mock + frontmatter parsing, plus a dedicated
 * audit-trail failure case.
 *
 * <p>Existing {@code extractFromRecentSessions} path covered by
 * {@link SkillDraftServiceApproveDraftTest} / {@link SkillDraftScheduledExtractorTest}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SkillDraftService.createDraftFromAttribution")
class SkillDraftServiceAttributionTest {

    @Mock private SessionRepository sessionRepository;
    @Mock private SkillDraftRepository skillDraftRepository;
    @Mock private SkillRepository skillRepository;
    @Mock private LlmProviderFactory llmProviderFactory;
    @Mock private LlmProvider llmProvider;
    @Mock private UserWebSocketHandler userWebSocketHandler;
    @Mock private SkillCreatorService skillCreatorService;
    @Mock private SkillPackageLoader skillPackageLoader;
    @Mock private SkillRegistry skillRegistry;
    @Mock private SkillStorageService skillStorageService;
    @Mock private MemoryContextProvider memoryContextProvider;

    private SkillDraftService service;

    @BeforeEach
    void setUp() {
        LlmProperties props = new LlmProperties();
        // Default-provider name used by the fallback path in SkillDraftService;
        // we always stub the preferred path in tests that need it, so this
        // value just has to be non-null for the constructor.
        props.setDefaultProvider("test");
        service = new SkillDraftService(
                sessionRepository,
                skillDraftRepository,
                skillRepository,
                llmProviderFactory,
                new ObjectMapper(),
                props,
                userWebSocketHandler,
                skillCreatorService,
                skillPackageLoader,
                skillRegistry,
                skillStorageService,
                // Phase 1.4e — 6 mock deps for startAbTestFromDraft path.
                org.mockito.Mockito.mock(com.skillforge.server.repository.EvalScenarioDraftRepository.class),
                org.mockito.Mockito.mock(com.skillforge.server.repository.OptimizationEventRepository.class),
                org.mockito.Mockito.mock(com.skillforge.server.repository.PatternSessionMemberRepository.class),
                org.mockito.Mockito.mock(com.skillforge.server.improve.SessionScenarioExtractorService.class),
                org.mockito.Mockito.mock(com.skillforge.server.improve.EphemeralScenarioCleanupService.class),
                org.mockito.Mockito.mock(com.skillforge.server.improve.SkillAbEvalService.class),
                memoryContextProvider);
    }

    /** Helper — stub the preferred xiaomi-mimo provider to return a canned LLM response. */
    private void stubLlmReturns(String content) {
        when(llmProviderFactory.getProvider("xiaomi-mimo")).thenReturn(llmProvider);
        LlmResponse resp = new LlmResponse();
        resp.setContent(content);
        when(llmProvider.chat(any(LlmRequest.class))).thenReturn(resp);
    }

    @Test
    @DisplayName("happy path: LLM frontmatter parsed → triggers / requiredTools / promptHint populated; "
            + "extractionRationale carries attribution pivot")
    void createDraftFromAttribution_happyPath_persistsLlmFilledDraft() {
        String llmOutput = """
                ---
                triggers: [pre-validate, dry-run, fail-fast]
                required_tools: [Bash, Read]
                ---
                Before invoking the destructive command, run a dry-run mode first \
                and surface its output to the operator. On any non-zero exit, ask \
                for confirmation before the real run. Always log both invocations.""";
        stubLlmReturns(llmOutput);
        ArgumentCaptor<SkillDraftEntity> captor = ArgumentCaptor.forClass(SkillDraftEntity.class);
        when(skillDraftRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        SkillDraftEntity draft = service.createDraftFromAttribution(
                /*eventId*/ 99L,
                /*patternId*/ 42L,
                /*description*/ "Add Bash pre-validation step before retries",
                /*expectedImpact*/ "Reduce failure rate by ~30% on this pattern",
                /*changeType*/ "rewrite_skill_md",
                /*ownerId*/ 7L,
                /*suggestedSkillName*/ "AttrSkill42_99");

        assertThat(draft).isNotNull();
        SkillDraftEntity saved = captor.getValue();

        // Identity + audit metadata still set deterministically.
        assertThat(saved.getOwnerId()).isEqualTo(7L);
        assertThat(saved.getName()).isEqualTo("AttrSkill42_99");
        assertThat(saved.getStatus()).isEqualTo("draft");
        assertThat(saved.getSourceSessionId()).isNull();
        assertThat(saved.getDescription()).isEqualTo("Add Bash pre-validation step before retries");
        assertThat(saved.getExtractionRationale())
                .contains("[attribution:eventId=99")
                .contains("patternId=42")
                .contains("changeType=rewrite_skill_md")
                .contains("Add Bash pre-validation step");

        // Phase 1.1 — these used to be empty / structured stubs; now they're
        // populated from the LLM frontmatter + body.
        assertThat(saved.getTriggers()).isEqualTo("pre-validate,dry-run,fail-fast");
        assertThat(saved.getRequiredTools()).isEqualTo("Bash,Read");
        assertThat(saved.getPromptHint())
                .startsWith("Before invoking the destructive command")
                .contains("dry-run mode first")
                .contains("Always log both invocations.")
                // Frontmatter delimiters stripped out of body.
                .doesNotContain("---")
                .doesNotContain("triggers:");
        // Successful path saves exactly once (rethrow path would save twice).
        verify(skillDraftRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("missing eventId / patternId / ownerId / blank description / blank name → "
            + "IllegalArgumentException before LLM call")
    void createDraftFromAttribution_invalidArgs_throwsBeforeLlmCall() {
        assertThatThrownBy(() -> service.createDraftFromAttribution(
                null, 42L, "desc", "impact", "rewrite", 7L, "name"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventId");
        assertThatThrownBy(() -> service.createDraftFromAttribution(
                99L, null, "desc", "impact", "rewrite", 7L, "name"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("patternId");
        assertThatThrownBy(() -> service.createDraftFromAttribution(
                99L, 42L, "desc", "impact", "rewrite", null, "name"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ownerId");
        assertThatThrownBy(() -> service.createDraftFromAttribution(
                99L, 42L, "  ", "impact", "rewrite", 7L, "name"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attributedDescription");
        assertThatThrownBy(() -> service.createDraftFromAttribution(
                99L, 42L, "desc", "impact", "rewrite", 7L, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("suggestedSkillName");
        // Validation runs before LLM dispatch + before any DB write.
        verify(skillDraftRepository, never()).save(any());
        verify(llmProviderFactory, never()).getProvider(anyString());
    }

    @Test
    @DisplayName("null expectedImpact: LLM prompt still built, no NPE, draft populated from LLM")
    void createDraftFromAttribution_nullExpectedImpact_stillFillsDraft() {
        stubLlmReturns("""
                ---
                triggers: [refactor-loop]
                required_tools: []
                ---
                Loop body for refactor pattern.""");
        ArgumentCaptor<SkillDraftEntity> captor = ArgumentCaptor.forClass(SkillDraftEntity.class);
        when(skillDraftRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.createDraftFromAttribution(
                99L, 42L,
                "desc only",
                null,  // null expectedImpact — must not NPE in prompt build
                "rewrite",
                7L,
                "AttrName");

        SkillDraftEntity saved = captor.getValue();
        assertThat(saved.getTriggers()).isEqualTo("refactor-loop");
        // Empty YAML list → empty CSV (not "null", not the string "[]").
        assertThat(saved.getRequiredTools()).isEqualTo("");
        assertThat(saved.getPromptHint()).isEqualTo("Loop body for refactor pattern.");
        // extractionRationale always set, regardless of expectedImpact / LLM result.
        assertThat(saved.getExtractionRationale())
                .contains("[attribution:eventId=99")
                .contains("desc only");
    }

    @Test
    @DisplayName("memory context provider output is appended to attribution skill-generation prompt")
    void createDraftFromAttribution_memoryContextAvailable_includesContextInPrompt() {
        when(memoryContextProvider.load(7L,
                "Add Bash pre-validation step before retries\nReduce failure rate"))
                .thenReturn(new MemoryContextSnapshot(
                        7L,
                        "task",
                        "User prefers dry-run before destructive commands.",
                        Set.of(101L),
                        "hash-101"));
        stubLlmReturns("""
                ---
                triggers: [pre-validate]
                required_tools: [Bash]
                ---
                Run dry-run validation first.""");
        ArgumentCaptor<LlmRequest> requestCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        when(skillDraftRepository.save(any(SkillDraftEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.createDraftFromAttribution(
                99L, 42L,
                "Add Bash pre-validation step before retries",
                "Reduce failure rate",
                "rewrite_skill_md",
                7L,
                "AttrSkill42_99");

        verify(llmProvider).chat(requestCaptor.capture());
        String userPrompt = requestCaptor.getValue().getMessages().get(0).getTextContent();
        assertThat(userPrompt)
                .contains("Relevant long-term memory context:")
                .contains("User prefers dry-run before destructive commands.");
    }

    @Test
    @DisplayName("LLM throws → draft saved with empty content + log.error + rethrow (audit-trail rethrow)")
    void createDraftFromAttribution_llmFails_savesAuditTrailDraftAndRethrows() {
        when(llmProviderFactory.getProvider("xiaomi-mimo")).thenReturn(llmProvider);
        when(llmProvider.chat(any(LlmRequest.class)))
                .thenThrow(new RuntimeException("LLM upstream 502"));
        ArgumentCaptor<SkillDraftEntity> captor = ArgumentCaptor.forClass(SkillDraftEntity.class);
        when(skillDraftRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> service.createDraftFromAttribution(
                99L, 42L, "Add Bash pre-validation",
                "Reduce failure rate", "rewrite_skill_md", 7L, "AttrSkill42_99"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("LLM upstream 502");

        // Audit-trail row written exactly once on the failure path
        // (the success path would save a second time after the catch — verifying
        // single save proves we rethrew instead of swallowing).
        verify(skillDraftRepository, times(1)).save(any());
        SkillDraftEntity savedOnFailure = captor.getValue();
        // Identity + attribution metadata preserved so the dashboard can pivot
        // back to the originating event even though the draft is "empty".
        assertThat(savedOnFailure.getOwnerId()).isEqualTo(7L);
        assertThat(savedOnFailure.getName()).isEqualTo("AttrSkill42_99");
        assertThat(savedOnFailure.getDescription()).isEqualTo("Add Bash pre-validation");
        assertThat(savedOnFailure.getExtractionRationale())
                .contains("[attribution:eventId=99")
                .contains("patternId=42")
                .contains("Add Bash pre-validation");
        // Content fields blanked to signal "needs operator intervention".
        assertThat(savedOnFailure.getTriggers()).isEqualTo("");
        assertThat(savedOnFailure.getRequiredTools()).isEqualTo("");
        assertThat(savedOnFailure.getPromptHint()).isEqualTo("");
    }

    /**
     * Direct coverage for the frontmatter parser — invoked through the service
     * in the cases above, but a focused parser test catches edge cases that
     * would otherwise need 5+ LLM-mock setups (cleaner via static helper).
     */
    @Nested
    @DisplayName("parseSkillMdOutput (frontmatter parser)")
    class ParseSkillMdOutput {

        @Test
        @DisplayName("YAML list form → CSV; body trimmed")
        void yamlList_parsesToCsv() {
            SkillDraftService.SkillContentResult r = SkillDraftService.parseSkillMdOutput("""
                    ---
                    triggers: [a, b, c]
                    required_tools: [Bash]
                    ---
                    Body text here.
                    """);
            assertThat(r.triggers()).isEqualTo("a,b,c");
            assertThat(r.requiredTools()).isEqualTo("Bash");
            assertThat(r.skillMdBody()).isEqualTo("Body text here.");
        }

        @Test
        @DisplayName("missing leading delimiter → RuntimeException (triggers audit-trail rethrow path)")
        void missingLeadingDelimiter_throws() {
            assertThatThrownBy(() -> SkillDraftService.parseSkillMdOutput("no frontmatter here"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("missing leading '---'");
        }

        @Test
        @DisplayName("missing closing delimiter → RuntimeException")
        void missingClosingDelimiter_throws() {
            assertThatThrownBy(() -> SkillDraftService.parseSkillMdOutput("---\ntriggers: [a]\nbody"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("missing closing '---'");
        }

        @Test
        @DisplayName("outer markdown code-fence wrap is tolerated and stripped")
        void markdownCodeFenceWrap_tolerated() {
            String wrapped = "```markdown\n---\ntriggers: [x]\nrequired_tools: []\n---\nBody.\n```";
            SkillDraftService.SkillContentResult r = SkillDraftService.parseSkillMdOutput(wrapped);
            assertThat(r.triggers()).isEqualTo("x");
            assertThat(r.requiredTools()).isEqualTo("");
            assertThat(r.skillMdBody()).isEqualTo("Body.");
        }
    }
}
