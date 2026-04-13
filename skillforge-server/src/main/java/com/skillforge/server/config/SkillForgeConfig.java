package com.skillforge.server.config;

import com.skillforge.core.compact.ContextCompactorCallback;
import com.skillforge.core.compact.FullCompactStrategy;
import com.skillforge.core.compact.LightCompactStrategy;
import com.skillforge.core.engine.AgentLoopEngine;
import com.skillforge.core.engine.CancellationRegistry;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.engine.PendingAskRegistry;
import com.skillforge.core.engine.SafetySkillHook;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.ModelConfig;
import com.skillforge.core.skill.SkillPackageLoader;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.skills.BashSkill;
import com.skillforge.skills.FileEditSkill;
import com.skillforge.skills.FileReadSkill;
import com.skillforge.skills.FileWriteSkill;
import com.skillforge.skills.GlobSkill;
import com.skillforge.skills.GrepSkill;
import com.skillforge.server.clawhub.ClawHubProperties;
import com.skillforge.server.skill.MemorySkill;
import com.skillforge.server.skill.SubAgentSkill;
import com.skillforge.server.service.AgentService;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.service.MemoryService;
import com.skillforge.server.service.SessionService;
import com.skillforge.server.subagent.SubAgentRegistry;
import org.springframework.context.annotation.Lazy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableConfigurationProperties({LlmProperties.class, ClawHubProperties.class})
public class SkillForgeConfig {

    private static final Logger log = LoggerFactory.getLogger(SkillForgeConfig.class);

    @Bean
    public SkillRegistry skillRegistry(MemoryService memoryService) {
        SkillRegistry registry = new SkillRegistry();
        registry.register(new BashSkill());
        registry.register(new FileReadSkill());
        registry.register(new FileWriteSkill());
        registry.register(new FileEditSkill());
        registry.register(new GlobSkill());
        registry.register(new GrepSkill());
        registry.register(new MemorySkill(memoryService));
        return registry;
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
        skillRegistry.register(skill);
        log.info("Registered SubAgentSkill into SkillRegistry");
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
            factory.getProvider(modelConfig);
            log.info("Registered LLM provider: name={}, type={}, baseUrl={}, readTimeoutSec={}, maxRetries={}, contextWindowTokens={}",
                    name, providerConfig.getType(), providerConfig.getBaseUrl(),
                    modelConfig.getReadTimeoutSeconds(), modelConfig.getMaxRetries(),
                    modelConfig.getContextWindowTokens());
        }

        return factory;
    }

    @Bean
    public PendingAskRegistry pendingAskRegistry() {
        return new PendingAskRegistry();
    }

    @Bean
    public CancellationRegistry cancellationRegistry() {
        return new CancellationRegistry();
    }

    @Bean
    public LightCompactStrategy lightCompactStrategy() {
        return new LightCompactStrategy();
    }

    @Bean
    public FullCompactStrategy fullCompactStrategy() {
        return new FullCompactStrategy();
    }

    @Bean
    public AgentLoopEngine agentLoopEngine(LlmProviderFactory llmProviderFactory, LlmProperties llmProperties,
                                           SkillRegistry skillRegistry,
                                           ChatEventBroadcaster broadcaster,
                                           PendingAskRegistry pendingAskRegistry,
                                           @Lazy ContextCompactorCallback compactorCallback,
                                           com.skillforge.core.engine.TraceCollector traceCollector,
                                           com.skillforge.server.context.EnvironmentContextProvider environmentContextProvider) {
        String defaultProvider = llmProperties.getDefaultProvider() != null
                ? llmProperties.getDefaultProvider() : "claude";
        AgentLoopEngine engine = new AgentLoopEngine(llmProviderFactory, defaultProvider, skillRegistry,
                Collections.emptyList(), List.of(new SafetySkillHook()),
                List.of(environmentContextProvider));
        engine.setBroadcaster(broadcaster);
        engine.setPendingAskRegistry(pendingAskRegistry);
        engine.setCompactorCallback(compactorCallback);
        engine.setTraceCollector(traceCollector);
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
