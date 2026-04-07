package com.skillforge.server.config;

import com.skillforge.core.engine.AgentLoopEngine;
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
import com.skillforge.skills.BrowserSkill;
import com.skillforge.skills.GrepSkill;
import com.skillforge.skills.SubAgentSkill;
import com.skillforge.core.engine.SubAgentExecutor;
import com.skillforge.server.skill.MemorySkill;
import com.skillforge.server.service.MemoryService;
import com.skillforge.server.service.AgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Configuration
@EnableConfigurationProperties({LlmProperties.class, BrowserProperties.class})
public class SkillForgeConfig {

    private static final Logger log = LoggerFactory.getLogger(SkillForgeConfig.class);

    @Bean(destroyMethod = "shutdown")
    public BrowserSkill browserSkill(BrowserProperties browserProperties) {
        return new BrowserSkill(
                browserProperties.getProfileDir(),
                browserProperties.getDefaultTimeoutMs(),
                browserProperties.getLoginTimeoutSeconds());
    }

    @Bean
    public SkillRegistry skillRegistry(MemoryService memoryService, BrowserSkill browserSkill) {
        SkillRegistry registry = new SkillRegistry();
        registry.register(new BashSkill());
        registry.register(new FileReadSkill());
        registry.register(new FileWriteSkill());
        registry.register(new FileEditSkill());
        registry.register(new GlobSkill());
        registry.register(new GrepSkill());
        registry.register(browserSkill);
        registry.register(new MemorySkill(memoryService));
        return registry;
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
            factory.getProvider(modelConfig);
            log.info("Registered LLM provider: name={}, type={}, baseUrl={}",
                    name, providerConfig.getType(), providerConfig.getBaseUrl());
        }

        return factory;
    }

    @Bean
    public SubAgentExecutor subAgentExecutor(LlmProviderFactory llmProviderFactory, LlmProperties llmProperties,
                                             SkillRegistry skillRegistry) {
        String defaultProvider = llmProperties.getDefaultProvider() != null
                ? llmProperties.getDefaultProvider() : "claude";
        return new SubAgentExecutor(llmProviderFactory, defaultProvider, skillRegistry);
    }

    @Bean
    public SubAgentSkill subAgentSkill(SubAgentExecutor subAgentExecutor, AgentService agentService,
                                       SkillRegistry skillRegistry) {
        SubAgentSkill skill = new SubAgentSkill(subAgentExecutor, agentId -> {
            try {
                return agentService.toAgentDefinition(agentService.getAgent(agentId));
            } catch (Exception e) {
                return null;
            }
        });
        skillRegistry.register(skill);
        return skill;
    }

    @Bean
    public AgentLoopEngine agentLoopEngine(LlmProviderFactory llmProviderFactory, LlmProperties llmProperties,
                                           SkillRegistry skillRegistry) {
        String defaultProvider = llmProperties.getDefaultProvider() != null
                ? llmProperties.getDefaultProvider() : "claude";
        return new AgentLoopEngine(llmProviderFactory, defaultProvider, skillRegistry,
                Collections.emptyList(), List.of(new SafetySkillHook()), Collections.emptyList());
    }
}
