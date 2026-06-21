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
public class InfraConfig {

    private static final Logger log = LoggerFactory.getLogger(InfraConfig.class);


    @Bean
    public BehaviorRuleRegistry behaviorRuleRegistry(ObjectMapper objectMapper) {
        return new BehaviorRuleRegistry(objectMapper);
    }

    /**
     * SKILLFORGE-SYSTEM-PROMPT: platform-wide global system prompt, built into the
     * code as a classpath resource. Wired into the engine's claudeMd slot (see
     * {@code EngineConfig}) so it becomes the first stable segment of every native
     * agent's system prompt, replacing the legacy per-user CLAUDE.md injection.
     * Fails fast at startup if the resource is missing/blank.
     */
    @Bean
    public com.skillforge.core.context.GlobalSystemPromptProvider globalSystemPromptProvider() {
        return new com.skillforge.core.context.GlobalSystemPromptProvider();
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


    // ─────────────────────────────────────────────────────────────────────────────
    // REMINDER-MVP Phase A: <system-reminder> framework + 3 sources.
    // PRD D7 source order in builder: ContextUsage → MemoryAge → FileActivity.
    // PRD D5 default cadence: ContextUsage every turn (≥70%); MemoryAge every 5 turns (>7d
    // stale); FileActivity every 5 turns (top-5 files older than 30s).
    // Each source is best-effort and degrades to "skip" on internal failure; the builder
    // wraps every source call in try/catch so reminders can never block the LLM call.
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * REMINDER-MVP: server-side adapter exposing {@link MemoryService} as a core-side
     * {@link com.skillforge.core.reminder.MemoryAgeStatsProvider} (W2: single combined call).
     * Avoids a core→server compile-time link; same pattern as {@code memoryProvider}
     * (BiFunction wired as a setter).
     */
    @Bean
    public com.skillforge.core.reminder.MemoryAgeStatsProvider memoryAgeStatsProvider(
            MemoryService memoryService) {
        return memoryService::getMemoryAgeStats;
    }


    @Bean
    public com.skillforge.core.reminder.MemoryAgeSource memoryAgeSource(
            com.skillforge.core.reminder.MemoryAgeStatsProvider memoryAgeStatsProvider,
            @Value("${skillforge.reminder.memory-age.enabled:true}") boolean enabled,
            @Value("${skillforge.reminder.memory-age.interval-turns:5}") int intervalTurns,
            @Value("${skillforge.reminder.memory-age.stale-days-threshold:7}") int staleDaysThreshold) {
        return new com.skillforge.core.reminder.MemoryAgeSource(
                memoryAgeStatsProvider, enabled, intervalTurns, staleDaysThreshold);
    }


    /**
     * REMINDER-MVP ContextUsageSource (W1+W3 fix): zero LLM/Spring deps. The actual
     * RequestTokenEstimator inputs (systemPrompt, tools, jsonMapper, thresholds) flow
     * through {@link com.skillforge.core.reminder.ReminderContext} per-iteration, so this
     * bean is just the YAML-driven config holder.
     */
    @Bean
    public com.skillforge.core.reminder.ContextUsageSource contextUsageSource(
            @Value("${skillforge.reminder.context-usage.enabled:true}") boolean enabled,
            @Value("${skillforge.reminder.context-usage.interval-turns:1}") int intervalTurns,
            @Value("${skillforge.reminder.context-usage.pct-threshold:70}") int pctThreshold) {
        return new com.skillforge.core.reminder.ContextUsageSource(
                enabled, intervalTurns, pctThreshold);
    }


    @Bean
    public TodoListSource todoListSource(
            TodoStore todoStore,
            ObjectMapper objectMapper,
            @Value("${skillforge.reminder.todo-list.enabled:true}") boolean enabled,
            @Value("${skillforge.reminder.todo-list.interval-turns:1}") int intervalTurns,
            @Value("${skillforge.reminder.todo-list.max-todos:20}") int maxTodos) {
        return new TodoListSource(todoStore, objectMapper, enabled, intervalTurns, maxTodos);
    }


    @Bean
    public com.skillforge.core.reminder.FileActivitySource fileActivitySource(
            FileStateCache fileStateCache,
            @Value("${skillforge.reminder.file-activity.enabled:true}") boolean enabled,
            @Value("${skillforge.reminder.file-activity.interval-turns:5}") int intervalTurns,
            @Value("${skillforge.reminder.file-activity.max-files:5}") int maxFiles,
            @Value("${skillforge.reminder.file-activity.min-age-seconds:30}") long minAgeSeconds) {
        return new com.skillforge.core.reminder.FileActivitySource(
                fileStateCache, enabled, intervalTurns, maxFiles, minAgeSeconds);
    }


    @Bean
    public com.skillforge.core.reminder.ReminderBuilder reminderBuilder(
            com.skillforge.core.reminder.ContextUsageSource contextUsageSource,
            TodoListSource todoListSource,
            com.skillforge.core.reminder.MemoryAgeSource memoryAgeSource,
            com.skillforge.core.reminder.FileActivitySource fileActivitySource,
            @Value("${skillforge.reminder.enabled:true}") boolean globalEnabled,
            @Value("${skillforge.reminder.total-budget-tokens:5000}") int totalBudgetTokens) {
        // Ordered: most-actionable first → ContextUsage → TodoList → MemoryAge → FileActivity.
        return new com.skillforge.core.reminder.ReminderBuilder(
                List.of(contextUsageSource, todoListSource, memoryAgeSource, fileActivitySource),
                totalBudgetTokens,
                globalEnabled);
    }


    @Bean
    public SkillRegistry skillRegistry(MemoryService memoryService, EmbeddingService embeddingService,
                                       FileStateCache fileStateCache,
                                       WebToolsProperties webToolsProperties) {
        SkillRegistry registry = new SkillRegistry();
        registry.registerTool(new BashTool());
        // P9-5: file tools share the FileStateCache so recovery payload knows recent files.
        registry.registerTool(new FileReadTool(fileStateCache));
        registry.registerTool(new FileWriteTool(fileStateCache));
        registry.registerTool(new FileEditTool(fileStateCache));
        registry.registerTool(new GlobTool());
        registry.registerTool(new GrepTool());
        registry.registerTool(new MemoryTool(memoryService));
        registry.registerTool(new MemorySearchTool(memoryService, embeddingService));
        registry.registerTool(new MemoryDetailTool(memoryService));
        registry.registerTool(new WebFetchTool(WebFetchConfig.fromHostAllowlist(
                webToolsProperties.getWebfetch().getRobots().getHostAllowlist())));
        registry.registerTool(new WebSearchTool(WebSearchConfig.fromBackendPriorityNames(
                webToolsProperties.getWebsearch().getBackendPriority(),
                webToolsProperties.getWebsearch().getTavily().getApiKey(),
                webToolsProperties.getWebsearch().getExa().getApiKey())));
        return registry;
    }


    /**
     * P11 MCP-CLIENT: process-wide registry of live {@link com.skillforge.tools.mcp.session.McpServerSession}s.
     * Owned by {@code McpServerLifecycle}; consumed by {@code McpToolRegistrar} +
     * controller status responses.
     */
    @Bean
    public com.skillforge.tools.mcp.session.McpServerSessionRegistry mcpServerSessionRegistry() {
        return new com.skillforge.tools.mcp.session.McpServerSessionRegistry();
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
            if (providerConfig.getChatPath() != null && !providerConfig.getChatPath().isBlank()) {
                modelConfig.setChatPath(providerConfig.getChatPath());
            }
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
                // An agent whose modelId carries this provider's "provider:" prefix will then
                // fail fast at call time with a clear error (see AgentLoopEngine.resolveProvider):
                // we do NOT silently fall back to the default provider, because that handed the
                // default provider a foreign prefixed model name and surfaced as a misleading
                // downstream 401. Configure the corresponding env var, or point the agent at a
                // configured provider, to fix it.
                log.warn("Skipped LLM provider '{}': {}", name, e.getMessage());
            }
        }

        return factory;
    }
}
