package com.skillforge.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.server.flywheel.run.FlywheelRunEntity;
import com.skillforge.server.flywheel.run.FlywheelRunRepository;
import com.skillforge.server.flywheel.run.FlywheelRunService;
import com.skillforge.server.flywheel.run.FlywheelRunStepEntity;
import com.skillforge.workflow.exception.WorkflowAlreadyRunningException;
import com.skillforge.workflow.exception.WorkflowNotFoundException;
import com.skillforge.workflow.exception.WorkflowNotPausedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task G — {@link WorkflowController} REST shape + status-code mapping (standalone
 * MockMvc; services mocked). Asserts the {@code {items}} envelope (footgun #6b)
 * and the 404/409 mappings.
 */
@EnableWebMvc
@DisplayName("WorkflowController")
class WorkflowControllerTest {

    private WorkflowDefinitionRegistry registry;
    private WorkflowRunnerService runnerService;
    private FlywheelRunService flywheelRunService;
    private FlywheelRunRepository runRepository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        registry = mock(WorkflowDefinitionRegistry.class);
        runnerService = mock(WorkflowRunnerService.class);
        flywheelRunService = mock(FlywheelRunService.class);
        runRepository = mock(FlywheelRunRepository.class);
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        WorkflowController controller = new WorkflowController(
                registry, runnerService, flywheelRunService, runRepository, objectMapper);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    @DisplayName("GET /api/workflows → {items:[...], total} envelope (not a bare array)")
    void listWorkflows() throws Exception {
        WorkflowDefinition def = new WorkflowDefinition(
                "demo", "a demo",
                List.of(new WorkflowDefinition.WorkflowPhase("Plan", "think")),
                "return 1;", "hash-1");
        when(registry.listAll()).thenReturn(List.of(def));

        mvc.perform(get("/api/workflows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].name").value("demo"))
                .andExpect(jsonPath("$.items[0].phases[0].title").value("Plan"));
    }

    @Test
    @DisplayName("POST /api/workflows/{name}/run → 202 {runId}")
    void runWorkflow() throws Exception {
        when(runnerService.startRun(eq("demo"), any(), any())).thenReturn("run-9");

        mvc.perform(post("/api/workflows/demo/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"args\":{\"k\":\"v\"}}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").value("run-9"))
                .andExpect(jsonPath("$.name").value("demo"))
                .andExpect(jsonPath("$.status").value("running"));
    }

    @Test
    @DisplayName("POST run → 404 when workflow unknown")
    void runUnknownWorkflow() throws Exception {
        when(runnerService.startRun(eq("ghost"), any(), any()))
                .thenThrow(new WorkflowNotFoundException("ghost"));

        mvc.perform(post("/api/workflows/ghost/run")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST run → 409 when a run of the same name is in flight")
    void runAlreadyRunning() throws Exception {
        when(runnerService.startRun(eq("demo"), any(), any()))
                .thenThrow(new WorkflowAlreadyRunningException("demo"));

        mvc.perform(post("/api/workflows/demo/run")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("GET /api/workflows/runs → {items, total, limit, offset} envelope")
    void listRuns() throws Exception {
        FlywheelRunEntity run = new FlywheelRunEntity();
        run.setId("run-1");
        run.setLoopKind("workflow");
        run.setStatus("completed");
        run.setInputJson("{\"workflow_name\":\"demo\"}");
        when(runRepository.findByLoopKindOrderByCreatedAtDescIdDesc(eq("workflow"), any()))
                .thenReturn(List.of(run));
        when(runRepository.countByLoopKind("workflow")).thenReturn(1L);

        mvc.perform(get("/api/workflows/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.limit").value(20))
                .andExpect(jsonPath("$.offset").value(0))
                .andExpect(jsonPath("$.items[0].runId").value("run-1"))
                .andExpect(jsonPath("$.items[0].name").value("demo"));
    }

    @Test
    @DisplayName("POST approve → 200 {status:running, decision}")
    void approve() throws Exception {
        mvc.perform(post("/api/workflows/runs/run-1/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"approved\",\"reason\":\"ok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("run-1"))
                .andExpect(jsonPath("$.status").value("running"))
                .andExpect(jsonPath("$.decision").value("approved"));
    }

    @Test
    @DisplayName("POST approve → 400 on an invalid decision value")
    void approveInvalidDecision() throws Exception {
        mvc.perform(post("/api/workflows/runs/run-1/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"maybe\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST approve → 409 when the run is not paused")
    void approveNotPaused() throws Exception {
        doThrow(new WorkflowNotPausedException("run-1", "running"))
                .when(runnerService).resume(eq("run-1"), anyBoolean(), nullable(String.class), nullable(String.class));

        mvc.perform(post("/api/workflows/runs/run-1/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"approved\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("POST approve → 409 (not 500) when resume hits an illegal internal state")
    void approveIllegalState() throws Exception {
        // r1 java-W1: resume()'s internal pre-condition failures (no pending gate
        // step, etc.) surface as IllegalStateException — must map to 409, never 500.
        doThrow(new IllegalStateException("paused run run-1 has no pending human_approve gate step"))
                .when(runnerService).resume(eq("run-1"), anyBoolean(), nullable(String.class), nullable(String.class));

        mvc.perform(post("/api/workflows/runs/run-1/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"approved\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("POST run → 409 (not 500) when startRun hits an illegal internal state")
    void runIllegalState() throws Exception {
        when(runnerService.startRun(eq("demo"), any(), any()))
                .thenThrow(new IllegalStateException("Workflow anchor agent not found: session-annotator"));

        mvc.perform(post("/api/workflows/demo/run")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("GET /api/workflows/runs/{id} → 404 for an unknown / non-workflow run")
    void getRunNotFound() throws Exception {
        when(flywheelRunService.findById("nope")).thenReturn(Optional.empty());

        mvc.perform(get("/api/workflows/runs/nope"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/workflows/runs/{id} → 200 run detail + steps envelope")
    void getRunDetail() throws Exception {
        FlywheelRunEntity run = new FlywheelRunEntity();
        run.setId("run-7");
        run.setLoopKind("workflow");
        run.setStatus("paused");
        run.setInputJson("{\"workflow_name\":\"demo\"}");
        run.setSummaryJson("{\"k\":\"v\"}");
        when(flywheelRunService.findById("run-7")).thenReturn(Optional.of(run));

        FlywheelRunStepEntity gate = new FlywheelRunStepEntity();
        gate.setId("step-1");
        gate.setRunId("run-7");
        gate.setStepIndex(1);
        gate.setStepKind(FlywheelRunStepEntity.STEP_KIND_HUMAN_APPROVE);
        gate.setStatus(FlywheelRunStepEntity.STATUS_PENDING);
        gate.setStepInputJson("{\"agentSlug\":\"reviewer\"}");

        // Sprint 3 B2: a subagent_dispatch step carrying a phase → DTO surfaces it.
        FlywheelRunStepEntity dispatch = new FlywheelRunStepEntity();
        dispatch.setId("step-2");
        dispatch.setRunId("run-7");
        dispatch.setStepIndex(2);
        dispatch.setStepKind(FlywheelRunStepEntity.STEP_KIND_SUBAGENT_DISPATCH);
        dispatch.setStatus(FlywheelRunStepEntity.STATUS_COMPLETED);
        dispatch.setStepInputJson("{\"phase\":\"Annotate\",\"agentSlug\":\"session-batch-annotator\",\"stepIndex\":2}");
        when(flywheelRunService.listStepsByRunId("run-7")).thenReturn(List.of(gate, dispatch));

        mvc.perform(get("/api/workflows/runs/run-7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("run-7"))
                .andExpect(jsonPath("$.name").value("demo"))
                .andExpect(jsonPath("$.status").value("paused"))
                .andExpect(jsonPath("$.summaryJson").value("{\"k\":\"v\"}"))
                .andExpect(jsonPath("$.steps").isArray())
                .andExpect(jsonPath("$.steps[0].stepIndex").value(1))
                .andExpect(jsonPath("$.steps[0].stepKind").value("human_approve"))
                .andExpect(jsonPath("$.steps[0].status").value("pending"))
                .andExpect(jsonPath("$.steps[0].agentSlug").value("reviewer"))
                // gate step has no phase in its step_input_json → null
                .andExpect(jsonPath("$.steps[0].phase").doesNotExist())
                // B2: dispatch step's phase is surfaced for the FE DAG grouping
                .andExpect(jsonPath("$.steps[1].phase").value("Annotate"))
                .andExpect(jsonPath("$.steps[1].agentSlug").value("session-batch-annotator"));
    }

    @Test
    @DisplayName("Sprint 4 W1: GET run detail surfaces the human_approve step's payload")
    void getRunDetail_exposesHumanApprovePayload() throws Exception {
        FlywheelRunEntity run = new FlywheelRunEntity();
        run.setId("run-8");
        run.setLoopKind("workflow");
        run.setStatus("paused");
        run.setInputJson("{\"workflow_name\":\"opt-report\"}");
        when(flywheelRunService.findById("run-8")).thenReturn(Optional.of(run));

        // step_input_json shape written by HostHumanApprove.buildStepInput:
        //   {stepKind, stepIndex, payload:<arbitrary humanApprove(payload) arg>}
        FlywheelRunStepEntity gate = new FlywheelRunStepEntity();
        gate.setId("step-1");
        gate.setRunId("run-8");
        gate.setStepIndex(3);
        gate.setStepKind(FlywheelRunStepEntity.STEP_KIND_HUMAN_APPROVE);
        gate.setStatus(FlywheelRunStepEntity.STATUS_PENDING);
        gate.setStepInputJson(
                "{\"stepKind\":\"human_approve\",\"stepIndex\":3,"
                        + "\"payload\":{\"topIssues\":[{\"id\":\"issue-1\",\"title\":\"ReadFile bug\"}],"
                        + "\"summary\":\"2 issues\"}}");
        when(flywheelRunService.listStepsByRunId("run-8")).thenReturn(List.of(gate));

        mvc.perform(get("/api/workflows/runs/run-8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].stepKind").value("human_approve"))
                .andExpect(jsonPath("$.steps[0].stepIndex").value(3))
                // W1: payload re-serialized as nested JSON (same shape as the WS frame)
                .andExpect(jsonPath("$.steps[0].payload.summary").value("2 issues"))
                .andExpect(jsonPath("$.steps[0].payload.topIssues[0].id").value("issue-1"))
                .andExpect(jsonPath("$.steps[0].payload.topIssues[0].title").value("ReadFile bug"));
    }

    @Test
    @DisplayName("Sprint 4 W1: payload is null for a step whose step_input_json has none")
    void getRunDetail_nullPayloadWhenAbsent() throws Exception {
        FlywheelRunEntity run = new FlywheelRunEntity();
        run.setId("run-9");
        run.setLoopKind("workflow");
        run.setStatus("running");
        run.setInputJson("{\"workflow_name\":\"demo\"}");
        when(flywheelRunService.findById("run-9")).thenReturn(Optional.of(run));

        FlywheelRunStepEntity dispatch = new FlywheelRunStepEntity();
        dispatch.setId("step-1");
        dispatch.setRunId("run-9");
        dispatch.setStepIndex(1);
        dispatch.setStepKind(FlywheelRunStepEntity.STEP_KIND_SUBAGENT_DISPATCH);
        dispatch.setStatus(FlywheelRunStepEntity.STATUS_COMPLETED);
        dispatch.setStepInputJson("{\"phase\":\"Load\",\"agentSlug\":\"loader\"}");
        when(flywheelRunService.listStepsByRunId("run-9")).thenReturn(List.of(dispatch));

        mvc.perform(get("/api/workflows/runs/run-9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].payload").doesNotExist());
    }
}
