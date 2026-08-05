package com.skillforge.server.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.compact.recovery.FileStateCache;
import com.skillforge.core.compact.recovery.RecoveryPayloadBuilder;
import com.skillforge.core.engine.AgentLoopEngine;
import com.skillforge.core.engine.LoopContext;
import com.skillforge.core.engine.LoopResult;
import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.LlmRequest;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.core.llm.LlmStreamHandler;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.core.model.ToolUseBlock;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.artifact.InteractiveArtifactValidator;
import com.skillforge.server.entity.ChatAttachmentEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionTaskDependencyEntity;
import com.skillforge.server.entity.SessionTaskEntity;
import com.skillforge.server.reminder.TaskReminderSource;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SessionTaskDependencyRepository;
import com.skillforge.server.repository.SessionTaskRepository;
import com.skillforge.server.service.ChatAttachmentService;
import com.skillforge.server.service.PersonalAppTemplateCatalog;
import com.skillforge.server.service.SessionTaskService;
import com.skillforge.server.tool.PublishInteractiveArtifactTool;
import com.skillforge.server.tool.task.TaskCreateTool;
import com.skillforge.server.tool.task.TaskUpdateTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Deterministic fake-model transcript executed through the production AgentLoop and tools. */
class GoalContinuityTaskTranscriptTest {
    @TempDir Path artifactWorkspace;
    private final SessionRepository sessions = mock(SessionRepository.class);
    private final SessionTaskRepository tasks = mock(SessionTaskRepository.class);
    private final SessionTaskDependencyRepository dependencies = mock(SessionTaskDependencyRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final List<SessionTaskEntity> taskRows = new ArrayList<>();
    private final List<SessionTaskDependencyEntity> dependencyRows = new ArrayList<>();
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private SessionTaskService service;
    private SkillRegistry registry;
    private List<Message> transcript;

    @BeforeEach
    void setUp() {
        SessionEntity session = new SessionEntity();
        session.setId("replay-session");
        session.setUserId(7L);
        when(sessions.findByIdForUpdate("replay-session")).thenReturn(Optional.of(session));
        when(sessions.findById("replay-session")).thenReturn(Optional.of(session));
        when(sessions.existsById("replay-session")).thenReturn(true);
        when(tasks.findBySessionIdOrderByCreatedAtAscIdAsc("replay-session"))
                .thenAnswer(ignored -> new ArrayList<>(taskRows));
        when(tasks.findByIdAndSessionId(anyString(), eq("replay-session"))).thenAnswer(invocation ->
                taskRows.stream().filter(task -> task.getId().equals(invocation.getArgument(0))).findFirst());
        when(tasks.save(any())).thenAnswer(invocation -> saveTask(invocation.getArgument(0)));
        when(tasks.saveAndFlush(any())).thenAnswer(invocation -> saveTask(invocation.getArgument(0)));
        when(dependencies.findBySessionId("replay-session"))
                .thenAnswer(ignored -> new ArrayList<>(dependencyRows));
        when(dependencies.save(any())).thenAnswer(invocation -> {
            SessionTaskDependencyEntity row = invocation.getArgument(0);
            dependencyRows.add(row);
            return row;
        });
        when(dependencies.saveAll(any())).thenAnswer(invocation -> {
            List<SessionTaskDependencyEntity> rows = invocation.getArgument(0);
            dependencyRows.addAll(rows);
            return rows;
        });
        service = new SessionTaskService(sessions, tasks, dependencies, objectMapper, events,
                Clock.fixed(Instant.parse("2026-08-05T00:00:00Z"), ZoneOffset.UTC));

        ChatAttachmentService attachmentService = mock(ChatAttachmentService.class);
        ChatAttachmentEntity attachment = new ChatAttachmentEntity();
        attachment.setId("artifact-1");
        attachment.setFilename("podcast-brief.html");
        attachment.setMimeType("text/html");
        when(attachmentService.importInteractiveArtifact(
                eq("replay-session"), eq(7L), anyString(), any(Path.class), any(),
                eq(artifactWorkspace), any())).thenReturn(attachment);

        registry = new SkillRegistry();
        registry.registerTool(new TaskCreateTool(service, objectMapper));
        registry.registerTool(new TaskUpdateTool(service, objectMapper));
        registry.registerTool(new PublishInteractiveArtifactTool(
                attachmentService,
                new PersonalAppTemplateCatalog(objectMapper),
                new InteractiveArtifactValidator(objectMapper),
                objectMapper));
        transcript = new ArrayList<>();
    }

    @Test
    void replayExecutesTenGoalContinuityScenariosThroughAgentLoop() throws Exception {
        Set<String> evidence = new LinkedHashSet<>();

        runTurn("Build the readable podcast brief", toolResponse("create-primary", "TaskCreate", Map.of(
                        "subject", "Publish readable podcast brief",
                        "description", "Summary, expandable content, and original-source action")),
                textResponse("Task created."));
        String primaryId = taskRows.get(0).getId();
        runTurn("Start it", toolResponse("start-primary", "TaskUpdate", Map.of(
                        "taskId", primaryId, "status", "in_progress")),
                textResponse("Started."));

        int taskCount = taskRows.size();
        LoopResult ordinary = runTurn("Why did the previous tool fail?",
                textResponse("The business goal is unchanged; I will explain the failure."));
        assertThat(ordinary.getToolCalls()).isEmpty();
        assertThat(taskRows).hasSize(taskCount);
        evidence.add("ordinary_followup_does_not_mutate_tasks");

        runTurn("Render concrete content in-app", toolResponse("update-acceptance", "TaskUpdate", Map.of(
                        "taskId", primaryId,
                        "description", "Render concrete content in-app and keep the original-source action")),
                textResponse("Updated the same task."));
        assertThat(taskRows).hasSize(1);
        assertThat(taskRows.get(0).getDescription()).contains("concrete content in-app");
        evidence.add("acceptance_correction_updates_existing_task");

        runTurn("Also run a review sample", toolResponse("create-independent", "TaskCreate", Map.of(
                        "subject", "Run a sample now", "description", "Independent review sample")),
                textResponse("Added as an independent pending task."));
        String priorityId = taskRows.get(1).getId();
        assertThat(taskRows.get(1).getStatus()).isEqualTo("pending");
        evidence.add("independent_scope_creates_pending_task");

        runTurn("Prioritize the sample", toolResponse("switch-priority", "TaskUpdate", Map.of(
                        "taskId", priorityId, "status", "in_progress")),
                textResponse("Priority switched."));
        assertThat(taskRows).extracting(SessionTaskEntity::getStatus)
                .containsExactly("pending", "in_progress");
        evidence.add("explicit_priority_switches_in_progress_task");

        runTurn("Mark the brief delivered", toolResponse("complete-primary", "TaskUpdate", Map.of(
                        "taskId", primaryId, "status", "completed")),
                textResponse("Marked delivered."));
        runTurn("No, it is not accepted; full content must render in-app",
                toolResponse("reopen-primary", "TaskUpdate", Map.of(
                        "taskId", primaryId,
                        "status", "pending",
                        "description", "Rejected delivery: full content must render in-app")),
                textResponse("Reopened the existing task."));
        assertThat(taskRows.get(0).getStatus()).isEqualTo("pending");
        evidence.add("user_rejection_reopens_completed_task");

        List<String> stateBeforeFailure = taskRows.stream()
                .map(task -> task.getId() + ":" + task.getStatus() + ":" + task.getDescription())
                .toList();
        Path outsideWorkspace = artifactWorkspace.resolveSibling("outside-podcast-brief.html");
        String latestUserGoal = "每项展示摘要、可展开全文，并保留可选原文入口";
        LoopResult failedPublish = runTurn(latestUserGoal,
                toolResponse("publish-invalid", PublishInteractiveArtifactTool.NAME, Map.of(
                        "file_path", outsideWorkspace.toString(),
                        "title", "Podcast brief",
                        "fallback", "Readable podcast viewpoints",
                        "state_schema", Map.of("type", "object"))),
                textResponse("发布工具失败，但业务目标仍是：" + latestUserGoal));
        assertThat(failedPublish.getMessages()).anyMatch(message ->
                message.getTextContent().contains("ARTIFACT_WORKSPACE_MISMATCH"));
        assertThat(taskRows.stream()
                .map(task -> task.getId() + ":" + task.getStatus() + ":" + task.getDescription())
                .toList()).isEqualTo(stateBeforeFailure);
        evidence.add("tool_failure_never_becomes_business_goal");
        assertThat(failedPublish.getFinalResponse())
                .contains(latestUserGoal)
                .doesNotContain("ARTIFACT_WORKSPACE_MISMATCH");
        evidence.add("final_response_follows_latest_user_goal");

        Path html = artifactWorkspace.resolve("podcast-brief.html");
        Files.writeString(html, "<!doctype html><title>Podcast brief</title>"
                + "<button data-sf-url='https://example.com/source'>Original</button>");
        LoopResult successfulPublish = runTurn("Fix the page and publish it",
                toolResponse("publish-valid", PublishInteractiveArtifactTool.NAME, Map.of(
                        "file_path", html.toString(),
                        "title", "Podcast brief",
                        "fallback", "Readable podcast viewpoints",
                        "state_schema", Map.of("type", "object"))),
                textResponse("The corrected Personal App is published."));
        assertThat(successfulPublish.getMessages()).anyMatch(message ->
                containsBlockType(message, "interactive_artifact_ref"));
        assertThat(taskRows.get(0).getSubject()).isEqualTo("Publish readable podcast brief");
        evidence.add("custom_artifact_can_recover_without_goal_drift");

        SessionTaskService restartedService = new SessionTaskService(
                sessions, tasks, dependencies, objectMapper, events,
                Clock.fixed(Instant.parse("2026-08-05T00:01:00Z"), ZoneOffset.UTC));
        TaskReminderSource taskRecovery = new TaskReminderSource(restartedService, true, 1, 20);
        RecoveryPayloadBuilder recoveryBuilder = new RecoveryPayloadBuilder(new FileStateCache());
        recoveryBuilder.setContributors(List.of(taskRecovery::renderForRecovery));
        Message recovered = recoveryBuilder.build("replay-session");
        assertThat(recovered).isNotNull();
        assertThat(recovered.getTextContent()).contains(primaryId, priorityId, "Run a sample now");

        Message historicalTodoCall = new Message();
        historicalTodoCall.setRole(Message.Role.ASSISTANT);
        historicalTodoCall.setContent(List.of(ContentBlock.toolUse(
                "legacy-todo", "TodoWrite", Map.of("todos", List.of(Map.of(
                        "subject", "Legacy task", "status", "completed"))))));
        Message historicalTodoResult = Message.toolResult("legacy-todo", "1 todo updated", false);
        transcript = new ArrayList<>(List.of(recovered, historicalTodoCall, historicalTodoResult));

        ScriptedProvider resumedProvider = new ScriptedProvider(List.of(
                textResponse("Recovered the in-progress sample after compact and restart.")));
        LoopResult resumed = runTurn(resumedProvider, "Continue after restart");
        assertThat(resumed.getFinalResponse()).contains("Recovered", "in-progress sample");
        assertThat(resumedProvider.requests()).singleElement().satisfies(request -> {
            assertThat(request.getMessages()).anyMatch(message ->
                    message.getTextContent().contains("Restored persistent state"));
            assertThat(request.getMessages()).anyMatch(message -> containsToolUse(message, "TodoWrite"));
        });
        evidence.add("compact_and_restart_reload_open_tasks");
        evidence.add("historical_todowrite_remains_renderable");

        assertThat(evidence).containsExactlyInAnyOrder(
                "ordinary_followup_does_not_mutate_tasks",
                "acceptance_correction_updates_existing_task",
                "independent_scope_creates_pending_task",
                "explicit_priority_switches_in_progress_task",
                "user_rejection_reopens_completed_task",
                "tool_failure_never_becomes_business_goal",
                "custom_artifact_can_recover_without_goal_drift",
                "compact_and_restart_reload_open_tasks",
                "historical_todowrite_remains_renderable",
                "final_response_follows_latest_user_goal");
    }

    private LoopResult runTurn(String userMessage, LlmResponse... responses) {
        return runTurn(new ScriptedProvider(List.of(responses)), userMessage);
    }

    private LoopResult runTurn(ScriptedProvider provider, String userMessage) {
        LlmProviderFactory factory = new LlmProviderFactory();
        factory.registerProvider("fake", provider);
        AgentLoopEngine engine = new AgentLoopEngine(factory, "fake", registry,
                List.of(), List.of(), List.of());
        LoopContext context = new LoopContext();
        context.setArtifactOutputDirectory(artifactWorkspace.toString());
        LoopResult result = engine.run(agent(), userMessage, transcript,
                "replay-session", 7L, context);
        assertThat(result.getStatus()).isEqualTo("completed");
        assertThat(provider.remainingResponses()).isZero();
        transcript = new ArrayList<>(result.getMessages());
        return result;
    }

    private static AgentDefinition agent() {
        AgentDefinition agent = new AgentDefinition();
        agent.setName("goal-continuity-replay");
        agent.setModelId("fake:model");
        agent.setSystemPrompt("Preserve the latest business goal even when a tool fails.");
        agent.setConfig(Map.of("max_loops", 5));
        return agent;
    }

    private static LlmResponse toolResponse(String id, String name, Map<String, Object> input) {
        LlmResponse response = new LlmResponse();
        response.setStopReason("tool_use");
        response.setToolUseBlocks(List.of(new ToolUseBlock(id, name, input)));
        return response;
    }

    private static LlmResponse textResponse(String text) {
        LlmResponse response = new LlmResponse();
        response.setStopReason("end_turn");
        response.setContent(text);
        return response;
    }

    private static boolean containsToolUse(Message message, String name) {
        if (!(message.getContent() instanceof List<?> blocks)) return false;
        return blocks.stream().filter(ContentBlock.class::isInstance).map(ContentBlock.class::cast)
                .anyMatch(block -> "tool_use".equals(block.getType()) && name.equals(block.getName()));
    }

    private static boolean containsBlockType(Message message, String type) {
        if (!(message.getContent() instanceof List<?> blocks)) return false;
        return blocks.stream().filter(ContentBlock.class::isInstance).map(ContentBlock.class::cast)
                .anyMatch(block -> type.equals(block.getType()));
    }

    private SessionTaskEntity saveTask(SessionTaskEntity task) {
        if (!taskRows.contains(task)) taskRows.add(task);
        return task;
    }

    private static final class ScriptedProvider implements LlmProvider {
        private final Queue<LlmResponse> responses;
        private final List<LlmRequest> requests = new ArrayList<>();

        private ScriptedProvider(List<LlmResponse> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override public String getName() { return "fake"; }
        @Override public LlmResponse chat(LlmRequest request) {
            requests.add(request);
            return responses.remove();
        }
        @Override public void chatStream(LlmRequest request, LlmStreamHandler handler) {
            requests.add(request);
            handler.onComplete(responses.remove());
        }
        private List<LlmRequest> requests() { return requests; }
        private int remainingResponses() { return responses.size(); }
    }
}
