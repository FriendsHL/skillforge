package com.skillforge.server.history.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.compact.CompactSummaryEnvelope;
import com.skillforge.core.compact.CompactSummaryMessage;
import com.skillforge.core.llm.*;
import com.skillforge.core.model.*;
import com.skillforge.core.skill.*;
import com.skillforge.server.config.SessionHistoryProperties;
import com.skillforge.server.history.*;
import com.skillforge.server.tool.SessionHistoryReadTool;
import com.skillforge.server.tool.SessionHistorySearchTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Opt-in real-model smoke, using synthetic row fixtures and the production History tools/query/wire.
 * It verifies model behavior, not database durability, full AgentLoopEngine orchestration, or A-D quality.
 * Run with HISTORY_LIVE_SMOKE=true and the selected provider credential. Never enabled by the ordinary regression gate.
 */
@EnabledIfEnvironmentVariable(named = "HISTORY_LIVE_SMOKE", matches = "true")
class SessionHistoryLiveProviderIT {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void realModel_recoversMissingFactViaSearchThenRead_andSkipsHistoryWhenTailSuffices() throws Exception {
        String providerName = System.getenv().getOrDefault("HISTORY_LIVE_PROVIDER", "ark");
        String keyName;
        String baseUrl;
        String defaultModel;
        String chatPath = "/chat/completions";
        switch (providerName) {
            case "ark" -> {
                keyName = "ARK_API_KEY";
                baseUrl = "https://ark.cn-beijing.volces.com/api/plan/v3";
                defaultModel = "doubao-seed-2.0-lite";
            }
            case "bailian-token-plan" -> {
                keyName = "BAILIAN_TOKEN_PLAN_API_KEY";
                baseUrl = "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1";
                defaultModel = "qwen3.8-max";
            }
            case "deepseek" -> {
                keyName = "DEEPSEEK_API_KEY";
                baseUrl = "https://api.deepseek.com";
                defaultModel = "deepseek-v4-pro";
                chatPath = "/v1/chat/completions";
            }
            default -> throw new IllegalArgumentException("Unsupported smoke provider");
        }
        String apiKey = System.getenv(keyName);
        assertThat(apiKey).as(keyName + " must be configured for this opt-in probe").isNotBlank();
        String model = System.getenv().getOrDefault("HISTORY_LIVE_MODEL", defaultModel);
        LlmProvider provider = new OpenAiProvider(apiKey, baseUrl, model, providerName, keyName,
                60, 0, chatPath);
        SkillContext context = new SkillContext("/synthetic-workspace", "history-live-fixture", 41L);
        CurrentSessionHistoryScope scope = CurrentSessionHistoryScope.from(context, 1, 3, 2);
        HistoryQueryStore store = mock(HistoryQueryStore.class, CALLS_REAL_METHODS);
        when(store.requireCurrentScope(any())).thenReturn(new HistoryQueryStore.ScopeState(1, false));
        when(store.captureCutoff(any())).thenReturn(new HistoryCursorCodec.SnapshotCutoff(3, 2, 0, 0, 3, 2));
        String expected = "YS-" + UUID.randomUUID().toString().substring(0, 8);
        List<HistoryQueryStore.MessageRow> rows = List.of(
                new HistoryQueryStore.MessageRow(1, 0, "user", "NORMAL",
                        mapper.writeValueAsString("紫杉项目的最终发布编号是 " + expected + "，请记住此编号。"),
                        "normal", null, 1L, Instant.EPOCH),
                new HistoryQueryStore.MessageRow(2, 1, "assistant", "NORMAL",
                        mapper.writeValueAsString("已记录紫杉项目发布编号。"),
                        "normal", null, 1L, Instant.EPOCH.plusSeconds(1)));
        when(store.loadMessages(any(), any(), anyInt())).thenReturn(rows);
        when(store.loadSummaries(any(), any(), anyInt())).thenReturn(List.of());
        when(store.loadArchives(any(), any(), anyInt())).thenReturn(List.of());
        when(store.loadMessages(any(), any(), any(HistoryQueryStore.Selection.class), anyInt()))
                .thenAnswer(invocation -> {
                    HistoryQueryStore.Selection selection = invocation.getArgument(2);
                    return rows.stream()
                            .filter(row -> selection.seqFrom() == null || row.seqNo() >= selection.seqFrom())
                            .filter(row -> selection.seqTo() == null || row.seqNo() <= selection.seqTo())
                            .filter(row -> selection.messageIds() == null || selection.messageIds().contains(row.id()))
                            .toList();
                });
        when(store.loadSummaries(any(), any(), any(HistoryQueryStore.Selection.class), anyInt()))
                .thenReturn(List.of());
        when(store.loadArchivesForMessages(any(), any(), anyList(), anyInt())).thenReturn(List.of());
        when(store.loadPairingContext(any(), any(), anyList(), anyInt())).thenReturn(List.of());
        HistoryRefCodec refs = new HistoryRefCodec();
        SessionHistoryQueryService query = new SessionHistoryQueryService(store,
                new HistoryEvidenceMaterializer(mapper, new HistoryAuthorizedProjection(mapper), refs,
                        new FailClosedHistoryCanonicalArchiveResolver()),
                new HistoryCanonicalSelector(mapper), new HistoryCursorCodec(mapper), refs);
        SessionHistoryProperties properties = new SessionHistoryProperties();
        properties.setEnabled(true);
        SessionHistoryScopeFactory scopes = mock(SessionHistoryScopeFactory.class);
        when(scopes.readiness(any())).thenReturn(
                new SessionHistoryAvailabilityPolicy.StoreReadiness(true, true, true, true, false));
        when(scopes.resolve(any(), anyString())).thenReturn(scope);
        var availability = new SessionHistoryAvailabilityPolicy(properties);
        var validator = new HistoryToolInputValidator();
        var formatter = new SessionHistoryWireFormatter(mapper, properties);
        Map<String, Tool> tools = Map.of(
                "SessionHistorySearch", new SessionHistorySearchTool(validator, query, formatter, availability, scopes),
                "SessionHistoryRead", new SessionHistoryReadTool(validator, query, formatter, availability, scopes));
        String system;
        try (var resource = getClass().getResourceAsStream("/prompts/global-system-prompt.md")) {
            assertThat(resource).isNotNull();
            system = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<Message> missing = new ArrayList<>(List.of(
                new CompactSummaryMessage(new CompactSummaryEnvelope.TrustedSummary(1, 0, 1,
                        "用户曾指定紫杉项目的最终发布编号。当前摘要省略了编号。")),
                Message.user("请告诉我之前确定的紫杉项目最终发布编号，保持原样，不要猜测。")));
        Outcome recovered = run(provider, model, system, missing, tools, context);
        assertThat(recovered.calls()).containsSubsequence("SessionHistorySearch", "SessionHistoryRead");
        assertThat(recovered.answer()).contains(expected);

        Outcome sufficient = run(provider, model, system, new ArrayList<>(List.of(
                Message.user("紫杉项目最终发布编号是 TAIL-9042。请原样回复这个编号。"))), tools, context);
        assertThat(sufficient.calls()).isEmpty();
        assertThat(sufficient.answer()).contains("TAIL-9042");
        System.out.println("HISTORY_LIVE_SMOKE provider=" + providerName + " model=" + model
                + " baseline=" + Boolean.parseBoolean(System.getenv("HISTORY_LIVE_SEARCH_BASELINE"))
                + " missing.calls=" + recovered.calls() + " exact=true sufficient.calls=" + sufficient.calls()
                + " providerCalls=" + (recovered.providerCalls() + sufficient.providerCalls()));
    }

    private Outcome run(LlmProvider provider, String model, String system, List<Message> messages,
                        Map<String, Tool> tools, SkillContext context) {
        List<String> calls = new ArrayList<>();
        for (int step = 0; step < 6; step++) {
            LlmRequest request = new LlmRequest();
            request.setModel(model);
            request.setSystemPrompt(system);
            request.setMessages(messages);
            request.setTools(List.of(searchSchemaForProbe(), SessionHistoryToolSchemas.read()));
            request.setTemperature(0);
            request.setMaxTokens(2048);
            long providerStarted = System.nanoTime();
            LlmResponse response = provider.chat(request);
            LlmResponse.Usage usage = response.getUsage();
            System.out.println("HISTORY_SMOKE_PROVIDER step=" + step + " elapsedMs="
                    + (System.nanoTime() - providerStarted) / 1_000_000
                    + " inputTokens=" + (usage == null ? -1 : usage.getInputTokens())
                    + " cacheReadTokens=" + (usage == null ? -1 : usage.getCacheReadInputTokens())
                    + " outputTokens=" + (usage == null ? -1 : usage.getOutputTokens()));
            if (!response.isToolUse()) return new Outcome(response.getContent(), List.copyOf(calls), step + 1);
            Message assistant = Message.assistant("");
            List<ContentBlock> blocks = new ArrayList<>();
            if (response.getContent() != null && !response.getContent().isBlank()) {
                blocks.add(ContentBlock.text(response.getContent()));
            }
            for (ToolUseBlock call : response.getValidToolUseBlocks()) {
                blocks.add(ContentBlock.toolUse(call.getId(), call.getName(), call.getInput()));
            }
            assistant.setContent(blocks);
            assistant.setReasoningContent(response.getReasoningContent());
            messages.add(assistant);
            for (ToolUseBlock call : response.getValidToolUseBlocks()) {
                Tool tool = tools.get(call.getName());
                assertThat(tool).as("only current-session History tools are exposed").isNotNull();
                calls.add(call.getName());
                context.setToolUseId(call.getId());
                long toolStarted = System.nanoTime();
                SkillResult result = tool.execute(call.getInput(), context);
                // Only opt-in synthetic fixtures are logged; production History never logs user queries.
                String responseJson = result.getOutput() == null ? "" : result.getOutput()
                        .replace("&quot;", "\"");
                int hitCount = responseJson.split(java.util.regex.Pattern.quote("\"ref\""), -1).length - 1;
                System.out.println("HISTORY_SMOKE_TOOL step=" + step + " name=" + call.getName()
                        + " input=" + call.getInput() + " hits=" + hitCount + " elapsedMs="
                        + (System.nanoTime() - toolStarted) / 1_000_000);
                assertThat(result.isSuccess()).as("History call must succeed: " + call.getName()).isTrue();
                assertThat(result.getOutput()).contains("<context-data source=\"history\"")
                        .hasSizeLessThanOrEqualTo(32000);
                messages.add(Message.toolResult(call.getId(), result.getOutput(), false));
            }
        }
        throw new AssertionError("Live smoke exceeded six provider turns");
    }

    /** Test-only description baseline; matching, fixtures and schema ordering stay identical. */
    @SuppressWarnings("unchecked")
    private ToolSchema searchSchemaForProbe() {
        ToolSchema schema = SessionHistoryToolSchemas.search();
        if (Boolean.parseBoolean(System.getenv("HISTORY_LIVE_SEARCH_BASELINE"))) {
            schema.setDescription("Locate missing facts in the persisted history of the current Session. "
                    + "Returns compact locators; use SessionHistoryRead for evidence.");
            Map<String, Object> properties = (Map<String, Object>) schema.getInputSchema().get("properties");
            Map<String, Object> query = (Map<String, Object>) properties.get("query");
            query.put("description", "Keyword or exact fragment to locate.");
        }
        return schema;
    }

    private record Outcome(String answer, List<String> calls, int providerCalls) { }
}
