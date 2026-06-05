package com.skillforge.workflow.engine;

import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.tool.evolve.GetAbResultTool;
import com.skillforge.server.tool.evolve.GetCandidateDiffTool;
import com.skillforge.server.tool.evolve.GetOptReportTool;
import com.skillforge.server.tool.evolve.ReconcilePredictionTool;
import com.skillforge.server.tool.evolve.RecordIterationTool;
import com.skillforge.server.tool.evolve.RunOptReportSubflowTool;
import com.skillforge.server.tool.evolve.TriggerAbEvalTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * AUTOEVOLVE-CLOSE-LOOP P1 — the WHITELIST registry the deterministic {@code tool()}
 * host binding ({@code HostToolCall} → {@code DefaultWorkflowToolInvoker}) resolves
 * tools from. This is intentionally SEPARATE from:
 *
 * <ul>
 *   <li>the main {@code SkillRegistry} (where the same evolve tools are registered
 *       for the legacy top-level orchestrator agent), and</li>
 *   <li>{@code WorkflowSkillRegistryFactory} (the LLM sub-agent registry) — the
 *       evolve mechanical tools must NEVER be reachable by an LLM sub-agent
 *       (recursion / fan-out guard).</li>
 * </ul>
 *
 * <p>The {@code tool()} binding is fed by THIS registry only, so the workflow JS
 * can call exactly the mechanical evolve nodes — and nothing else (e.g. no
 * {@code Bash} / {@code FileWrite}). The JS source is trusted classpath, not an
 * untrusted LLM surface, but the whitelist still bounds the blast radius.
 *
 * <p>Built once and shared: the registered tools are stateless Spring singletons
 * and {@code SkillRegistry} reads are thread-safe.
 */
@Component
public class WorkflowEvolveToolRegistryFactory {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEvolveToolRegistryFactory.class);

    private final SkillRegistry registry;

    public WorkflowEvolveToolRegistryFactory(GetOptReportTool getOptReportTool,
                                             TriggerAbEvalTool triggerAbEvalTool,
                                             GetAbResultTool getAbResultTool,
                                             ReconcilePredictionTool reconcilePredictionTool,
                                             RecordIterationTool recordIterationTool,
                                             GetCandidateDiffTool getCandidateDiffTool,
                                             RunOptReportSubflowTool runOptReportSubflowTool) {
        SkillRegistry r = new SkillRegistry();
        for (Tool tool : new Tool[]{
                getOptReportTool,
                triggerAbEvalTool,
                getAbResultTool,
                reconcilePredictionTool,
                recordIterationTool,
                getCandidateDiffTool,
                runOptReportSubflowTool}) {
            r.registerTool(tool);
        }
        this.registry = r;
        log.info("WorkflowEvolveToolRegistryFactory: registered {} tool() whitelist tools: {}",
                r.getAllTools().size(),
                r.getAllTools().stream().map(Tool::getName).sorted().toList());
    }

    /** The shared, read-only whitelist registry for the workflow {@code tool()} binding. */
    public SkillRegistry workflowEvolveToolRegistry() {
        return registry;
    }
}
