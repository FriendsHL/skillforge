package com.skillforge.core.engine;

import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.LlmRequest;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.core.llm.LlmStreamHandler;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.model.ToolUseBlock;
import com.skillforge.core.skill.PublishedArtifact;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import static org.assertj.core.api.Assertions.assertThat;

class AgentLoopEnginePublishedArtifactTest {

    @Test
    void parallelTools_completeOutOfOrder_artifactsFollowCallOrderAndDeferTerminalBroadcast() {
        SkillRegistry registry = new SkillRegistry();
        registry.registerTool(new ArtifactTool("FirstArtifact", 80,
                new PublishedArtifact("att-1", "pdf_ref", "first.pdf", "application/pdf", 2, null, "First")));
        registry.registerTool(new ArtifactTool("SecondArtifact", 0,
                new PublishedArtifact("att-2", "csv_ref", "second.csv", "text/csv", null, null, "Second")));
        QueueProvider provider = new QueueProvider(List.of(
                toolResponse(List.of(
                        new ToolUseBlock("call-1", "FirstArtifact", Map.of()),
                        new ToolUseBlock("call-2", "SecondArtifact", Map.of()))),
                textResponse("Artifacts ready")));
        RecordingBroadcaster broadcaster = new RecordingBroadcaster();
        AgentLoopEngine engine = engine(registry, provider, broadcaster, 3);

        LoopResult result = engine.run(agent(3), "start", new ArrayList<>(), "sid", 1L);

        assertThat(result.getMessages()).hasSize(5);
        assertThat(toolUseId(result.getMessages().get(2))).isEqualTo("call-1");
        assertThat(toolUseId(result.getMessages().get(3))).isEqualTo("call-2");
        Message terminal = result.getMessages().get(4);
        assertThat(terminal.getTextContent()).isEqualTo(
                "Artifacts ready\n[PDF attachment: first.pdf]\n[CSV attachment: second.csv]");
        assertThat(refTypes(terminal)).containsExactly("pdf_ref", "csv_ref");
        assertThat(refBlocks(terminal)).extracting(ContentBlock::getCaption)
                .containsExactly("First", "Second");
        assertThat(result.getDeferredBroadcastMessages()).containsExactly(terminal);
        assertThat(result.getDeferredBroadcastMessages().get(0)).isSameAs(terminal);
        assertThat(broadcaster.appended).doesNotContain(terminal);
        assertThat(broadcaster.appended).hasSize(3);
    }

    @Test
    void maxLoopsAfterToolResult_appendsDeferredAttachmentOnlyTerminalMessage() {
        SkillRegistry registry = new SkillRegistry();
        registry.registerTool(new ArtifactTool("Publish", 0,
                new PublishedArtifact("att-1", "word_ref", "draft.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        null, null, "Draft")));
        QueueProvider provider = new QueueProvider(List.of(
                toolResponse(List.of(new ToolUseBlock("call-1", "Publish", Map.of())))));
        RecordingBroadcaster broadcaster = new RecordingBroadcaster();
        AgentLoopEngine engine = engine(registry, provider, broadcaster, 1);

        LoopResult result = engine.run(agent(1), "start", new ArrayList<>(), "sid", 1L);

        Message toolResult = result.getMessages().get(2);
        Message terminal = result.getMessages().get(3);
        assertThat(toolUseId(toolResult)).isEqualTo("call-1");
        assertThat(result.getStatus()).isEqualTo("max_loops_reached");
        assertThat(terminal.getRole()).isEqualTo(Message.Role.ASSISTANT);
        assertThat(refTypes(terminal)).containsExactly("word_ref");
        assertSingleDeferredArtifact(result, broadcaster, "word_ref");
    }

    @Test
    void waitingUserAfterPublishedTool_insertsAttachmentBeforeNewOpenToolUse() {
        SkillRegistry registry = new SkillRegistry();
        registry.registerTool(new ArtifactTool("Publish", 0,
                new PublishedArtifact("att-1", "image_ref", "chart.png",
                        "image/png", null, null, "Chart")));
        QueueProvider provider = new QueueProvider(List.of(
                toolResponse(List.of(new ToolUseBlock("publish-1", "Publish", Map.of()))),
                toolResponse(List.of(new ToolUseBlock("ask-1", "ask_user",
                        Map.of("question", "Continue?"))))));
        RecordingBroadcaster broadcaster = new RecordingBroadcaster();
        AgentLoopEngine engine = engine(registry, provider, broadcaster, 3);
        engine.setPendingAskRegistry(new PendingAskRegistry());

        LoopResult result = engine.run(agent(3), "start", new ArrayList<>(), "sid", 1L);

        assertThat(result.getStatus()).isEqualTo("waiting_user");
        assertThat(result.getMessages()).hasSize(5);
        Message attachment = result.getMessages().get(3);
        Message openAsk = result.getMessages().get(4);
        assertThat(refTypes(attachment)).containsExactly("image_ref");
        assertThat(openAsk.getToolUseBlocks()).extracting(ToolUseBlock::getId).containsExactly("ask-1");
        assertThat(result.getDeferredBroadcastMessages()).containsExactly(attachment);
        assertThat(broadcaster.appended).doesNotContain(attachment);
    }

    @Test
    void cancellationAfterPublishedTool_appendsArtifactExactlyOnce() {
        LoopContext context = new LoopContext();
        SkillRegistry registry = registryWithArtifactTool("Publish", artifact("pdf_ref", "cancelled.pdf"));
        QueueProvider provider = new QueueProvider(List.of(
                toolResponse(List.of(new ToolUseBlock("publish-1", "Publish", Map.of()))),
                textResponse("not persisted")), call -> {
                    if (call == 2) context.requestCancel();
                });
        RecordingBroadcaster broadcaster = new RecordingBroadcaster();
        AgentLoopEngine engine = engine(registry, provider, broadcaster, 4);

        LoopResult result = engine.run(agent(4), "start", new ArrayList<>(), "sid", 1L, context);

        assertThat(result.getStatus()).isEqualTo("cancelled");
        assertSingleDeferredArtifact(result, broadcaster, "pdf_ref");
    }

    @Test
    void tokenBudgetAfterPublishedTool_appendsArtifactExactlyOnce() {
        SkillRegistry registry = registryWithArtifactTool("Publish", artifact("csv_ref", "budget.csv"));
        QueueProvider provider = new QueueProvider(List.of(toolResponseWithUsage(
                List.of(new ToolUseBlock("publish-1", "Publish", Map.of())), 10)));
        RecordingBroadcaster broadcaster = new RecordingBroadcaster();
        AgentLoopEngine engine = engine(registry, provider, broadcaster, 4);
        Map<String, Object> config = new HashMap<>();
        config.put("max_loops", 4);
        config.put("enforce_max_input_tokens", true);
        config.put("max_input_tokens", 1);

        LoopResult result = engine.run(agent(config), "start", new ArrayList<>(), "sid", 1L);

        assertThat(result.getStatus()).isEqualTo("token_budget_exceeded");
        assertSingleDeferredArtifact(result, broadcaster, "csv_ref");
    }

    @Test
    void durationLimitAfterPublishedTool_appendsArtifactExactlyOnce() {
        Map<String, Object> config = new HashMap<>();
        config.put("max_loops", 4);
        config.put("max_duration_seconds", 3600);
        SkillRegistry registry = registryWithArtifactTool("Publish", artifact("excel_ref", "duration.xlsx"));
        QueueProvider provider = new QueueProvider(List.of(
                toolResponse(List.of(new ToolUseBlock("publish-1", "Publish", Map.of())))),
                call -> config.put("max_duration_seconds", -1));
        RecordingBroadcaster broadcaster = new RecordingBroadcaster();
        AgentLoopEngine engine = engine(registry, provider, broadcaster, 4);

        LoopResult result = engine.run(agent(config), "start", new ArrayList<>(), "sid", 1L);

        assertThat(result.getStatus()).isEqualTo("duration_exceeded");
        assertSingleDeferredArtifact(result, broadcaster, "excel_ref");
    }

    @Test
    void maxTokensExhaustedAfterPublishedTool_appendsArtifactExactlyOnce() {
        SkillRegistry registry = registryWithArtifactTool("Publish", artifact("image_ref", "tokens.png"));
        QueueProvider provider = new QueueProvider(List.of(
                toolResponse(List.of(new ToolUseBlock("publish-1", "Publish", Map.of()))),
                truncatedResponse("partial"),
                truncatedResponse("still partial")));
        RecordingBroadcaster broadcaster = new RecordingBroadcaster();
        AgentLoopEngine engine = engine(registry, provider, broadcaster, 4);

        LoopResult result = engine.run(agent(4), "start", new ArrayList<>(), "sid", 1L);

        assertThat(result.getStatus()).isEqualTo("max_tokens_exhausted");
        assertSingleDeferredArtifact(result, broadcaster, "image_ref");
    }

    @Test
    void abortToolUseAfterPublishedTool_appendsArtifactExactlyOnceAfterPairedResults() {
        LoopContext context = new LoopContext();
        context.setAllowedMcpServerNames(Collections.emptySet());
        SkillRegistry registry = registryWithArtifactTool("Publish", artifact("pdf_ref", "aborted.pdf"));
        QueueProvider provider = new QueueProvider(List.of(
                toolResponse(List.of(
                        new ToolUseBlock("publish-1", "Publish", Map.of()),
                        new ToolUseBlock("denied-1", "mcp_hidden_call", Map.of()))),
                toolResponse(List.of(new ToolUseBlock("denied-2", "mcp_hidden_call", Map.of())))));
        RecordingBroadcaster broadcaster = new RecordingBroadcaster();
        AgentLoopEngine engine = engine(registry, provider, broadcaster, 4);

        LoopResult result = engine.run(agent(4), "start", new ArrayList<>(), "sid", 1L, context);

        assertThat(context.isAbortToolUse()).isTrue();
        assertThat(toolUseId(result.getMessages().get(result.getMessages().size() - 2))).isEqualTo("denied-2");
        assertSingleDeferredArtifact(result, broadcaster, "pdf_ref");
    }

    @Test
    void queuedMessageAfterPublishedTool_defersArtifactUntilFollowingTerminalAssistant() {
        LoopContext context = new LoopContext();
        SkillRegistry registry = registryWithArtifactTool("Publish", artifact("word_ref", "queued.docx"));
        QueueProvider provider = new QueueProvider(List.of(
                toolResponse(List.of(new ToolUseBlock("publish-1", "Publish", Map.of()))),
                textResponse("intermediate"),
                textResponse("final")), call -> {
                    if (call == 2) context.enqueueUserMessage("queued follow-up");
                });
        RecordingBroadcaster broadcaster = new RecordingBroadcaster();
        AgentLoopEngine engine = engine(registry, provider, broadcaster, 5);

        LoopResult result = engine.run(agent(5), "start", new ArrayList<>(), "sid", 1L, context);

        assertThat(result.getStatus()).isEqualTo("completed");
        Message terminal = result.getMessages().get(result.getMessages().size() - 1);
        assertThat(terminal.getTextContent()).startsWith("final");
        assertThat(result.getMessages().get(3).getTextContent()).isEqualTo("intermediate");
        assertThat(result.getMessages().get(4).getTextContent()).isEqualTo("queued follow-up");
        assertThat(refBlocks(result.getMessages().get(3))).isEmpty();
        assertSingleDeferredArtifact(result, broadcaster, "word_ref");
    }

    @Test
    void truncatedToolOutput_dropsArtifactAndDoesNotCreateDeferredMessage() {
        SkillRegistry registry = new SkillRegistry();
        registry.registerTool(new ArtifactTool("Publish", 0, artifact("pdf_ref", "truncated.pdf"),
                "x".repeat(120_000)));
        QueueProvider provider = new QueueProvider(List.of(
                toolResponse(List.of(new ToolUseBlock("publish-1", "Publish", Map.of())))));
        RecordingBroadcaster broadcaster = new RecordingBroadcaster();
        AgentLoopEngine engine = engine(registry, provider, broadcaster, 1);

        LoopResult result = engine.run(agent(1), "start", new ArrayList<>(), "sid", 1L);

        assertThat(result.getStatus()).isEqualTo("max_loops_reached");
        assertThat(result.getDeferredBroadcastMessages()).isEmpty();
        assertThat(result.getMessages()).hasSize(3);
        assertThat(result.getMessages()).allMatch(message -> refBlocks(message).isEmpty());
    }

    private static AgentLoopEngine engine(SkillRegistry registry, LlmProvider provider,
                                          ChatEventBroadcaster broadcaster, int maxLoops) {
        LlmProviderFactory factory = new LlmProviderFactory();
        factory.registerProvider("fake", provider);
        AgentLoopEngine engine = new AgentLoopEngine(factory, "fake", registry,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        engine.setBroadcaster(broadcaster);
        return engine;
    }

    private static AgentDefinition agent(int maxLoops) {
        return agent(Map.of("max_loops", maxLoops));
    }

    private static AgentDefinition agent(Map<String, Object> config) {
        AgentDefinition agent = new AgentDefinition();
        agent.setName("artifact-test");
        agent.setModelId("fake:model");
        agent.setSystemPrompt("test");
        agent.setConfig(config);
        return agent;
    }

    private static SkillRegistry registryWithArtifactTool(String name, PublishedArtifact artifact) {
        SkillRegistry registry = new SkillRegistry();
        registry.registerTool(new ArtifactTool(name, 0, artifact));
        return registry;
    }

    private static PublishedArtifact artifact(String type, String filename) {
        return new PublishedArtifact("att-" + filename, type, filename, "application/octet-stream",
                null, null, filename);
    }

    private static LlmResponse toolResponse(List<ToolUseBlock> calls) {
        LlmResponse response = new LlmResponse();
        response.setStopReason("tool_use");
        response.setToolUseBlocks(calls);
        return response;
    }

    private static LlmResponse textResponse(String text) {
        LlmResponse response = new LlmResponse();
        response.setStopReason("end_turn");
        response.setContent(text);
        return response;
    }

    private static LlmResponse toolResponseWithUsage(List<ToolUseBlock> calls, int inputTokens) {
        LlmResponse response = toolResponse(calls);
        LlmResponse.Usage usage = new LlmResponse.Usage();
        usage.setInputTokens(inputTokens);
        response.setUsage(usage);
        return response;
    }

    private static LlmResponse truncatedResponse(String text) {
        LlmResponse response = new LlmResponse();
        response.setStopReason("max_tokens");
        response.setContent(text);
        return response;
    }

    private static String toolUseId(Message message) {
        return ((ContentBlock) ((List<?>) message.getContent()).get(0)).getToolUseId();
    }

    private static List<String> refTypes(Message message) {
        return refBlocks(message).stream()
                .map(ContentBlock::getType)
                .toList();
    }

    private static List<ContentBlock> refBlocks(Message message) {
        if (!(message.getContent() instanceof List<?> blocks)) return List.of();
        return blocks.stream()
                .filter(ContentBlock.class::isInstance)
                .map(ContentBlock.class::cast)
                .filter(block -> block.getType().endsWith("_ref"))
                .toList();
    }

    private static void assertSingleDeferredArtifact(LoopResult result,
                                                     RecordingBroadcaster broadcaster,
                                                     String expectedType) {
        assertThat(result.getDeferredBroadcastMessages()).hasSize(1);
        Message deferred = result.getDeferredBroadcastMessages().get(0);
        assertThat(refTypes(deferred)).containsExactly(expectedType);
        assertThat(result.getMessages()).filteredOn(message -> !refBlocks(message).isEmpty())
                .containsExactly(deferred);
        assertThat(broadcaster.appended).doesNotContain(deferred);
    }

    private static final class ArtifactTool implements Tool {
        private final String name;
        private final long delayMs;
        private final PublishedArtifact artifact;
        private final String output;

        private ArtifactTool(String name, long delayMs, PublishedArtifact artifact) {
            this(name, delayMs, artifact, "published " + artifact.getFilename());
        }

        private ArtifactTool(String name, long delayMs, PublishedArtifact artifact, String output) {
            this.name = name;
            this.delayMs = delayMs;
            this.artifact = artifact;
            this.output = output;
        }

        @Override public String getName() { return name; }
        @Override public String getDescription() { return "publishes an artifact"; }
        @Override public ToolSchema getToolSchema() {
            ToolSchema schema = new ToolSchema();
            schema.setName(name);
            schema.setDescription(getDescription());
            schema.setInputSchema(Map.of("type", "object", "properties", Map.of()));
            return schema;
        }
        @Override public SkillResult execute(Map<String, Object> input, SkillContext context) {
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return SkillResult.error("interrupted");
                }
            }
            return SkillResult.success(output, List.of(artifact));
        }
    }

    private static final class QueueProvider implements LlmProvider {
        private final Queue<LlmResponse> responses;
        private final IntConsumer beforeComplete;
        private final AtomicInteger calls = new AtomicInteger();
        private QueueProvider(List<LlmResponse> responses) { this(responses, call -> { }); }
        private QueueProvider(List<LlmResponse> responses, IntConsumer beforeComplete) {
            this.responses = new ArrayDeque<>(responses);
            this.beforeComplete = beforeComplete;
        }
        @Override public String getName() { return "fake"; }
        @Override public LlmResponse chat(LlmRequest request) { return responses.remove(); }
        @Override public void chatStream(LlmRequest request, LlmStreamHandler handler) {
            beforeComplete.accept(calls.incrementAndGet());
            handler.onComplete(responses.remove());
        }
    }

    private static final class RecordingBroadcaster implements ChatEventBroadcaster {
        private final List<Message> appended = new ArrayList<>();
        @Override public void sessionStatus(String sessionId, String status, String step, String error) { }
        @Override public void messageAppended(String sessionId, String traceId, Message message) { appended.add(message); }
        @Override public void askUser(String sessionId, AskUserEvent event) { }
    }
}
