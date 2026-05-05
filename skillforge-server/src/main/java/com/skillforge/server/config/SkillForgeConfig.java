package com.skillforge.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.context.BehaviorRuleRegistry;
import com.skillforge.core.compact.CompactableToolRegistry;
import com.skillforge.core.compact.ContextCompactorCallback;
import com.skillforge.core.compact.FullCompactStrategy;
import com.skillforge.core.compact.LightCompactStrategy;
import com.skillforge.core.engine.AgentLoopEngine;
import com.skillforge.core.engine.CancellationRegistry;
import com.skillforge.core.engine.ChatEventBroadcaster;
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
import com.skillforge.server.code.CompiledMethodService;
import com.skillforge.server.code.RegisterCompiledMethodTool;
import com.skillforge.server.code.RegisterScriptMethodTool;
import com.skillforge.server.code.ScriptMethodService;
import com.skillforge.server.skill.ImportSkillTool;
import com.skillforge.server.skill.SkillImportProperties;
import com.skillforge.server.skill.SkillImportService;
import com.skillforge.server.skill.TodoStore;
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
import com.skillforge.server.service.SessionService;
import com.skillforge.server.service.UserConfigService;
import com.skillforge.server.repository.TraceSpanRepository;
import com.skillforge.server.subagent.AgentRoster;
import com.skillforge.server.subagent.CollabRunService;
import com.skillforge.server.subagent.SubAgentRegistry;
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
@EnableConfigurationProperties({
        LlmProperties.class,
        LifecycleHooksScriptProperties.class,
        SessionMessageStoreProperties.class,
        MemoryProperties.class,
        SkillImportProperties.class
})
public class SkillForgeConfig {

    private static final Logger log = LoggerFactory.getLogger(SkillForgeConfig.class);

    @Bean
    public BehaviorRuleRegistry behaviorRuleRegistry(ObjectMapper objectMapper) {
        return new BehaviorRuleRegistry(objectMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "skillforge.embedding.enabled", havingValue = "true")
    public EmbeddingProvider embeddingProvider(
            @Value("${skillforge.embedding.api-key}") String apiKey,
            @Value("${skillforge.embedding.base-url:https://api.openai.com}") String baseUrl,
            @Value("${skillforge.embedding.model:text-embedding-3-small}") String model,
            @Value("${skillforge.embedding.dimension:1536}") int dimension) {
        if (dimension != 1536) {
            log.warn("Configured embedding dimension {} does not match schema column vector(1536). "
                    + "Vector search will fail at runtime unless migration is updated.", dimension);
        }
        return new OpenAiEmbeddingProvider(apiKey, baseUrl, model, dimension);
    }

    // When embedding provider is not configured, inject a no-op that throws on use.
    // EmbeddingService catches EmbeddingNotSupportedException and degrades to FTS-only.
    @Bean
    @ConditionalOnMissingBean(EmbeddingProvider.class)
    public EmbeddingProvider noOpEmbeddingProvider() {
        return text -> { throw new com.skillforge.core.llm.EmbeddingNotSupportedException("no-op"); };
    }

    @Bean
    public SkillRegistry skillRegistry(MemoryService memoryService, EmbeddingService embeddingService) {
        SkillRegistry registry = new SkillRegistry();
        registry.registerTool(new BashTool());
        registry.registerTool(new FileReadTool());
        registry.registerTool(new FileWriteTool());
        registry.registerTool(new FileEditTool());
        registry.registerTool(new GlobTool());
        registry.registerTool(new GrepTool());
        registry.registerTool(new MemoryTool(memoryService));
        registry.registerTool(new MemorySearchTool(memoryService, embeddingService));
        registry.registerTool(new MemoryDetailTool(memoryService));
        registry.registerTool(new WebFetchTool());
        registry.registerTool(new WebSearchTool());
        return registry;
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
        // OBS-2 M4: read path moved from t_trace_span (legacy TraceSpanRepository) to
        // unified t_llm_trace + t_llm_span via LlmTraceStore. The legacy
        // TraceSpanRepository bean stays alive for other consumers (SessionSpansService /
        // R__migrate_legacy_trace_span ETL etc.) until M5/M6 cleanup migrations.
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
                                       SkillRegistry skillRegistry) {
        SubAgentTool tool = new SubAgentTool(targetResolver, sessionService, chatService,
                subAgentRegistry, cancellationRegistry);
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

    @Bean
    public SkillPackageLoader skillPackageLoader() {
        return new SkillPackageLoader();
    }

    @Bean
    public LlmProviderFactory llmProviderFactory(LlmProperties llmProperties) {
        LlmProviderFactory factory = new LlmProviderFactory();

        for (Map.Entry<String, LlmProperties.ProviderConfig> entry : llmProperties.getProviders().entrySet()) {
            String name = entry.getKey();
            LlmProperties.ProviderConfig providerConfig = entry.getValue();

            ModelConfig modelConfig = new ModelConfig(
                    name,
                    providerConfig.getType(),
                    providerConfig.getApiKey(),
                    providerConfig.getBaseUrl(),
                    providerConfig.getModel()
            );
            if (providerConfig.getReadTimeoutSeconds() != null) {
                modelConfig.setReadTimeoutSeconds(providerConfig.getReadTimeoutSeconds());
            }
            if (providerConfig.getMaxRetries() != null) {
                modelConfig.setMaxRetries(providerConfig.getMaxRetries());
            }
            if (providerConfig.getContextWindowTokens() != null) {
                modelConfig.setContextWindowTokens(providerConfig.getContextWindowTokens());
            }
            try {
                com.skillforge.core.llm.LlmProvider provider = factory.getProvider(modelConfig);
                // CTX-1 — apply per-provider compact thresholds if configured;
                // otherwise the provider keeps its default 0.60/0.80/0.85.
                if (providerConfig.getCompactThresholds() != null) {
                    com.skillforge.core.llm.CompactThresholds thresholds =
                            providerConfig.getCompactThresholds().toCore();
                    if (provider instanceof com.skillforge.core.llm.ClaudeProvider claude) {
                        claude.setCompactThresholds(thresholds);
                    } else if (provider instanceof com.skillforge.core.llm.OpenAiProvider openai) {
                        openai.setCompactThresholds(thresholds);
                    }
                    log.info("Applied compact thresholds to provider {}: {}", name, thresholds);
                }
                log.info("Registered LLM provider: name={}, type={}, baseUrl={}, readTimeoutSec={}, maxRetries={}, contextWindowTokens={}",
                        name, providerConfig.getType(), providerConfig.getBaseUrl(),
                        modelConfig.getReadTimeoutSeconds(), modelConfig.getMaxRetries(),
                        modelConfig.getContextWindowTokens());
            } catch (IllegalStateException e) {
                // API key missing for this provider — skip registration so the app still starts.
                // Agents that pick this provider's models will fall back to the default provider
                // (see AgentLoopEngine.resolveProvider). Configure the corresponding env var
                // to enable this provider.
                log.warn("Skipped LLM provider '{}': {}", name, e.getMessage());
            }
        }

        return factory;
    }

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

    @Bean
    public CompactableToolRegistry compactableToolRegistry() {
        return new CompactableToolRegistry();
    }

    @Bean
    public LightCompactStrategy lightCompactStrategy(CompactableToolRegistry compactableToolRegistry) {
        return new LightCompactStrategy(compactableToolRegistry);
    }

    @Bean
    public FullCompactStrategy fullCompactStrategy() {
        return new FullCompactStrategy();
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
}
