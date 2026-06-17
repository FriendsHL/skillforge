package com.skillforge.workflow.engine;

import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.tool.GetAgentConfigTool;
import com.skillforge.server.tool.GetTraceTool;
import com.skillforge.server.tool.evolve.GenerateCandidateTool;
import com.skillforge.server.tool.optreport.GetToolCallSequenceTool;
import com.skillforge.server.tool.optreport.LoadErrorSpanBatchTool;
import com.skillforge.server.tool.optreport.LoadSessionBatchTool;
import com.skillforge.server.tool.optreport.RecordBatchAnnotationsTool;
import com.skillforge.server.tool.sessionannotation.AnnotateSessionTool;
import com.skillforge.server.tool.sessionannotation.SpanBehaviorStatsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AUTOEVOLVING V1 Sprint 3 (B1): {@link WorkflowSkillRegistryFactory} must register
 * the full OPT-REPORT tool subset (self-check risk #1 — "registry 漏 tool 致 agent
 * 跑不了") and nothing privileged beyond it (least privilege).
 */
class WorkflowSkillRegistryFactoryTest {

    private WorkflowSkillRegistryFactory factory;

    private static Tool toolNamed(Class<? extends Tool> type, String name) {
        Tool t = mock(type);
        when(t.getName()).thenReturn(name);
        return t;
    }

    @BeforeEach
    void setUp() {
        factory = new WorkflowSkillRegistryFactory(
                (LoadSessionBatchTool) toolNamed(LoadSessionBatchTool.class, "LoadSessionBatch"),
                (GetAgentConfigTool) toolNamed(GetAgentConfigTool.class, "GetAgentConfig"),
                (GetTraceTool) toolNamed(GetTraceTool.class, "GetTrace"),
                (SpanBehaviorStatsTool) toolNamed(SpanBehaviorStatsTool.class, "SpanBehaviorStats"),
                (AnnotateSessionTool) toolNamed(AnnotateSessionTool.class, "AnnotateSession"),
                (RecordBatchAnnotationsTool) toolNamed(RecordBatchAnnotationsTool.class, "RecordBatchAnnotations"),
                (LoadErrorSpanBatchTool) toolNamed(LoadErrorSpanBatchTool.class, "LoadErrorSpanBatch"),
                (GetToolCallSequenceTool) toolNamed(GetToolCallSequenceTool.class, "GetToolCallSequence"),
                (GenerateCandidateTool) toolNamed(GenerateCandidateTool.class, "GenerateCandidate"));
    }

    @Test
    @DisplayName("registers exactly the 9 OPT-REPORT / G5 / candidate-gen tools by name")
    void registersAllOptReportTools() {
        SkillRegistry registry = factory.workflowRegistry();

        List<String> names = registry.getAllTools().stream().map(Tool::getName).sorted().toList();
        assertThat(names).containsExactlyInAnyOrder(
                "LoadSessionBatch", "GetAgentConfig", "GetTrace",
                "SpanBehaviorStats", "AnnotateSession", "RecordBatchAnnotations",
                "LoadErrorSpanBatch", "GetToolCallSequence",
                // AUTOEVOLVE-CLOSE-LOOP P1: opened to the evolve-candidate-gen leaf.
                "GenerateCandidate");

        // Every agent that the opt-report workflow dispatches can resolve its tools.
        for (String n : names) {
            assertThat(registry.getTool(n)).isPresent();
        }
    }

    @Test
    @DisplayName("does NOT register privileged / out-of-scope tools (least privilege)")
    void excludesPrivilegedTools() {
        SkillRegistry registry = factory.workflowRegistry();
        for (String forbidden : List.of("WriteOptReport", "SubAgent", "Bash", "FileWrite")) {
            assertThat(registry.getTool(forbidden)).as(forbidden + " must not be registered").isEmpty();
        }
    }

    @Test
    @DisplayName("returns the same shared read-only registry instance")
    void registryIsShared() {
        assertThat(factory.workflowRegistry()).isSameAs(factory.workflowRegistry());
    }

    /**
     * AUTOEVOLVE-AGENT-FLYWHEEL Module B + C — recursion-isolation invariant. The
     * orchestration tools ({@code RunWorkflow} from Module A, the three A/B tools
     * {@code TriggerAbEval} / {@code GetAbResult} / {@code PromoteCandidate} from
     * Module B, and the Module C orchestrator tools {@code GenerateCandidate} /
     * {@code RecordIteration}) MUST NOT be reachable by a workflow sub-agent, or a
     * sub-agent could re-open a fan-out / recursion path. They live ONLY in the
     * main SkillRegistry (SkillForgeConfig). The orchestrator runs TOP-LEVEL, not
     * as a workflow sub-agent. This asserts the workflow sub-agent registry never
     * exposes any of them.
     */
    @Test
    @DisplayName("does NOT register orchestration / A-B / evolve tools (recursion isolation)")
    void excludesOrchestrationAndAbTools() {
        SkillRegistry registry = factory.workflowRegistry();
        // NOTE (AUTOEVOLVE-CLOSE-LOOP P1): GenerateCandidate is NO LONGER forbidden —
        // it is intentionally opened to the evolve-candidate-gen leaf (Q2: it only
        // calls the improver services, never A/B / RunWorkflow, so it opens no
        // fan-out path; least-privilege is preserved because only that agent's
        // tool_ids list it). The fan-out tools below stay excluded.
        for (String forbidden : List.of(
                "RunWorkflow", "TriggerAbEval", "GetAbResult", "PromoteCandidate",
                "RecordIteration")) {
            assertThat(registry.getTool(forbidden))
                    .as(forbidden + " must not be reachable by a workflow sub-agent")
                    .isEmpty();
        }
    }
}
