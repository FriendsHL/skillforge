package com.skillforge.workflow.engine;

import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.tool.GetAgentConfigTool;
import com.skillforge.server.tool.GetTraceTool;
import com.skillforge.server.tool.optreport.GetToolCallSequenceTool;
import com.skillforge.server.tool.optreport.LoadErrorSpanBatchTool;
import com.skillforge.server.tool.optreport.LoadSessionBatchTool;
import com.skillforge.server.tool.optreport.RecordBatchAnnotationsTool;
import com.skillforge.server.tool.sessionannotation.AnnotateSessionTool;
import com.skillforge.server.tool.sessionannotation.SpanBehaviorStatsTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * AUTOEVOLVING V1 Sprint 3 (B1) — assembles the {@link SkillRegistry} used by
 * workflow {@code agent()} sub-agents.
 *
 * <p><b>Why this exists.</b> Sprint 1's {@link WorkflowSubAgentEngineFactory}
 * was wired with an empty {@code new SkillRegistry()}, so any workflow sub-agent
 * that emitted a {@code tool_use} hit "tool not found". {@code hello-world} never
 * called a tool, so the gap stayed latent. OPT-REPORT's loader / annotator /
 * aggregator all call production tools — without a populated registry the
 * {@code opt-report} workflow cannot run.
 *
 * <p><b>Least-privilege.</b> Mirrors {@link com.skillforge.server.eval.sandbox.SandboxSkillRegistryFactory}'s
 * "own registry, not the global bean" pattern, but registers the <em>real</em>
 * production tool beans (workflow OPT-REPORT is privileged system work — it reads
 * real sessions and writes real annotations). Only the OPT-REPORT / evolve read +
 * annotate tools are registered (incl. G5's read-only {@code LoadErrorSpanBatch} /
 * {@code GetToolCallSequence}); {@code Bash} / {@code FileWrite} / {@code SubAgent} /
 * {@code WriteOptReport} are deliberately absent. An agent still only sees the
 * subset declared in its own {@code tool_ids}, so the registry being a superset of
 * any single agent's needs is safe.
 *
 * <p>The registry is built once and shared: the registered tools are stateless
 * Spring singletons and {@code SkillRegistry} reads ({@code getTool}) are
 * thread-safe ({@code ConcurrentHashMap}).
 */
@Component
public class WorkflowSkillRegistryFactory {

    private static final Logger log = LoggerFactory.getLogger(WorkflowSkillRegistryFactory.class);

    private final SkillRegistry workflowRegistry;

    public WorkflowSkillRegistryFactory(LoadSessionBatchTool loadSessionBatchTool,
                                        GetAgentConfigTool getAgentConfigTool,
                                        GetTraceTool getTraceTool,
                                        SpanBehaviorStatsTool spanBehaviorStatsTool,
                                        AnnotateSessionTool annotateSessionTool,
                                        RecordBatchAnnotationsTool recordBatchAnnotationsTool,
                                        LoadErrorSpanBatchTool loadErrorSpanBatchTool,
                                        GetToolCallSequenceTool getToolCallSequenceTool) {
        SkillRegistry registry = new SkillRegistry();
        for (Tool tool : new Tool[]{
                loadSessionBatchTool,
                getAgentConfigTool,
                getTraceTool,
                spanBehaviorStatsTool,
                annotateSessionTool,
                recordBatchAnnotationsTool,
                loadErrorSpanBatchTool,
                getToolCallSequenceTool}) {
            registry.registerTool(tool);
        }
        this.workflowRegistry = registry;
        log.info("WorkflowSkillRegistryFactory: registered {} OPT-REPORT tools for workflow sub-agents: {}",
                registry.getAllTools().size(),
                registry.getAllTools().stream().map(Tool::getName).sorted().toList());
    }

    /**
     * The shared, read-only registry of OPT-REPORT production tools for workflow
     * sub-agents. Callers must not mutate it.
     */
    public SkillRegistry workflowRegistry() {
        return workflowRegistry;
    }
}
