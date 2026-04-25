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
import com.skillforge.core.llm.EmbeddingProvider;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.ModelConfig;
import com.skillforge.core.llm.OpenAiEmbeddingProvider;
import com.skillforge.core.skill.SkillPackageLoader;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.skills.BashSkill;
import com.skillforge.skills.CodeReviewSkill;
import com.skillforge.skills.CodeSandboxSkill;
import com.skillforge.skills.FileEditSkill;
import com.skillforge.skills.FileReadSkill;
import com.skillforge.skills.FileWriteSkill;
import com.skillforge.skills.GlobSkill;
import com.skillforge.skills.GrepSkill;
import com.skillforge.skills.WebFetchSkill;
import com.skillforge.skills.WebSearchSkill;
import com.skillforge.server.code.CompiledMethodService;
import com.skillforge.server.code.RegisterCompiledMethodSkill;
import com.skillforge.server.code.RegisterScriptMethodSkill;
import com.skillforge.server.code.ScriptMethodService;
import com.skillforge.server.skill.TodoStore;
import com.skillforge.server.skill.TodoWriteSkill;
import com.skillforge.server.clawhub.ClawHubProperties;
import com.skillforge.server.skill.MemoryDetailSkill;
import com.skillforge.server.skill.MemorySearchSkill;
import com.skillforge.server.skill.MemorySkill;
import com.skillforge.server.skill.SubAgentSkill;
import com.skillforge.server.skill.TeamCreateSkill;
import com.skillforge.server.skill.TeamKillSkill;
import com.skillforge.server.skill.TeamListSkill;
import com.skillforge.server.skill.TeamSendSkill;
import com.skillforge.server.service.AgentService;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.service.EmbeddingService;
import com.skillforge.server.service.MemoryService;
import com.skillforge.server.service.SessionService;
import com.skillforge.server.service.UserConfigService;
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
        ClawHubProperties.class,
        LifecycleHooksScriptProperties.class,
        SessionMessageStoreProperties.class,
        MemoryProperties.class
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
        registry.registerTool(new BashSkill());
        registry.registerTool(new FileReadSkill());
        registry.registerTool(new FileWriteSkill());
        registry.registerTool(new FileEditSkill());
        registry.registerTool(new GlobSkill());
        registry.registerTool(new GrepSkill());
        registry.registerTool(new MemorySkill(memoryService));
        registry.registerTool(new MemorySearchSkill(memoryService, embeddingService));
        registry.registerTool(new MemoryDetailSkill(memoryService));
        registry.registerTool(new WebFetchSkill());
        registry.registerTool(new WebSearchSkill());
        return registry;
    }

    /**
     * CodeSandboxSkill — lets Code Agent test-run bash/node/java snippets in an isolated sandbox
     * before registering them as hook methods.
     */
    @Bean
    public CodeSandboxSkill codeSandboxSkill(SkillRegistry skillRegistry) {
        CodeSandboxSkill skill = new CodeSandboxSkill();
        skillRegistry.registerTool(skill);
        log.info("Registered CodeSandboxSkill into SkillRegistry");
        return skill;
    }

    /**
     * CodeReviewSkill — delegates code review to an LLM provider.
     */
    @Bean
    public CodeReviewSkill codeReviewSkill(LlmProviderFactory llmProviderFactory,
                                           LlmProperties llmProperties,
                                           SkillRegistry skillRegistry) {
        String providerName = llmProperties.getDefaultProvider() != null
                ? llmProperties.getDefaultProvider() : "claude";
        CodeReviewSkill skill = new CodeReviewSkill(llmProviderFactory, providerName);
        skillRegistry.registerTool(skill);
        log.info("Registered CodeReviewSkill into SkillRegistry (provider={})", providerName);
        return skill;
    }

    /**
     * RegisterScriptMethodSkill — lets Code Agent persist a bash/node script as an
     * {@code agent.*} hook method and register it at runtime.
     */
    @Bean
    public RegisterScriptMethodSkill registerScriptMethodSkill(ScriptMethodService scriptMethodService,
                                                               ObjectMapper objectMapper,
                                                               SkillRegistry skillRegistry) {
        RegisterScriptMethodSkill skill = new RegisterScriptMethodSkill(scriptMethodService, objectMapper);
        skillRegistry.registerTool(skill);
        log.info("Registered RegisterScriptMethodSkill into SkillRegistry");
        return skill;
    }

    /**
     * RegisterCompiledMethodSkill — lets Code Agent submit a Java class that implements
     * {@code BuiltInMethod}; source is compiled in-process and stored for human approval before
     * being loaded into the runtime registry.
     */
    @Bean
    public RegisterCompiledMethodSkill registerCompiledMethodSkill(CompiledMethodService compiledMethodService,
                                                                   ObjectMapper objectMapper,
                                                                   SkillRegistry skillRegistry) {
        RegisterCompiledMethodSkill skill = new RegisterCompiledMethodSkill(compiledMethodService, objectMapper);
        skillRegistry.registerTool(skill);
        log.info("Registered RegisterCompiledMethodSkill into SkillRegistry");
        return skill;
    }

    @Bean
    public TodoWriteSkill todoWriteSkill(TodoStore todoStore, SkillRegistry skillRegistry) {
        TodoWriteSkill skill = new TodoWriteSkill(todoStore);
        skillRegistry.registerTool(skill);
        log.info("Registered TodoWriteSkill into SkillRegistry");
        return skill;
    }

    // ClawHubSkill 已迁移为 system-skills/clawhub/ 文件化 Skill，
    // 由 SystemSkillLoader 在启动时自动加载，不再需要 Java bean 注册。

    /**
     * SubAgentSkill — 异步派发任务给另一个 agentId 指向的子 Agent。
     * ChatService 用 @Lazy 打破 ChatService ↔ SubAgentRegistry ↔ SubAgentSkill 依赖环。
     */
    @Bean
    public SubAgentSkill subAgentSkill(AgentService agentService,
                                       SessionService sessionService,
                                       @Lazy ChatService chatService,
                                       SubAgentRegistry subAgentRegistry,
                                       SkillRegistry skillRegistry) {
        SubAgentSkill skill = new SubAgentSkill(agentService, sessionService, chatService, subAgentRegistry);
        skillRegistry.registerTool(skill);
        log.info("Registered SubAgentSkill into SkillRegistry");
        return skill;
    }

    /**
     * TeamCreateSkill — spawn a team member in a multi-agent collaboration run.
     */
    @Bean
    public TeamCreateSkill teamCreateSkill(SessionService sessionService,
                                           CollabRunService collabRunService,
                                           SkillRegistry skillRegistry) {
        TeamCreateSkill skill = new TeamCreateSkill(sessionService, collabRunService);
        skillRegistry.registerTool(skill);
        log.info("Registered TeamCreateSkill into SkillRegistry");
        return skill;
    }

    /**
     * TeamListSkill — list team members in the current collaboration run.
     */
    @Bean
    public TeamListSkill teamListSkill(SessionService sessionService,
                                       AgentRoster agentRoster,
                                       SkillRegistry skillRegistry) {
        TeamListSkill skill = new TeamListSkill(sessionService, agentRoster);
        skillRegistry.registerTool(skill);
        log.info("Registered TeamListSkill into SkillRegistry");
        return skill;
    }

    /**
     * TeamKillSkill — cancel a running team member or the entire collab run.
     */
    @Bean
    public TeamKillSkill teamKillSkill(SessionService sessionService,
                                       AgentRoster agentRoster,
                                       CollabRunService collabRunService,
                                       CancellationRegistry cancellationRegistry,
                                       SkillRegistry skillRegistry) {
        TeamKillSkill skill = new TeamKillSkill(sessionService, agentRoster, collabRunService, cancellationRegistry);
        skillRegistry.registerTool(skill);
        log.info("Registered TeamKillSkill into SkillRegistry");
        return skill;
    }

    /**
     * TeamSendSkill — send peer messages between team members in a collaboration run.
     */
    @Bean
    public TeamSendSkill teamSendSkill(SessionService sessionService,
                                       AgentRoster agentRoster,
                                       SubAgentRegistry subAgentRegistry,
                                       ChatEventBroadcaster broadcaster,
                                       com.skillforge.core.engine.TraceCollector traceCollector,
                                       SkillRegistry skillRegistry) {
        TeamSendSkill skill = new TeamSendSkill(sessionService, agentRoster, subAgentRegistry, broadcaster, traceCollector);
        skillRegistry.registerTool(skill);
        log.info("Registered TeamSendSkill into SkillRegistry");
        return skill;
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
                factory.getProvider(modelConfig);
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
                                           com.skillforge.server.context.EnvironmentContextProvider environmentContextProvider,
                                           com.skillforge.server.hook.ActivityLogHook activityLogHook,
                                           LifecycleHookLoopAdapter lifecycleHookLoopAdapter,
                                           LifecycleHookSkillAdapter lifecycleHookSkillAdapter,
                                           com.skillforge.server.service.MemoryService memoryService,
                                           UserConfigService userConfigService,
                                           com.skillforge.core.engine.confirm.SessionConfirmCache sessionConfirmCache,
                                           com.skillforge.core.engine.confirm.RootSessionLookup rootSessionLookup,
                                           com.skillforge.core.engine.confirm.ConfirmationPrompter confirmationPrompter) {
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
        engine.setMemoryProvider(userId -> memoryService.getMemoriesForPrompt(userId));
        engine.setClaudeMdProvider(userId -> userConfigService.getClaudeMd(userId));
        engine.setConfirmationPrompter(confirmationPrompter);
        engine.setSessionConfirmCache(sessionConfirmCache);
        engine.setRootSessionLookup(rootSessionLookup);
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
