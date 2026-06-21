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
public class EngineConfig {

    private static final Logger log = LoggerFactory.getLogger(EngineConfig.class);


    @Bean
    public PendingAskRegistry pendingAskRegistry() {
        return new PendingAskRegistry();
    }


    @Bean
    public com.skillforge.core.engine.confirm.PendingConfirmationRegistry pendingConfirmationRegistry() {
        return new com.skillforge.core.engine.confirm.PendingConfirmationRegistry();
    }


    @Bean
    public com.skillforge.core.engine.confirm.SessionConfirmCache sessionConfirmCache() {
        return new com.skillforge.core.engine.confirm.SessionConfirmCache();
    }


    @Bean
    public com.skillforge.core.engine.confirm.ToolApprovalRegistry toolApprovalRegistry() {
        return new com.skillforge.core.engine.confirm.ToolApprovalRegistry();
    }


    @Bean
    public CancellationRegistry cancellationRegistry() {
        return new CancellationRegistry();
    }


    // ---------- V3 ATTRIBUTION-AGENT Phase 1.2 tools ----------

    /**
     * V3 ATTRIBUTION-AGENT shared clock. Injected into
     * {@link com.skillforge.server.attribution.AttributionDispatcherService} +
     * {@link com.skillforge.server.tool.attribution.ProposeOptimizationTool}
     * so tests can supply a fixed clock without touching system time. UTC by
     * default — cooldown arithmetic + audit timestamps don't care about zone,
     * and TIMESTAMPTZ stores everything as UTC at the DB layer anyway.
     */
    @Bean
    public java.time.Clock systemClock() {
        return java.time.Clock.systemUTC();
    }


    /**
     * Resolver that maps sessionId → active AgentDefinition. Used by
     * {@link LifecycleHookSkillAdapter} since the {@code SkillHook} contract does not pass
     * the owning {@code LoopContext}. Reads from {@link CancellationRegistry} which holds the
     * live {@code LoopContext} for every running loop.
     */
    @Bean
    public LifecycleHookSkillAdapter.AgentDefinitionResolver lifecycleHookAgentDefResolver(
            CancellationRegistry cancellationRegistry) {
        return sessionId -> {
            LoopContext ctx = cancellationRegistry.getContext(sessionId);
            return ctx != null ? ctx.getAgentDefinition() : null;
        };
    }


    @Bean
    public LifecycleHookLoopAdapter lifecycleHookLoopAdapter(LifecycleHookDispatcher dispatcher) {
        return new LifecycleHookLoopAdapter(dispatcher);
    }


    @Bean
    public LifecycleHookSkillAdapter lifecycleHookSkillAdapter(
            LifecycleHookDispatcher dispatcher,
            LifecycleHookSkillAdapter.AgentDefinitionResolver resolver) {
        return new LifecycleHookSkillAdapter(dispatcher, resolver);
    }


    @Bean
    public AgentLoopEngine agentLoopEngine(LlmProviderFactory llmProviderFactory, LlmProperties llmProperties,
                                           SkillRegistry skillRegistry,
                                           ChatEventBroadcaster broadcaster,
                                           PendingAskRegistry pendingAskRegistry,
                                           @Lazy ContextCompactorCallback compactorCallback,
                                           com.skillforge.core.engine.TraceCollector traceCollector,
                                           com.skillforge.core.engine.TraceLifecycleSink traceLifecycleSink,
                                           com.skillforge.server.context.EnvironmentContextProvider environmentContextProvider,
                                           com.skillforge.server.hook.ActivityLogHook activityLogHook,
                                           LifecycleHookLoopAdapter lifecycleHookLoopAdapter,
                                           LifecycleHookSkillAdapter lifecycleHookSkillAdapter,
                                           com.skillforge.server.service.MemoryService memoryService,
                                           UserConfigService userConfigService,
                                           com.skillforge.core.engine.confirm.SessionConfirmCache sessionConfirmCache,
                                           com.skillforge.core.engine.confirm.ToolApprovalRegistry toolApprovalRegistry,
                                           com.skillforge.core.engine.confirm.RootSessionLookup rootSessionLookup,
                                           com.skillforge.core.engine.confirm.ConfirmationPrompter confirmationPrompter,
                                           com.skillforge.core.skill.view.SessionSkillResolver sessionSkillResolver,
                                           com.skillforge.server.service.SkillService skillService,
                                           FileStateCache fileStateCache,
                                           com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        String defaultProvider = llmProperties.getDefaultProvider() != null
                ? llmProperties.getDefaultProvider() : "claude";
        AgentLoopEngine engine = new AgentLoopEngine(llmProviderFactory, defaultProvider, skillRegistry,
                List.of(lifecycleHookLoopAdapter),
                List.of(new SafetySkillHook(sessionConfirmCache, rootSessionLookup),
                        activityLogHook, lifecycleHookSkillAdapter),
                List.of(environmentContextProvider));
        engine.setBroadcaster(broadcaster);
        engine.setPendingAskRegistry(pendingAskRegistry);
        engine.setCompactorCallback(compactorCallback);
        engine.setTraceCollector(traceCollector);
        // OBS-2 M1: wire trace lifecycle sink (PgLlmTraceStore via adapter).
        engine.setTraceLifecycleSink(traceLifecycleSink);
        // CTX-1 — wire the project-wide Spring ObjectMapper (JavaTimeModule registered)
        // into RequestTokenEstimator path. Avoids footgun #1 (silent wrong serialisation).
        engine.setJsonMapper(objectMapper);
        // Memory v2 (PR-2): BiFunction provider — taskContext = current user message lets
        // L1 hybrid recall pick semantically relevant knowledge/project/reference memories.
        engine.setMemoryProvider((userId, taskContext) ->
                memoryService.getMemoriesForPromptInjection(userId, taskContext));
        engine.setClaudeMdProvider(userId -> userConfigService.getClaudeMd(userId));
        engine.setConfirmationPrompter(confirmationPrompter);
        engine.setSessionConfirmCache(sessionConfirmCache);
        engine.setToolApprovalRegistry(toolApprovalRegistry);
        engine.setRootSessionLookup(rootSessionLookup);
        // Plan r2 §5 + §7: wire skill control plane.
        engine.setSessionSkillResolver(sessionSkillResolver);
        engine.setSkillTelemetryRecorder(skillService::recordUsage);
        // P9-5: per-session file state cache for post-compact recovery payload.
        engine.setFileStateCache(fileStateCache);
        // Q2 (cache-friendly migration, 2026-05-10): reminderBuilder no longer wired into the
        // engine — ChatService injects the <system-reminder> as a ContentBlock on the user
        // Message at chatAsync entry, so it persists with the message and stays byte-identical
        // across turns (preserves Anthropic prompt-cache breakpoints 2 + 3).
        return engine;
    }


    /**
     * Chat loop 异步执行线程池。
     * core=8 / max=64 / queue=128,溢出触发 AbortPolicy → RejectedExecutionException,
     * Controller 层捕获后返回 429 Too Many Requests。
     */
    @Bean(name = "chatLoopExecutor", destroyMethod = "shutdown")
    public ThreadPoolExecutor chatLoopExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                8, 64,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(128),
                r -> {
                    Thread t = new Thread(r, "chat-loop-" + System.nanoTime());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(false);
        return executor;
    }


    /**
     * AUTOEVOLVING V1 — workflow JS-body execution pool. Runs the (single-threaded
     * per run) workflow script body {@code WorkflowRunnerService.runWorkflowBody}.
     * SEPARATE from {@code workflowSubAgentExecutor}: the workflow thread
     * barrier-joins on sub-agent futures submitted to that other pool, so they must
     * not share a pool (nested-pool deadlock — same rationale as eval's
     * evalOrchestratorExecutor vs evalLoopExecutor split). One thread per concurrent
     * workflow run; queue absorbs bursts and overflow aborts (caller sees 429).
     */
    @Bean(name = "workflowExecutor", destroyMethod = "shutdown")
    public ThreadPoolExecutor workflowExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                2, 16,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(64),
                r -> {
                    Thread t = new Thread(r, "workflow-run-" + System.nanoTime());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }


    /**
     * AUTOEVOLVING V1 — workflow sub-agent execution pool. Runs the blocking
     * {@code AgentLoopEngine.run(...)} for {@code agent()} primitives offloaded by
     * {@code HostParallel} (plan §2.1). SEPARATE from the workflow-JS pool to avoid
     * nested-pool deadlock (same rationale as eval's evalOrchestratorExecutor vs
     * evalLoopExecutor split): the single workflow thread barrier-joins on futures
     * submitted here, so the runners must never compete for the workflow thread's
     * own pool.
     *
     * <p>Concurrency cap: configurable via
     * {@code skillforge.workflow.subagent-concurrency} (default 4). The CPU is
     * NOT the binding constraint — the LLM provider's rate limit is: a workflow
     * like opt-report fans out {@code parallel(batches.map(...))}
     * session-batch-annotators, each making provider calls, so a CPU-sized cap
     * ({@code min(16, cores-2)}) easily tripped the provider's HTTP 429 ("Too
     * many requests"), which is fatal (LLM 429 is not retried). A modest default
     * keeps the fan-out under typical provider limits; raise it after upgrading
     * the provider tier, or lower it to 2 if a stricter tier still 429s. A value
     * {@code <= 0} falls back to the legacy CPU-sized cap.
     */
    @Bean(name = "workflowSubAgentExecutor", destroyMethod = "shutdown")
    public ThreadPoolExecutor workflowSubAgentExecutor(
            @Value("${skillforge.workflow.subagent-concurrency:4}") int configured) {
        int max;
        if (configured > 0) {
            max = configured;
        } else {
            int cores = Runtime.getRuntime().availableProcessors();
            max = Math.max(2, Math.min(16, cores - 2));
        }
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                max, max,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(256),
                r -> {
                    Thread t = new Thread(r, "wf-subagent-" + System.nanoTime());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        log.info("workflowSubAgentExecutor concurrency cap = {} (configured={}, "
                + "skillforge.workflow.subagent-concurrency)", max, configured);
        return executor;
    }
}
