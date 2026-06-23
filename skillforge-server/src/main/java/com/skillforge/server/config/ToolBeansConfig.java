package com.skillforge.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.context.BehaviorRuleRegistry;
import com.skillforge.core.compact.CompactableToolRegistry;
import com.skillforge.core.compact.ContextCompactorCallback;
import com.skillforge.core.compact.FullCompactStrategy;
import com.skillforge.core.compact.LightCompactStrategy;
import com.skillforge.core.compact.recovery.FileStateCache;
import com.skillforge.core.compact.recovery.RecoveryPayloadBuilder;
import com.skillforge.core.engine.AgentLoopEngine;
import com.skillforge.core.engine.CancellationRegistry;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.server.acp.AcpAgentRunner;
import com.skillforge.server.acp.AcpClientFactory;
import com.skillforge.server.acp.AcpRunnerProperties;
import com.skillforge.server.acp.AcpUpdateTranslator;
import com.skillforge.server.acp.CcAcpUpdateTranslator;
import com.skillforge.server.acp.ProcessAcpClientFactory;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.core.engine.LoopContext;
import com.skillforge.core.engine.PendingAskRegistry;
import com.skillforge.core.engine.SafetySkillHook;
import com.skillforge.core.engine.hook.LifecycleHookDispatcher;
import com.skillforge.server.hook.LifecycleHookLoopAdapter;
import com.skillforge.server.hook.LifecycleHookSkillAdapter;
import com.skillforge.server.hook.GetAgentHooksTool;
import com.skillforge.server.hook.ProposeHookBindingTool;
import com.skillforge.core.llm.EmbeddingProvider;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.ModelConfig;
import com.skillforge.core.llm.OpenAiEmbeddingProvider;
import com.skillforge.core.skill.SkillPackageLoader;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.tools.BashTool;
import com.skillforge.tools.CodeReviewTool;
import com.skillforge.tools.CodeSandboxTool;
import com.skillforge.tools.FileEditTool;
import com.skillforge.tools.FileReadTool;
import com.skillforge.tools.FileWriteTool;
import com.skillforge.tools.GlobTool;
import com.skillforge.tools.GrepTool;
import com.skillforge.tools.WebFetchTool;
import com.skillforge.tools.WebSearchTool;
import com.skillforge.tools.webfetch.WebFetchConfig;
import com.skillforge.tools.websearch.WebSearchConfig;
import com.skillforge.server.code.CompiledMethodService;
import com.skillforge.server.code.RegisterCompiledMethodTool;
import com.skillforge.server.code.RegisterScriptMethodTool;
import com.skillforge.server.code.ScriptMethodService;
import com.skillforge.server.skill.ImportSkillTool;
import com.skillforge.server.skill.SkillImportProperties;
import com.skillforge.server.skill.SkillImportService;
import com.skillforge.server.skill.TodoStore;
import com.skillforge.server.security.skill.SkillSecurityScanProperties;
import com.skillforge.server.reminder.TodoListSource;
import com.skillforge.server.tool.TodoWriteTool;
import com.skillforge.server.tool.MemoryDetailTool;
import com.skillforge.server.tool.MemorySearchTool;
import com.skillforge.server.tool.MemoryTool;
import com.skillforge.server.tool.AgentDiscoveryTool;
import com.skillforge.server.tool.CreateAgentTool;
import com.skillforge.server.tool.GetAgentConfigTool;
import com.skillforge.server.tool.GetSessionMessagesTool;
import com.skillforge.server.tool.GetTraceTool;
import com.skillforge.server.tool.SubAgentTool;
import com.skillforge.server.tool.TeamCreateTool;
import com.skillforge.server.tool.TeamKillTool;
import com.skillforge.server.tool.TeamListTool;
import com.skillforge.server.tool.TeamSendTool;
import com.skillforge.server.tool.UpdateAgentTool;
import com.skillforge.server.service.AgentAuthoredHookService;
import com.skillforge.server.service.AgentMutationService;
import com.skillforge.server.service.AgentService;
import com.skillforge.server.service.AgentTargetResolver;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.service.EmbeddingService;
import com.skillforge.server.service.LifecycleHookViewService;
import com.skillforge.server.service.MemoryService;
import com.skillforge.server.service.ScheduledTaskService;
import com.skillforge.server.service.SessionService;
import com.skillforge.server.service.UserConfigService;
import com.skillforge.server.memory.transcript.MemoryTranscriptProperties;
import com.skillforge.server.memory.transcript.SessionTranscriptProvider;
import com.skillforge.server.memory.context.MemoryContextProvider;
import com.skillforge.server.tool.memorysynth.ClusterMemoriesTool;
import com.skillforge.server.tool.memorysynth.CreateMemoryProposalTool;
import com.skillforge.server.tool.memorysynth.ListActiveUsersTool;
import com.skillforge.server.tool.memorysynth.ListMemoryCandidatesTool;
import com.skillforge.server.tool.memorysynth.ListRecentSessionTranscriptsTool;
import com.skillforge.server.tool.memorycontext.ListRelevantMemoriesTool;
import com.skillforge.server.tool.scheduling.CreateScheduledTaskTool;
import com.skillforge.server.tool.scheduling.DeleteScheduledTaskTool;
import com.skillforge.server.tool.scheduling.GetScheduledTaskTool;
import com.skillforge.server.tool.scheduling.ListScheduledTasksTool;
import com.skillforge.server.tool.scheduling.UpdateScheduledTaskTool;
import com.skillforge.observability.repository.LlmSpanRepository;
import com.skillforge.observability.repository.LlmTraceRepository;
import com.skillforge.server.tool.sessionannotation.SpanBehaviorStatsTool;
import com.skillforge.server.tool.optreport.GetToolCallSequenceTool;
import com.skillforge.server.tool.optreport.LoadErrorSpanBatchTool;
import com.skillforge.server.tool.optreport.LoadSessionBatchTool;
import com.skillforge.server.tool.optreport.RecordBatchAnnotationsTool;
import com.skillforge.server.tool.optreport.WriteOptReportTool;
import com.skillforge.server.optreport.OptReportService;
import com.skillforge.server.flywheel.run.FlywheelRunRepository;
import com.skillforge.server.flywheel.run.FlywheelRunService;
import com.skillforge.server.flywheel.run.FlywheelRunStepRepository;
import com.skillforge.server.repository.SessionAnnotationRepository;
import com.skillforge.server.subagent.AgentRoster;
import com.skillforge.server.subagent.CollabRunService;
import com.skillforge.server.channel.ChannelConfigService;
import com.skillforge.server.channel.platform.weixin.WeixinChannelAdapter;
import com.skillforge.server.repository.ChannelConversationRepository;
import com.skillforge.server.subagent.SubAgentRegistry;
import com.skillforge.server.tool.channel.SendChannelFileTool;
import org.springframework.context.annotation.Lazy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
public class ToolBeansConfig {

    private static final Logger log = LoggerFactory.getLogger(ToolBeansConfig.class);


    /**
     * P11 MCP-CLIENT: registers MCP tool descriptors as {@code mcp_<server>_<tool>}
     * entries in the global SkillRegistry. Stateless; safe to re-call across
     * lifecycle reconnects.
     */
    @Bean
    public com.skillforge.server.mcp.service.McpToolRegistrar mcpToolRegistrar(
            SkillRegistry skillRegistry, ObjectMapper objectMapper) {
        return new com.skillforge.server.mcp.service.McpToolRegistrar(skillRegistry, objectMapper);
    }


    /**
     * CodeSandboxTool — lets Code Agent test-run bash/node/java snippets in an isolated sandbox
     * before registering them as hook methods.
     */
    @Bean
    public CodeSandboxTool codeSandboxTool(SkillRegistry skillRegistry) {
        CodeSandboxTool tool = new CodeSandboxTool();
        skillRegistry.registerTool(tool);
        log.info("Registered CodeSandboxTool into SkillRegistry");
        return tool;
    }


    /**
     * CodeReviewTool — delegates code review to an LLM provider.
     */
    @Bean
    public CodeReviewTool codeReviewTool(LlmProviderFactory llmProviderFactory,
                                           LlmProperties llmProperties,
                                           SkillRegistry skillRegistry) {
        String providerName = llmProperties.getDefaultProvider() != null
                ? llmProperties.getDefaultProvider() : "claude";
        CodeReviewTool tool = new CodeReviewTool(llmProviderFactory, providerName);
        skillRegistry.registerTool(tool);
        log.info("Registered CodeReviewTool into SkillRegistry (provider={})", providerName);
        return tool;
    }


    /**
     * RegisterScriptMethodTool — lets Code Agent persist a bash/node script as an
     * {@code agent.*} hook method and register it at runtime.
     */
    @Bean
    public RegisterScriptMethodTool registerScriptMethodTool(ScriptMethodService scriptMethodService,
                                                               ObjectMapper objectMapper,
                                                               SkillRegistry skillRegistry) {
        RegisterScriptMethodTool tool = new RegisterScriptMethodTool(scriptMethodService, objectMapper);
        skillRegistry.registerTool(tool);
        log.info("Registered RegisterScriptMethodTool into SkillRegistry");
        return tool;
    }


    /**
     * RegisterCompiledMethodTool — lets Code Agent submit a Java class that implements
     * {@code BuiltInMethod}; source is compiled in-process and stored for human approval before
     * being loaded into the runtime registry.
     */
    @Bean
    public RegisterCompiledMethodTool registerCompiledMethodTool(CompiledMethodService compiledMethodService,
                                                                   ObjectMapper objectMapper,
                                                                   SkillRegistry skillRegistry) {
        RegisterCompiledMethodTool tool = new RegisterCompiledMethodTool(compiledMethodService, objectMapper);
        skillRegistry.registerTool(tool);
        log.info("Registered RegisterCompiledMethodTool into SkillRegistry");
        return tool;
    }


    @Bean
    public GetAgentHooksTool getAgentHooksTool(AgentTargetResolver targetResolver,
                                               LifecycleHookViewService viewService,
                                               ObjectMapper objectMapper,
                                               SkillRegistry skillRegistry) {
        GetAgentHooksTool tool = new GetAgentHooksTool(targetResolver, viewService, objectMapper);
        skillRegistry.registerTool(tool);
        log.info("Registered GetAgentHooksTool into SkillRegistry");
        return tool;
    }


    /**
     * EVAL-V2 Q3: AddEvalScenario lets agents (or operators via the dashboard
     * tool palette) persist a new base eval scenario to the home dir
     * ({@code ~/.skillforge/eval-scenarios/<id>.json}). Picked up by
     * {@link com.skillforge.server.eval.scenario.ScenarioLoader} on the next
     * eval run.
     */
    @Bean
    public com.skillforge.server.tool.AddEvalScenarioTool addEvalScenarioTool(
            com.skillforge.server.eval.scenario.BaseScenarioService baseScenarioService,
            ObjectMapper objectMapper,
            SkillRegistry skillRegistry) {
        com.skillforge.server.tool.AddEvalScenarioTool tool =
                new com.skillforge.server.tool.AddEvalScenarioTool(baseScenarioService, objectMapper);
        skillRegistry.registerTool(tool);
        log.info("Registered AddEvalScenarioTool into SkillRegistry");
        return tool;
    }


    /**
     * WECHAT-CHANNEL slice 2 (Option b): SendChannelFile lets an agent push a local file/image to
     * the user over the channel bound to the current session (currently WeChat). Reads are confined
     * to the chat-attachments root + system temp dir (path-containment guard) since {@code file_path}
     * comes from the LLM.
     */
    @Bean
    public SendChannelFileTool sendChannelFileTool(
            ChannelConversationRepository conversationRepository,
            ChannelConfigService channelConfigService,
            WeixinChannelAdapter weixinChannelAdapter,
            ObjectMapper objectMapper,
            SkillRegistry skillRegistry,
            @Value("${skillforge.chat.attachments.root:./data/chat-attachments}") String attachmentsRoot) {
        SendChannelFileTool tool = new SendChannelFileTool(
                conversationRepository, channelConfigService, weixinChannelAdapter,
                objectMapper, attachmentsRoot);
        skillRegistry.registerTool(tool);
        log.info("Registered SendChannelFileTool into SkillRegistry");
        return tool;
    }


    /**
     * EVAL-V2 M3a (b2): RunEvalTask lets agents kick off a new eval task against
     * an agent definition. Mirrors the operator-facing
     * {@code POST /api/eval/tasks} endpoint. Async dispatch via
     * {@code evalOrchestratorExecutor} (separate pool from
     * {@code evalLoopExecutor} to avoid nested-pool deadlock).
     */
    @Bean
    public com.skillforge.server.tool.RunEvalTaskTool runEvalTaskTool(
            com.skillforge.server.eval.EvalOrchestrator evalOrchestrator,
            com.skillforge.server.repository.EvalTaskRepository evalTaskRepository,
            @org.springframework.beans.factory.annotation.Qualifier("evalOrchestratorExecutor")
            java.util.concurrent.ExecutorService evalOrchestratorExecutor,
            ObjectMapper objectMapper,
            SkillRegistry skillRegistry) {
        com.skillforge.server.tool.RunEvalTaskTool tool =
                new com.skillforge.server.tool.RunEvalTaskTool(
                        evalOrchestrator, evalTaskRepository,
                        evalOrchestratorExecutor, objectMapper);
        skillRegistry.registerTool(tool);
        log.info("Registered RunEvalTaskTool into SkillRegistry");
        return tool;
    }


    /**
     * AUTOEVOLVING — {@code RunWorkflow} lets an agent trigger the DSL workflow
     * engine ({@link com.skillforge.workflow.WorkflowRunnerService}) in three
     * modes: run-by-name, run-inline-script, resume-paused-gate.
     *
     * <p><b>Invariant:</b> registered ONLY here in the main SkillRegistry —
     * deliberately NOT added to {@code WorkflowSkillRegistryFactory} (the workflow
     * sub-agent registry). A workflow's own sub-agents must not be able to trigger
     * another workflow, which would allow unbounded recursion / runaway runs. The
     * tool is registered + assignable but is NOT auto-assigned to any agent's
     * tool_ids; operators decide which agent may use it.
     */
    @Bean
    public com.skillforge.server.tool.workflow.RunWorkflowTool runWorkflowTool(
            com.skillforge.workflow.WorkflowRunnerService workflowRunnerService,
            com.skillforge.workflow.WorkflowDefinitionRegistry workflowDefinitionRegistry,
            ObjectMapper objectMapper,
            SkillRegistry skillRegistry) {
        com.skillforge.server.tool.workflow.RunWorkflowTool tool =
                new com.skillforge.server.tool.workflow.RunWorkflowTool(
                        workflowRunnerService, workflowDefinitionRegistry, objectMapper);
        skillRegistry.registerTool(tool);
        log.info("Registered RunWorkflowTool into SkillRegistry");
        return tool;
    }


    /**
     * AUTOEVOLVE-AGENT-FLYWHEEL Module B (FR-B1) — {@code TriggerAbEval} thin
     * wrapper over the existing async A/B engine (prompt / skill / behavior_rule).
     *
     * <p><b>Invariant:</b> registered ONLY here in the main SkillRegistry —
     * deliberately NOT added to {@code WorkflowSkillRegistryFactory} (the workflow
     * sub-agent registry). A workflow sub-agent reaching {@code TriggerAbEval}
     * would re-open a fan-out / recursion path (same invariant as
     * {@code RunWorkflowTool}). Not auto-assigned to any agent's tool_ids — the
     * orchestrator agent (Module C) declares it explicitly.
     */
    @Bean
    public com.skillforge.server.tool.evolve.TriggerAbEvalTool triggerAbEvalTool(
            com.skillforge.server.improve.PromptImproverService promptImproverService,
            com.skillforge.server.improve.SkillDraftService skillDraftService,
            com.skillforge.server.improve.behavior.BehaviorRuleAbEvalService behaviorRuleAbEvalService,
            com.skillforge.server.improve.agent.AgentEvolveAbEvalService agentEvolveAbEvalService,
            com.skillforge.server.repository.SkillDraftRepository skillDraftRepository,
            com.skillforge.server.repository.BehaviorRuleVersionRepository behaviorRuleVersionRepository,
            FlywheelRunService flywheelRunService,
            @Value("${skillforge.evolve.ab-budget-per-run:30}") int abBudgetPerRun,
            @Value("${skillforge.evolve.ab-budget-window-hours:168}") int abBudgetWindowHours,
            ObjectMapper objectMapper,
            SkillRegistry skillRegistry) {
        com.skillforge.server.tool.evolve.TriggerAbEvalTool tool =
                new com.skillforge.server.tool.evolve.TriggerAbEvalTool(
                        promptImproverService, skillDraftService, behaviorRuleAbEvalService,
                        agentEvolveAbEvalService, skillDraftRepository, behaviorRuleVersionRepository,
                        flywheelRunService, abBudgetPerRun, abBudgetWindowHours, objectMapper);
        skillRegistry.registerTool(tool);
        log.info("Registered TriggerAbEvalTool into SkillRegistry "
                        + "(FR-C7 abBudgetPerRun={} abBudgetWindowHours={})",
                abBudgetPerRun, abBudgetWindowHours);
        return tool;
    }


    /**
     * AUTOEVOLVE-AGENT-FLYWHEEL Module B (FR-B2) — {@code GetAbResult} read-only
     * poll of a surface's ab_run row.
     *
     * <p><b>Invariant:</b> registered ONLY here in the main SkillRegistry — NOT in
     * {@code WorkflowSkillRegistryFactory} (workflow sub-agent registry). Same
     * recursion-isolation invariant as the other Module B tools.
     */
    @Bean
    public com.skillforge.server.tool.evolve.GetAbResultTool getAbResultTool(
            com.skillforge.server.repository.PromptAbRunRepository promptAbRunRepository,
            com.skillforge.server.repository.SkillAbRunRepository skillAbRunRepository,
            com.skillforge.server.repository.BehaviorRuleAbRunRepository behaviorRuleAbRunRepository,
            com.skillforge.server.repository.AgentEvolveAbRunRepository agentEvolveAbRunRepository,
            ObjectMapper objectMapper,
            EvolveThresholdProperties evolveThresholdProperties,
            SkillRegistry skillRegistry) {
        com.skillforge.server.tool.evolve.GetAbResultTool tool =
                new com.skillforge.server.tool.evolve.GetAbResultTool(
                        promptAbRunRepository, skillAbRunRepository,
                        behaviorRuleAbRunRepository, agentEvolveAbRunRepository, objectMapper,
                        evolveThresholdProperties);
        skillRegistry.registerTool(tool);
        log.info("Registered GetAbResultTool into SkillRegistry");
        return tool;
    }


    /**
     * AUTOEVOLVE-CLOSE-LOOP Phase BC-M2b — {@code ListActiveHarvestedScenarios}
     * read-only lookup of an agent's ACTIVE harvested bad-case scenario ids, used
     * by the orchestrator to build the explicit target subset of an A/B run.
     *
     * <p><b>Invariant:</b> registered ONLY here in the main SkillRegistry — NOT in
     * {@code WorkflowSkillRegistryFactory} (workflow sub-agent registry). Same
     * recursion-isolation invariant as the other Module B/C evolve tools.
     */
    @Bean
    public com.skillforge.server.tool.evolve.ListActiveHarvestedScenariosTool listActiveHarvestedScenariosTool(
            com.skillforge.server.repository.EvalScenarioDraftRepository evalScenarioDraftRepository,
            com.skillforge.server.service.EvalDatasetService evalDatasetService,
            ObjectMapper objectMapper,
            SkillRegistry skillRegistry) {
        com.skillforge.server.tool.evolve.ListActiveHarvestedScenariosTool tool =
                new com.skillforge.server.tool.evolve.ListActiveHarvestedScenariosTool(
                        evalScenarioDraftRepository, evalDatasetService, objectMapper);
        skillRegistry.registerTool(tool);
        log.info("Registered ListActiveHarvestedScenariosTool into SkillRegistry");
        return tool;
    }


    /**
     * AUTOEVOLVE-AGENT-FLYWHEEL Module B (FR-B3) — {@code PromoteCandidate} wraps
     * the existing guarded promote services (NO guard bypass) and additionally
     * validates the candidate belongs to {@code targetAgentId}.
     *
     * <p><b>Invariant:</b> registered ONLY here in the main SkillRegistry — NOT in
     * {@code WorkflowSkillRegistryFactory} (workflow sub-agent registry). Same
     * recursion-isolation invariant as the other Module B tools.
     */
    @Bean
    public com.skillforge.server.tool.evolve.PromoteCandidateTool promoteCandidateTool(
            com.skillforge.server.improve.PromptPromotionService promptPromotionService,
            com.skillforge.server.improve.SkillAbEvalService skillAbEvalService,
            com.skillforge.server.improve.BehaviorRulePromotionService behaviorRulePromotionService,
            com.skillforge.server.repository.PromptAbRunRepository promptAbRunRepository,
            com.skillforge.server.repository.SkillAbRunRepository skillAbRunRepository,
            com.skillforge.server.repository.BehaviorRuleVersionRepository behaviorRuleVersionRepository,
            ObjectMapper objectMapper,
            SkillRegistry skillRegistry) {
        com.skillforge.server.tool.evolve.PromoteCandidateTool tool =
                new com.skillforge.server.tool.evolve.PromoteCandidateTool(
                        promptPromotionService, skillAbEvalService, behaviorRulePromotionService,
                        promptAbRunRepository, skillAbRunRepository,
                        behaviorRuleVersionRepository, objectMapper);
        skillRegistry.registerTool(tool);
        log.info("Registered PromoteCandidateTool into SkillRegistry");
        return tool;
    }


    /**
     * AUTOEVOLVE-AGENT-FLYWHEEL Module C (FR-C2) — {@code GenerateCandidate} thin
     * wrapper over the existing improver candidate-gen (prompt / skill /
     * behavior_rule), reusing the same one-shot LLM-fill path
     * {@code AttributionApprovalService} uses. Returns the persisted candidateId.
     *
     * <p><b>Invariant:</b> registered ONLY here in the main SkillRegistry — NOT in
     * {@code WorkflowSkillRegistryFactory} (workflow sub-agent registry). Same
     * recursion-isolation invariant as the Module A/B tools. Declared explicitly
     * in the {@code evolve-orchestrator} agent's tool_ids (V131); not auto-assigned.
     */
    @Bean
    public com.skillforge.server.tool.evolve.GenerateCandidateTool generateCandidateTool(
            com.skillforge.server.improve.PromptImproverService promptImproverService,
            com.skillforge.server.improve.SkillDraftService skillDraftService,
            com.skillforge.server.improve.BehaviorRuleImproverService behaviorRuleImproverService,
            com.skillforge.server.optreport.OptReportToEventBridge optReportToEventBridge,
            com.skillforge.server.flywheel.run.FlywheelRunRepository flywheelRunRepository,
            ObjectMapper objectMapper,
            SkillRegistry skillRegistry) {
        com.skillforge.server.tool.evolve.GenerateCandidateTool tool =
                new com.skillforge.server.tool.evolve.GenerateCandidateTool(
                        promptImproverService, skillDraftService,
                        behaviorRuleImproverService, optReportToEventBridge,
                        flywheelRunRepository, objectMapper);
        skillRegistry.registerTool(tool);
        log.info("Registered GenerateCandidateTool into SkillRegistry");
        return tool;
    }


    /**
     * AUTOEVOLVE-AGENT-FLYWHEEL Module C — {@code GetOptReport} read-only tool:
     * returns a completed opt-report's {@code topIssues} so the orchestrator can
     * drive its loop ({@code RunWorkflow('opt-report')} is async + returns only a
     * runId). Reuses {@code FlywheelRunRepository} + the existing
     * {@code OptReportSummaryParser}.
     *
     * <p><b>Invariant:</b> registered ONLY here in the main SkillRegistry — NOT in
     * {@code WorkflowSkillRegistryFactory}. Same recursion-isolation invariant as
     * the other Module A/B/C tools. Declared in the {@code evolve-orchestrator}
     * agent's tool_ids (V132).
     */
    @Bean
    public com.skillforge.server.tool.evolve.GetOptReportTool getOptReportTool(
            com.skillforge.server.flywheel.run.FlywheelRunRepository flywheelRunRepository,
            com.skillforge.server.optreport.dto.OptReportSummaryParser optReportSummaryParser,
            ObjectMapper objectMapper,
            SkillRegistry skillRegistry) {
        com.skillforge.server.tool.evolve.GetOptReportTool tool =
                new com.skillforge.server.tool.evolve.GetOptReportTool(
                        flywheelRunRepository, optReportSummaryParser, objectMapper);
        skillRegistry.registerTool(tool);
        log.info("Registered GetOptReportTool into SkillRegistry");
        return tool;
    }


    /**
     * AUTOEVOLVE-AGENT-FLYWHEEL Module C (RecordIteration) — {@code RecordIteration}
     * appends an {@code evolve_iteration} ledger step (no new table; reuses
     * {@code t_flywheel_run_step}). These rows are also the FR-C7 A/B budget counter.
     *
     * <p><b>Invariant:</b> registered ONLY here in the main SkillRegistry — NOT in
     * {@code WorkflowSkillRegistryFactory}. Same recursion-isolation invariant.
     */
    @Bean
    public com.skillforge.server.tool.evolve.RecordIterationTool recordIterationTool(
            FlywheelRunService flywheelRunService,
            ObjectMapper objectMapper,
            SkillRegistry skillRegistry) {
        com.skillforge.server.tool.evolve.RecordIterationTool tool =
                new com.skillforge.server.tool.evolve.RecordIterationTool(
                        flywheelRunService, objectMapper);
        skillRegistry.registerTool(tool);
        log.info("Registered RecordIterationTool into SkillRegistry");
        return tool;
    }


    /**
     * AUTOEVOLVE-CLOSE-LOOP Phase BC-M2b (G3) — {@code ReconcilePrediction}
     * deterministic prediction-vs-actual reconciliation of a whole-agent A/B run.
     *
     * <p><b>Invariant:</b> registered ONLY here in the main SkillRegistry — NOT in
     * {@code WorkflowSkillRegistryFactory}. Same recursion-isolation invariant as
     * the other Module B/C evolve tools.
     */
    @Bean
    public com.skillforge.server.tool.evolve.ReconcilePredictionTool reconcilePredictionTool(
            com.skillforge.server.repository.AgentEvolveAbRunRepository agentEvolveAbRunRepository,
            ObjectMapper objectMapper,
            SkillRegistry skillRegistry) {
        com.skillforge.server.tool.evolve.ReconcilePredictionTool tool =
                new com.skillforge.server.tool.evolve.ReconcilePredictionTool(
                        agentEvolveAbRunRepository, objectMapper);
        skillRegistry.registerTool(tool);
        log.info("Registered ReconcilePredictionTool into SkillRegistry");
        return tool;
    }


    /**
     * AUTOEVOLVE-CLOSE-LOOP P1 — {@code GetCandidateDiff} read-only tool: resolves
     * before/after text + unified diff of a generated candidate so the evolve-loop
     * workflow JS can emit a {@code semanticDelta} into the iteration ledger.
     *
     * <p><b>Workflow-only (recursion guard):</b> NOT registered into the main
     * {@code SkillRegistry} and NOT into {@code WorkflowSkillRegistryFactory} — it
     * is reachable ONLY via the {@code tool()} whitelist
     * ({@code WorkflowEvolveToolRegistryFactory}). The legacy orchestrator agent
     * has no use for it (it never assembled diffs).
     */
    @Bean
    public com.skillforge.server.tool.evolve.GetCandidateDiffTool getCandidateDiffTool(
            com.skillforge.server.repository.PromptVersionRepository promptVersionRepository,
            com.skillforge.server.repository.BehaviorRuleVersionRepository behaviorRuleVersionRepository,
            com.skillforge.server.repository.SkillDraftRepository skillDraftRepository,
            com.skillforge.server.repository.AgentRepository agentRepository,
            ObjectMapper objectMapper) {
        com.skillforge.server.tool.evolve.GetCandidateDiffTool tool =
                new com.skillforge.server.tool.evolve.GetCandidateDiffTool(
                        promptVersionRepository, behaviorRuleVersionRepository,
                        skillDraftRepository, agentRepository, objectMapper);
        log.info("Built GetCandidateDiffTool (workflow tool() whitelist only)");
        return tool;
    }


    /**
     * AUTOEVOLVE-CLOSE-LOOP P1 — {@code RunOptReportSubflow}: runs opt-report as a
     * blocking sub-flow when the evolve loop was not handed a pre-existing reportId.
     *
     * <p><b>Workflow-only (recursion guard):</b> NOT registered into the main
     * {@code SkillRegistry} nor {@code WorkflowSkillRegistryFactory} — reachable
     * ONLY via the {@code tool()} whitelist. {@code WorkflowRunnerService} is
     * injected {@code @Lazy} to break the construction cycle
     * (WorkflowRunnerService → WorkflowToolInvokerFactory →
     * WorkflowEvolveToolRegistryFactory → this tool → WorkflowRunnerService).
     *
     * <p><b>The cycle is ONLY in the Spring construction graph, NOT at runtime.</b>
     * At runtime this tool calls {@code workflowRunnerService.startRun("opt-report",
     * ...)} — a DIFFERENT workflow name than the evolve-loop that invoked it, and
     * {@code ConsolidationLock} is keyed per-name (spike-verified Q1), so the nested
     * sub-flow never self-locks and there is no runtime recursion. The {@code @Lazy}
     * is purely a bean-instantiation-ordering device; do NOT "simplify" it away.
     */
    @Bean
    public com.skillforge.server.tool.evolve.RunOptReportSubflowTool runOptReportSubflowTool(
            @org.springframework.context.annotation.Lazy
            com.skillforge.workflow.WorkflowRunnerService workflowRunnerService,
            com.skillforge.server.flywheel.run.FlywheelRunRepository flywheelRunRepository,
            ObjectMapper objectMapper) {
        com.skillforge.server.tool.evolve.RunOptReportSubflowTool tool =
                new com.skillforge.server.tool.evolve.RunOptReportSubflowTool(
                        workflowRunnerService, flywheelRunRepository, objectMapper);
        log.info("Built RunOptReportSubflowTool (workflow tool() whitelist only)");
        return tool;
    }


    @Bean
    public com.skillforge.server.tool.AnalyzeEvalTaskTool analyzeEvalTaskTool(
            com.skillforge.server.repository.EvalTaskRepository evalTaskRepository,
            ObjectMapper objectMapper,
            SkillRegistry skillRegistry) {
        com.skillforge.server.tool.AnalyzeEvalTaskTool tool =
                new com.skillforge.server.tool.AnalyzeEvalTaskTool(evalTaskRepository, objectMapper);
        skillRegistry.registerTool(tool);
        log.info("Registered AnalyzeEvalTaskTool into SkillRegistry");
        return tool;
    }


    /**
     * V5 EVAL-DYNAMIC-USER-SIM Phase 1.2: register {@code RunSimulatorTrial}
     * (programmatic kickoff for REST controller / future agent dispatch — NOT
     * UserSim-consumed) into the global SkillRegistry.
     */
    @Bean
    public com.skillforge.server.tool.sim.RunSimulatorTrial runSimulatorTrialTool(
            com.skillforge.server.eval.usersim.SimulatorTrialOrchestrator orchestrator,
            ObjectMapper objectMapper,
            SkillRegistry skillRegistry) {
        com.skillforge.server.tool.sim.RunSimulatorTrial tool =
                new com.skillforge.server.tool.sim.RunSimulatorTrial(orchestrator, objectMapper);
        skillRegistry.registerTool(tool);
        log.info("Registered RunSimulatorTrial into SkillRegistry");
        return tool;
    }


    /**
     * V5 EVAL-DYNAMIC-USER-SIM Phase 1.2: register {@code RecordSimulationResult}
     * (UserSim self-call on termination) into the global SkillRegistry.
     */
    @Bean
    public com.skillforge.server.tool.sim.RecordSimulationResult recordSimulationResultTool(
            com.skillforge.server.repository.SimulatorTrialRepository trialRepository,
            ObjectMapper objectMapper,
            SkillRegistry skillRegistry) {
        com.skillforge.server.tool.sim.RecordSimulationResult tool =
                new com.skillforge.server.tool.sim.RecordSimulationResult(trialRepository, objectMapper);
        skillRegistry.registerTool(tool);
        log.info("Registered RecordSimulationResult into SkillRegistry");
        return tool;
    }


    @Bean
    public ProposeHookBindingTool proposeHookBindingTool(AgentTargetResolver targetResolver,
                                                         AgentAuthoredHookService agentAuthoredHookService,
                                                         ObjectMapper objectMapper,
                                                         SkillRegistry skillRegistry) {
        ProposeHookBindingTool tool = new ProposeHookBindingTool(targetResolver, agentAuthoredHookService, objectMapper);
        skillRegistry.registerTool(tool);
        log.info("Registered ProposeHookBindingTool into SkillRegistry");
        return tool;
    }


    @Bean
    public TodoWriteTool todoWriteTool(TodoStore todoStore, SkillRegistry skillRegistry) {
        TodoWriteTool tool = new TodoWriteTool(todoStore);
        skillRegistry.registerTool(tool);
        log.info("Registered TodoWriteTool into SkillRegistry");
        return tool;
    }


    /**
     * SKILL-IMPORT — registers the {@code ImportSkill} agent tool. Lets the
     * agent register a skill it has installed externally (ClawHub / GitHub /
     * SkillHub) into SkillForge. See {@link SkillImportService}.
     */
    @Bean
    public ImportSkillTool importSkillTool(SkillImportService skillImportService,
                                           ObjectMapper objectMapper,
                                           SkillRegistry skillRegistry) {
        ImportSkillTool tool = new ImportSkillTool(skillImportService, objectMapper);
        skillRegistry.registerTool(tool);
        log.info("Registered ImportSkillTool into SkillRegistry");
        return tool;
    }


    // ClawHub Java 链路（ClawHubTool / ClawHubInstallService 等）已于 P1-D 删除，
    // 由 system-skills/clawhub/ 文件化 Skill 接管（机制 B）。

    /**
     * SubAgentTool — 异步派发任务给另一个 agentId 指向的子 Agent。
     * ChatService 用 @Lazy 打破 ChatService ↔ SubAgentRegistry ↔ SubAgentTool 依赖环。
     */
    @Bean
    public AgentDiscoveryTool agentDiscoveryTool(AgentTargetResolver targetResolver,
                                                 ObjectMapper objectMapper,
                                                 SkillRegistry skillRegistry) {
        AgentDiscoveryTool tool = new AgentDiscoveryTool(targetResolver, objectMapper);
        skillRegistry.registerTool(tool);
        log.info("Registered AgentDiscoveryTool into SkillRegistry");
        return tool;
    }


    @Bean
    public GetAgentConfigTool getAgentConfigTool(AgentTargetResolver targetResolver,
                                                 ObjectMapper objectMapper,
                                                 SkillRegistry skillRegistry) {
        GetAgentConfigTool tool = new GetAgentConfigTool(targetResolver, objectMapper);
        skillRegistry.registerTool(tool);
        log.info("Registered GetAgentConfigTool into SkillRegistry");
        return tool;
    }


    @Bean
    public CreateAgentTool createAgentTool(AgentService agentService,
                                           ObjectMapper objectMapper,
                                           com.skillforge.core.engine.confirm.ToolApprovalRegistry toolApprovalRegistry,
                                           SkillRegistry skillRegistry) {
        CreateAgentTool tool = new CreateAgentTool(agentService, objectMapper, toolApprovalRegistry);
        skillRegistry.registerTool(tool);
        log.info("Registered CreateAgentTool into SkillRegistry");
        return tool;
    }


    @Bean
    public UpdateAgentTool updateAgentTool(AgentTargetResolver targetResolver,
                                           AgentMutationService mutationService,
                                           ObjectMapper objectMapper,
                                           com.skillforge.core.engine.confirm.ToolApprovalRegistry toolApprovalRegistry,
                                           SkillRegistry skillRegistry) {
        UpdateAgentTool tool = new UpdateAgentTool(targetResolver, mutationService, objectMapper, toolApprovalRegistry);
        skillRegistry.registerTool(tool);
        log.info("Registered UpdateAgentTool into SkillRegistry");
        return tool;
    }


    @Bean
    public GetTraceTool getTraceTool(com.skillforge.observability.api.LlmTraceStore llmTraceStore,
                                     com.skillforge.observability.repository.LlmTraceRepository llmTraceRepository,
                                     SessionService sessionService,
                                     ObjectMapper objectMapper,
                                     SkillRegistry skillRegistry) {
        // OBS-2 M6: read path is fully unified on t_llm_trace + t_llm_span.
        GetTraceTool tool = new GetTraceTool(llmTraceStore, llmTraceRepository, sessionService, objectMapper);
        skillRegistry.registerTool(tool);
        log.info("Registered GetTraceTool into SkillRegistry");
        return tool;
    }


    @Bean
    public GetSessionMessagesTool getSessionMessagesTool(SessionService sessionService,
                                                         ObjectMapper objectMapper,
                                                         SkillRegistry skillRegistry) {
        GetSessionMessagesTool tool = new GetSessionMessagesTool(sessionService, objectMapper);
        skillRegistry.registerTool(tool);
        log.info("Registered GetSessionMessagesTool into SkillRegistry");
        return tool;
    }


    @Bean
    public SubAgentTool subAgentTool(AgentTargetResolver targetResolver,
                                       SessionService sessionService,
                                       @Lazy ChatService chatService,
                                       SubAgentRegistry subAgentRegistry,
                                       CancellationRegistry cancellationRegistry,
                                       SkillRegistry skillRegistry,
                                       AgentService agentService,
                                       com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                                       AcpAgentRunner acpAgentRunner) {
        // SKILL-CREATOR-WITH-EVAL Phase 1.1 (2026-05-18): objectMapper added
        // so handleDispatch can JSON-serialise the new skillIdsOverride input
        // list into the child session's skill_overrides_json column.
        // ACP-EXTERNAL-AGENT P1c-1: acpAgentRunner added so a dispatch to an
        // acp:-prefixed agent (claude-code) routes to cc instead of the engine.
        SubAgentTool tool = new SubAgentTool(targetResolver, sessionService, chatService,
                subAgentRegistry, cancellationRegistry, agentService, objectMapper, acpAgentRunner);
        skillRegistry.registerTool(tool);
        log.info("Registered SubAgentTool into SkillRegistry");
        return tool;
    }


    /**
     * TeamCreateTool — spawn a team member in a multi-agent collaboration run.
     */
    @Bean
    public TeamCreateTool teamCreateTool(SessionService sessionService,
                                           CollabRunService collabRunService,
                                           SkillRegistry skillRegistry) {
        TeamCreateTool tool = new TeamCreateTool(sessionService, collabRunService);
        skillRegistry.registerTool(tool);
        log.info("Registered TeamCreateTool into SkillRegistry");
        return tool;
    }


    /**
     * TeamListTool — list team members in the current collaboration run.
     */
    @Bean
    public TeamListTool teamListTool(SessionService sessionService,
                                       AgentRoster agentRoster,
                                       SkillRegistry skillRegistry) {
        TeamListTool tool = new TeamListTool(sessionService, agentRoster);
        skillRegistry.registerTool(tool);
        log.info("Registered TeamListTool into SkillRegistry");
        return tool;
    }


    /**
     * TeamKillTool — cancel a running team member or the entire collab run.
     */
    @Bean
    public TeamKillTool teamKillTool(SessionService sessionService,
                                       AgentRoster agentRoster,
                                       CollabRunService collabRunService,
                                       CancellationRegistry cancellationRegistry,
                                       SkillRegistry skillRegistry) {
        TeamKillTool tool = new TeamKillTool(sessionService, agentRoster, collabRunService, cancellationRegistry);
        skillRegistry.registerTool(tool);
        log.info("Registered TeamKillTool into SkillRegistry");
        return tool;
    }


    /**
     * TeamSendTool — send peer messages between team members in a collaboration run.
     */
    @Bean
    public TeamSendTool teamSendTool(SessionService sessionService,
                                       AgentRoster agentRoster,
                                       SubAgentRegistry subAgentRegistry,
                                       ChatEventBroadcaster broadcaster,
                                       com.skillforge.core.engine.TraceCollector traceCollector,
                                       SkillRegistry skillRegistry) {
        TeamSendTool tool = new TeamSendTool(sessionService, agentRoster, subAgentRegistry, broadcaster, traceCollector);
        skillRegistry.registerTool(tool);
        log.info("Registered TeamSendTool into SkillRegistry");
        return tool;
    }


    // ---------- P12 scheduled task tools ----------

    @Bean
    public CreateScheduledTaskTool createScheduledTaskTool(ScheduledTaskService scheduledTaskService,
                                                           SessionService sessionService,
                                                           ObjectMapper objectMapper,
                                                           SkillRegistry skillRegistry) {
        CreateScheduledTaskTool tool = new CreateScheduledTaskTool(
                scheduledTaskService, sessionService, objectMapper);
        skillRegistry.registerTool(tool);
        log.info("Registered CreateScheduledTaskTool into SkillRegistry");
        return tool;
    }


    @Bean
    public UpdateScheduledTaskTool updateScheduledTaskTool(ScheduledTaskService scheduledTaskService,
                                                           ObjectMapper objectMapper,
                                                           SkillRegistry skillRegistry) {
        UpdateScheduledTaskTool tool = new UpdateScheduledTaskTool(scheduledTaskService, objectMapper);
        skillRegistry.registerTool(tool);
        log.info("Registered UpdateScheduledTaskTool into SkillRegistry");
        return tool;
    }


    @Bean
    public DeleteScheduledTaskTool deleteScheduledTaskTool(ScheduledTaskService scheduledTaskService,
                                                           SkillRegistry skillRegistry) {
        DeleteScheduledTaskTool tool = new DeleteScheduledTaskTool(scheduledTaskService);
        skillRegistry.registerTool(tool);
        log.info("Registered DeleteScheduledTaskTool into SkillRegistry");
        return tool;
    }


    @Bean
    public ListScheduledTasksTool listScheduledTasksTool(ScheduledTaskService scheduledTaskService,
                                                         ObjectMapper objectMapper,
                                                         SkillRegistry skillRegistry) {
        ListScheduledTasksTool tool = new ListScheduledTasksTool(scheduledTaskService, objectMapper);
        skillRegistry.registerTool(tool);
        log.info("Registered ListScheduledTasksTool into SkillRegistry");
        return tool;
    }


    @Bean
    public GetScheduledTaskTool getScheduledTaskTool(ScheduledTaskService scheduledTaskService,
                                                     ObjectMapper objectMapper,
                                                     SkillRegistry skillRegistry) {
        GetScheduledTaskTool tool = new GetScheduledTaskTool(scheduledTaskService, objectMapper);
        skillRegistry.registerTool(tool);
        log.info("Registered GetScheduledTaskTool into SkillRegistry");
        return tool;
    }


    // ---------- MEMORY-LLM-SYNTHESIS dogfood tools (D22 fan-out + per-user curator) ----------

    @Bean
    public ListActiveUsersTool listActiveUsersTool(
            com.skillforge.server.repository.SessionRepository sessionRepository,
            ObjectMapper objectMapper,
            SkillRegistry skillRegistry) {
        ListActiveUsersTool tool = new ListActiveUsersTool(sessionRepository, objectMapper);
        skillRegistry.registerTool(tool);
        log.info("Registered ListActiveUsersTool into SkillRegistry");
        return tool;
    }


    @Bean
    public ListMemoryCandidatesTool listMemoryCandidatesTool(
            com.skillforge.server.repository.MemoryRepository memoryRepository,
            ObjectMapper objectMapper,
            SkillRegistry skillRegistry) {
        ListMemoryCandidatesTool tool = new ListMemoryCandidatesTool(memoryRepository, objectMapper);
        skillRegistry.registerTool(tool);
        log.info("Registered ListMemoryCandidatesTool into SkillRegistry");
        return tool;
    }


    @Bean
    public ListRecentSessionTranscriptsTool listRecentSessionTranscriptsTool(
            SessionTranscriptProvider transcriptProvider,
            MemoryTranscriptProperties memoryTranscriptProperties,
            ObjectMapper objectMapper,
            SkillRegistry skillRegistry) {
        ListRecentSessionTranscriptsTool tool = new ListRecentSessionTranscriptsTool(
                transcriptProvider, memoryTranscriptProperties, objectMapper);
        skillRegistry.registerTool(tool);
        log.info("Registered ListRecentSessionTranscriptsTool into SkillRegistry");
        return tool;
    }


    @Bean
    public ClusterMemoriesTool clusterMemoriesTool(
            com.skillforge.server.memory.llmsynth.MemoryClusterer memoryClusterer,
            com.skillforge.server.repository.MemoryRepository memoryRepository,
            ObjectMapper objectMapper,
            SkillRegistry skillRegistry) {
        ClusterMemoriesTool tool = new ClusterMemoriesTool(memoryClusterer, memoryRepository, objectMapper);
        skillRegistry.registerTool(tool);
        log.info("Registered ClusterMemoriesTool into SkillRegistry");
        return tool;
    }


    @Bean
    public CreateMemoryProposalTool createMemoryProposalTool(
            com.skillforge.server.repository.MemoryProposalRepository memoryProposalRepository,
            com.skillforge.server.repository.MemoryRepository memoryRepository,
            ObjectMapper objectMapper,
            SkillRegistry skillRegistry) {
        CreateMemoryProposalTool tool = new CreateMemoryProposalTool(
                memoryProposalRepository, memoryRepository, objectMapper);
        skillRegistry.registerTool(tool);
        log.info("Registered CreateMemoryProposalTool into SkillRegistry");
        return tool;
    }


    @Bean
    public ListRelevantMemoriesTool listRelevantMemoriesTool(
            MemoryContextProvider memoryContextProvider,
            ObjectMapper objectMapper,
            SkillRegistry skillRegistry) {
        ListRelevantMemoriesTool tool = new ListRelevantMemoriesTool(memoryContextProvider, objectMapper);
        skillRegistry.registerTool(tool);
        log.info("Registered ListRelevantMemoriesTool into SkillRegistry");
        return tool;
    }


    /**
     * PROD-LABEL-CLUSTER V1 Phase 1.2 — STEP 1 of the session-annotator agent
     * pipeline. AnnotateSession (STEP 2.2, registered below) lands in Phase 1.3;
     * RecomputeClusters (STEP 3) lands in Phase 1.4. Until RecomputeClusters
     * ships, session-annotator agent runs that reach STEP 3 will partial-fail
     * (tool not in toolRegistry) — see §4.1 prompt CONSTRAINT "If a tool returns
     * an error, log it and proceed"; STEP 1 + STEP 2 still succeed.
     */
    @Bean
    public com.skillforge.server.tool.sessionannotation.DetectSignalAnnotationsTool detectSignalAnnotationsTool(
            com.skillforge.server.sessionannotation.SessionAnnotationSignalService signalService,
            ObjectMapper objectMapper,
            SkillRegistry skillRegistry) {
        com.skillforge.server.tool.sessionannotation.DetectSignalAnnotationsTool tool =
                new com.skillforge.server.tool.sessionannotation.DetectSignalAnnotationsTool(signalService, objectMapper);
        skillRegistry.registerTool(tool);
        log.info("Registered DetectSignalAnnotationsTool into SkillRegistry");
        return tool;
    }


    /**
     * ANNOTATOR-BEHAVIOR-SIGNALS (2026-05-22) — STEP 1.5 of the session-annotator
     * agent pipeline. Computes behavioral efficiency stats (per-tool counts,
     * total turns, error span count) from {@code t_llm_span} + latest
     * {@code t_llm_trace} for a given session. V96 migration adds
     * {@code SpanBehaviorStats} to the session-annotator's tool_ids + injects
     * STEP 1.5 into the system_prompt.
     */
    @Bean
    public SpanBehaviorStatsTool spanBehaviorStatsTool(
            LlmSpanRepository spanRepository,
            LlmTraceRepository traceRepository,
            ObjectMapper objectMapper,
            SkillRegistry skillRegistry) {
        SpanBehaviorStatsTool tool = new SpanBehaviorStatsTool(spanRepository, traceRepository, objectMapper);
        skillRegistry.registerTool(tool);
        log.info("Registered SpanBehaviorStatsTool into SkillRegistry");
        return tool;
    }


    // ---------- OPT-REPORT-V1 (V97) tools ----------

    /**
     * OPT-REPORT-V1 STEP 1 / STEP 5: load the target agent's recent production
     * sessions (origin='production', parent_session_id IS NULL, within
     * windowDays) paginated; each item bundles existing annotations so the
     * report-generator can fan out / read back annotation state in one call.
     */
    @Bean
    public LoadSessionBatchTool loadSessionBatchTool(
            com.skillforge.server.repository.SessionRepository sessionRepository,
            SessionAnnotationRepository annotationRepository,
            com.skillforge.server.repository.AgentRepository agentRepository,
            com.skillforge.server.repository.SessionPatternRepository patternRepository,
            ObjectMapper objectMapper,
            java.time.Clock clock,
            SkillRegistry skillRegistry) {
        LoadSessionBatchTool tool = new LoadSessionBatchTool(
                sessionRepository, annotationRepository, agentRepository,
                patternRepository, objectMapper, clock);
        skillRegistry.registerTool(tool);
        log.info("Registered LoadSessionBatchTool into SkillRegistry");
        return tool;
    }


    /**
     * AUTOEVOLVE-CLOSE-LOOP G5 段1: load the target agent's failed tool-call spans
     * grouped by (toolName + error signature) across its production sessions, for
     * the holistic-error-span-analyzer workflow sub-agent.
     *
     * <p>Deliberately NOT registered into the global {@code SkillRegistry} — it is
     * a workflow-only read-only tool, wired solely into
     * {@code WorkflowSkillRegistryFactory} (least-privilege; never reachable by an
     * arbitrary user session sub-agent).
     */
    @Bean
    public LoadErrorSpanBatchTool loadErrorSpanBatchTool(
            com.skillforge.server.repository.SessionRepository sessionRepository,
            LlmSpanRepository spanRepository,
            ObjectMapper objectMapper,
            java.time.Clock clock) {
        return new LoadErrorSpanBatchTool(sessionRepository, spanRepository, objectMapper, clock);
    }


    /**
     * AUTOEVOLVE-CLOSE-LOOP G5 段2: return a session's ordered tool-call sequence
     * (compact, tool-only) so the holistic analyzer can infer the missing
     * precondition behind a failing call.
     *
     * <p>Same least-privilege treatment as {@code loadErrorSpanBatchTool} — NOT in
     * the global {@code SkillRegistry}, only in {@code WorkflowSkillRegistryFactory}.
     */
    @Bean
    public GetToolCallSequenceTool getToolCallSequenceTool(
            LlmSpanRepository spanRepository,
            ObjectMapper objectMapper) {
        return new GetToolCallSequenceTool(spanRepository, objectMapper);
    }


    /**
     * OPT-REPORT-V1 STEP 7: persist generated markdown + summary JSON to
     * t_opt_report (status running → completed). Also fires the
     * opt_report_completed WS broadcast via OptReportService.onReportCompleted.
     */
    @Bean
    public WriteOptReportTool writeOptReportTool(
            FlywheelRunRepository reportRepository,
            FlywheelRunService flywheelRunService,
            OptReportService reportService,
            ObjectMapper objectMapper,
            SkillRegistry skillRegistry) {
        WriteOptReportTool tool = new WriteOptReportTool(
                reportRepository, flywheelRunService, reportService, objectMapper);
        skillRegistry.registerTool(tool);
        log.info("Registered WriteOptReportTool into SkillRegistry");
        return tool;
    }


    /**
     * OPT-REPORT-V1 STEP B: SubAgent worker reports back batch completion
     * (annotationsWrittenCount + status) to t_flywheel_run_step (post-V124
     * rename; legacy name "t_opt_report_batch" served via SQL view).
     */
    @Bean
    public RecordBatchAnnotationsTool recordBatchAnnotationsTool(
            FlywheelRunStepRepository batchRepository,
            ObjectMapper objectMapper,
            SkillRegistry skillRegistry) {
        RecordBatchAnnotationsTool tool = new RecordBatchAnnotationsTool(batchRepository, objectMapper);
        skillRegistry.registerTool(tool);
        log.info("Registered RecordBatchAnnotationsTool into SkillRegistry");
        return tool;
    }


    /**
     * PROD-LABEL-CLUSTER V1 Phase 1.3 — STEP 2.2 of the session-annotator agent
     * pipeline. After STEP 2.1 (the agent calls {@code GetTrace} to fetch trace
     * summary + span tree — that tool already exists, wired through
     * {@code GetTraceTool}'s own @Bean), this tool persists the LLM judgment as
     * 2-3 {@code source='llm'} rows in {@code t_session_annotation}. V76
     * migration adds {@code GetTrace} + {@code AnnotateSession} to the
     * session-annotator's tool_ids.
     */
    @Bean
    public com.skillforge.server.tool.sessionannotation.AnnotateSessionTool annotateSessionTool(
            com.skillforge.server.sessionannotation.SessionAnnotationLlmService llmService,
            ObjectMapper objectMapper,
            SkillRegistry skillRegistry) {
        com.skillforge.server.tool.sessionannotation.AnnotateSessionTool tool =
                new com.skillforge.server.tool.sessionannotation.AnnotateSessionTool(llmService, objectMapper);
        skillRegistry.registerTool(tool);
        log.info("Registered AnnotateSessionTool into SkillRegistry");
        return tool;
    }


    /**
     * PROD-LABEL-CLUSTER V1 Phase 1.4 — STEP 3 of the session-annotator agent
     * pipeline. Bucket production sessions by their LLM-judged 4-tuple
     * (outcome × suspect_surface × top_failing_tool × agent_id), upsert
     * {@code t_session_pattern} rows for buckets with ≥ 3 members, and append
     * {@code t_pattern_session_member} rows for any new sessions in those
     * buckets. Tool name matches the V75 seed {@code tool_ids} array.
     */
    @Bean
    public com.skillforge.server.tool.sessionannotation.RecomputeClustersTool recomputeClustersTool(
            com.skillforge.server.sessionannotation.SessionPatternClusterService clusterService,
            ObjectMapper objectMapper,
            SkillRegistry skillRegistry) {
        com.skillforge.server.tool.sessionannotation.RecomputeClustersTool tool =
                new com.skillforge.server.tool.sessionannotation.RecomputeClustersTool(clusterService, objectMapper);
        skillRegistry.registerTool(tool);
        log.info("Registered RecomputeClustersTool into SkillRegistry");
        return tool;
    }


    /**
     * SKILL-CANARY-ROLLOUT V2 Phase 1.4 — sole step of the {@code metrics-collector}
     * agent pipeline. The V79-seeded {@code ScheduledTask} fires this agent
     * hourly; the agent invokes {@code RecomputeMetrics} with default
     * {@code window_hours=1}. Service is auto-discovered (constructor injection).
     */
    @Bean
    public com.skillforge.server.tool.canary.RecomputeMetricsTool recomputeMetricsTool(
            com.skillforge.server.canary.CanaryMetricsService metricsService,
            ObjectMapper objectMapper,
            SkillRegistry skillRegistry) {
        com.skillforge.server.tool.canary.RecomputeMetricsTool tool =
                new com.skillforge.server.tool.canary.RecomputeMetricsTool(metricsService, objectMapper);
        skillRegistry.registerTool(tool);
        log.info("Registered RecomputeMetricsTool into SkillRegistry");
        return tool;
    }


    @Bean
    public com.skillforge.server.tool.attribution.PatternReadTool patternReadTool(
            com.skillforge.server.repository.SessionPatternRepository patternRepository,
            com.skillforge.server.repository.PatternSessionMemberRepository memberRepository,
            ObjectMapper objectMapper,
            SkillRegistry skillRegistry) {
        com.skillforge.server.tool.attribution.PatternReadTool tool =
                new com.skillforge.server.tool.attribution.PatternReadTool(
                        patternRepository, memberRepository, objectMapper);
        skillRegistry.registerTool(tool);
        log.info("Registered PatternReadTool into SkillRegistry");
        return tool;
    }


    @Bean
    public com.skillforge.server.tool.attribution.SessionAnnotationReadTool sessionAnnotationReadTool(
            com.skillforge.server.repository.SessionAnnotationRepository annotationRepository,
            ObjectMapper objectMapper,
            SkillRegistry skillRegistry) {
        com.skillforge.server.tool.attribution.SessionAnnotationReadTool tool =
                new com.skillforge.server.tool.attribution.SessionAnnotationReadTool(
                        annotationRepository, objectMapper);
        skillRegistry.registerTool(tool);
        log.info("Registered SessionAnnotationReadTool into SkillRegistry");
        return tool;
    }


    @Bean
    public com.skillforge.server.tool.attribution.ProposeOptimizationTool proposeOptimizationTool(
            com.skillforge.server.repository.OptimizationEventRepository eventRepository,
            com.skillforge.server.repository.SessionPatternRepository patternRepository,
            ObjectMapper objectMapper,
            java.time.Clock clock,
            com.skillforge.server.attribution.AttributionEventBroadcaster broadcaster,
            SkillRegistry skillRegistry) {
        com.skillforge.server.tool.attribution.ProposeOptimizationTool tool =
                new com.skillforge.server.tool.attribution.ProposeOptimizationTool(
                        eventRepository, patternRepository, objectMapper, clock, broadcaster);
        skillRegistry.registerTool(tool);
        log.info("Registered ProposeOptimizationTool into SkillRegistry");
        return tool;
    }


    @Bean
    public com.skillforge.server.tool.attribution.WriteOptimizationEventTool writeOptimizationEventTool(
            com.skillforge.server.repository.OptimizationEventRepository eventRepository,
            com.skillforge.server.repository.SessionPatternRepository patternRepository,
            ObjectMapper objectMapper,
            java.time.Clock clock,
            SkillRegistry skillRegistry) {
        com.skillforge.server.tool.attribution.WriteOptimizationEventTool tool =
                new com.skillforge.server.tool.attribution.WriteOptimizationEventTool(
                        eventRepository, patternRepository, objectMapper, clock);
        skillRegistry.registerTool(tool);
        log.info("Registered WriteOptimizationEventTool into SkillRegistry");
        return tool;
    }


    /**
     * DISPATCHER-ORCHESTRATOR-REFACTOR (post-V93): replaces
     * {@code DispatchAttributionPatternsTool} (deleted) with a STEP-1 tool that
     * returns a candidates list rather than fan-out'ing per-pattern in Java.
     * The dispatcher agent's system prompt now drives per-candidate routing
     * decisions via {@code SubAgent(action=dispatch,
     * agentName=attribution-curator)} — moves dispatch policy from baked-in
     * Java into the configurable agent prompt.
     */
    @Bean
    public com.skillforge.server.tool.attribution.ListAttributionCandidatesTool listAttributionCandidatesTool(
            com.skillforge.server.attribution.AttributionDispatcherService dispatcherService,
            ObjectMapper objectMapper,
            SkillRegistry skillRegistry) {
        com.skillforge.server.tool.attribution.ListAttributionCandidatesTool tool =
                new com.skillforge.server.tool.attribution.ListAttributionCandidatesTool(
                        dispatcherService, objectMapper);
        skillRegistry.registerTool(tool);
        log.info("Registered ListAttributionCandidatesTool into SkillRegistry");
        return tool;
    }
}
