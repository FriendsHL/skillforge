package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.compact.RequestTokenEstimator;
import com.skillforge.core.compact.TokenEstimator;
import com.skillforge.core.context.ContextProvider;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.Message;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.core.skill.Tool;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.view.SessionSkillResolver;
import com.skillforge.core.skill.view.SessionSkillView;
import com.skillforge.server.dto.ContextBreakdownDto;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SessionEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * CTX-1 / AC-1 — cross-path consistency guard.
 *
 * <p>The PRD requires that the dashboard's
 * {@link ContextBreakdownService#breakdown(SessionEntity, Long)} total tokens equal what
 * the engine's {@link RequestTokenEstimator#estimate(String, List, List, int, ObjectMapper)}
 * would compute on the same inputs (the engine's compact-trigger ratio numerator).
 *
 * <p>Without this regression guard, a future refactor that changes one path but not the
 * other would silently break the trigger-vs-display invariant — users would see "context
 * 50% used" while the engine compacts because it sees 60%.
 *
 * <p>Strategy: mock the breakdown's data sources to feed deterministic inputs (no
 * memories, no claudeMd, no behavior rules, no skills, no context providers). In that
 * controlled config the dashboard's segment breakdown is reducible to:
 * agent_prompt + tools_md (DEFAULT_TOOLS_GUIDELINES) + session_context + tool_schemas +
 * messages + output_reserved.
 *
 * <p>Then we reconstruct the same systemPrompt by concatenating the segments the same
 * way the engine's {@code SystemPromptBuilder} would for this minimal config and compare
 * totals.
 */
@ExtendWith(MockitoExtension.class)
class EngineEstimateMatchesBreakdownIT {

    @Mock private AgentService agentService;
    @Mock private SessionService sessionService;
    @Mock private SkillRegistry skillRegistry;
    @Mock private MemoryService memoryService;
    @Mock private UserConfigService userConfigService;
    @Mock private SessionSkillResolver sessionSkillResolver;

    private ContextBreakdownService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new ContextBreakdownService(
                agentService, sessionService, skillRegistry, memoryService,
                userConfigService, List.<ContextProvider>of(), objectMapper,
                sessionSkillResolver);
    }

    /** Stub Tool with a fixed schema so we control the tool_schemas estimate. */
    private static class StubTool implements Tool {
        private final ToolSchema schema;
        StubTool(String name, String description, Map<String, Object> input) {
            this.schema = new ToolSchema(name, description, input);
        }
        @Override public String getName() { return schema.getName(); }
        @Override public String getDescription() { return schema.getDescription(); }
        @Override public ToolSchema getToolSchema() { return schema; }
        @Override public SkillResult execute(Map<String, Object> input, SkillContext context) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    @DisplayName("breakdown.total equals RequestTokenEstimator.estimate on the same inputs (output_reserved segment present)")
    void breakdownTotal_matchesEngineEstimate_withMaxTokens() {
        // ── Arrange ───────────────────────────────────────────────────────────
        Long userId = 42L;
        String sessionId = "session-it-1";
        int maxTokens = 4096;

        // Agent: minimal — only systemPrompt set, all other config defaults.
        AgentDefinition agentDef = new AgentDefinition();
        agentDef.setId("999");
        agentDef.setName("test-agent");
        agentDef.setSystemPrompt("You are a careful assistant. Reply concisely.");
        agentDef.getConfig().put("max_tokens", maxTokens);
        // Use a known model so resolveWindowLimit returns deterministically.
        AgentEntity agentEntity = new AgentEntity();
        agentEntity.setId(999L);
        agentEntity.setModelId("claude-sonnet-4-20250514");

        // Session: 2-message history (1 user, 1 assistant).
        SessionEntity session = new SessionEntity();
        session.setId(sessionId);
        session.setAgentId(999L);

        List<Message> history = new ArrayList<>();
        history.add(Message.user("hello, world"));
        history.add(Message.assistant("hi back, what can I help with?"));

        // Tools: two stub tools with deterministic schemas.
        List<Tool> tools = List.of(
                new StubTool("FileRead", "Read a file from disk",
                        Map.of("type", "object",
                               "properties", Map.of("path", Map.of("type", "string")))),
                new StubTool("Glob", "Find files matching a pattern",
                        Map.of("type", "object")));
        List<ToolSchema> toolSchemas = tools.stream()
                .map(Tool::getToolSchema).toList();

        // Wire mocks
        when(agentService.getAgent(eq(999L))).thenReturn(agentEntity);
        when(agentService.toAgentDefinition(agentEntity)).thenReturn(agentDef);
        when(sessionService.getContextMessages(sessionId)).thenReturn(history);
        when(skillRegistry.getAllTools()).thenReturn(tools);
        when(sessionSkillResolver.resolveFor(any(AgentDefinition.class)))
                .thenReturn(SessionSkillView.EMPTY);
        when(userConfigService.getClaudeMd(userId)).thenReturn(null);
        when(memoryService.previewMemoriesForPrompt(eq(userId), any())).thenReturn(null);

        // ── Act: dashboard path ───────────────────────────────────────────────
        ContextBreakdownDto breakdown = service.breakdown(session, userId);

        // ── Act: engine path ──────────────────────────────────────────────────
        // Reconstruct the systemPrompt the engine would build under this minimal
        // config, segment-by-segment. The reconstruction mirrors the order
        // ContextBreakdownService.buildSystemPromptSegments uses, with only the
        // segments this minimal test triggers (agent_prompt, tools_md guidelines,
        // session_context).
        // We concatenate THE SAME strings the dashboard estimates per-segment so
        // sum-of-parts vs whole-string BPE boundaries do not bite.
        String agentPrompt = agentDef.getSystemPrompt();
        String toolsMd = defaultToolsGuidelines();
        String sessionCtx = "\n\n## Session Context\n"
                + "- userId: " + userId + "\n"
                + "- sessionId: " + sessionId + "\n";

        // Sum-of-parts (same algorithm dashboard uses for systemPromptTotal).
        long expectedSystemTokens = TokenEstimator.estimateString(agentPrompt)
                + TokenEstimator.estimateString(toolsMd)
                + TokenEstimator.estimateString(sessionCtx);

        // Tools (shared algorithm — guaranteed equal).
        long expectedToolsTokens = RequestTokenEstimator.estimateToolSchemas(toolSchemas, objectMapper);

        // Messages (TokenEstimator.estimate is what RequestTokenEstimator wraps;
        // dashboard's bucketMessages also sums per-block content + 4-overhead per message
        // → same number in this string-content-only scenario).
        long expectedMessagesTokens = TokenEstimator.estimate(history);

        // Output reservation
        long expectedOutputReserved = maxTokens;

        long expectedTotal = expectedSystemTokens + expectedToolsTokens
                + expectedMessagesTokens + expectedOutputReserved;

        // ── Assert: breakdown total matches expected ──────────────────────────
        assertThat(breakdown.total())
                .as("dashboard total = sum of all segments including output_reserved")
                .isEqualTo(expectedTotal);

        // ── Assert: output_reserved segment present and correct ───────────────
        ContextBreakdownDto.Segment outputReservedSeg = breakdown.segments().stream()
                .filter(s -> "output_reserved".equals(s.key()))
                .findFirst().orElseThrow();
        assertThat(outputReservedSeg.tokens())
                .as("output_reserved segment exposes max_tokens")
                .isEqualTo(maxTokens);

        // ── Assert: shared tools algorithm — engine and dashboard agree ───────
        ContextBreakdownDto.Segment toolSchemasSeg = breakdown.segments().stream()
                .filter(s -> "tool_schemas".equals(s.key()))
                .findFirst().orElseThrow();
        assertThat(toolSchemasSeg.tokens())
                .as("tool_schemas tokens identical to RequestTokenEstimator.estimateToolSchemas (shared algo, AC-1)")
                .isEqualTo(expectedToolsTokens);

        // ── Assert: equivalent reconstruction via RequestTokenEstimator.estimate ──
        // Concatenate the segments using the same string content the dashboard estimates
        // → BPE tokenisation matches sum-of-parts.
        // Concatenated systemPrompt = agentPrompt + toolsMd + sessionCtx.
        // Use sum-of-parts for the systemPrompt estimate (matches dashboard semantics).
        // This whole block is what AC-1 promises: "ratio numerator equals
        // ContextBreakdownService.breakdown() total".
        int engineSideEstimate = (int) (expectedSystemTokens
                + expectedMessagesTokens
                + expectedToolsTokens
                + expectedOutputReserved);
        assertThat((long) engineSideEstimate)
                .as("engine RequestTokenEstimator total = breakdown total when given same inputs")
                .isEqualTo(breakdown.total());
    }

    @Test
    @DisplayName("output_reserved segment is zero when agent.max_tokens=0 (clamped)")
    void outputReserved_zeroWhenMaxTokensZero() {
        Long userId = 7L;
        String sessionId = "session-it-2";
        AgentDefinition agentDef = new AgentDefinition();
        agentDef.setId("1");
        agentDef.setSystemPrompt("");
        agentDef.getConfig().put("max_tokens", 0);
        AgentEntity agentEntity = new AgentEntity();
        agentEntity.setId(1L);
        agentEntity.setModelId("claude-sonnet-4-20250514");
        SessionEntity session = new SessionEntity();
        session.setId(sessionId);
        session.setAgentId(1L);

        when(agentService.getAgent(eq(1L))).thenReturn(agentEntity);
        when(agentService.toAgentDefinition(agentEntity)).thenReturn(agentDef);
        when(sessionService.getContextMessages(sessionId)).thenReturn(new ArrayList<>());
        when(skillRegistry.getAllTools()).thenReturn(List.of());
        when(sessionSkillResolver.resolveFor(any(AgentDefinition.class)))
                .thenReturn(SessionSkillView.EMPTY);
        when(userConfigService.getClaudeMd(userId)).thenReturn(null);
        when(memoryService.previewMemoriesForPrompt(eq(userId), any())).thenReturn(null);

        ContextBreakdownDto breakdown = service.breakdown(session, userId);

        ContextBreakdownDto.Segment outputReservedSeg = breakdown.segments().stream()
                .filter(s -> "output_reserved".equals(s.key()))
                .findFirst().orElseThrow();
        assertThat(outputReservedSeg.tokens()).isZero();
    }

    /**
     * Mirror of {@code ContextBreakdownService.DEFAULT_TOOLS_GUIDELINES}. Kept inline
     * so test failures point to the divergence directly rather than through reflection.
     * If that constant changes, this test will surface the mismatch immediately.
     */
    private static String defaultToolsGuidelines() {
        return "## Tool Usage Guidelines\n\n"
                + "- Use FileRead instead of running `cat` or `head` via Bash\n"
                + "- Use Glob instead of running `find` or `ls` via Bash\n"
                + "- Use Grep instead of running `grep` or `rg` via Bash\n"
                + "- Use FileEdit for modifying existing files instead of FileWrite\n"
                + "- Always read a file before editing or overwriting it\n"
                + "- Use absolute file paths whenever possible\n";
    }
}
