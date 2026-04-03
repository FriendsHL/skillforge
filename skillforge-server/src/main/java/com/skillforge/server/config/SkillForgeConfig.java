package com.skillforge.server.config;

import com.skillforge.core.context.ContextProvider;
import com.skillforge.core.engine.AgentLoopEngine;
import com.skillforge.core.engine.LoopHook;
import com.skillforge.core.engine.SafetySkillHook;
import com.skillforge.core.engine.SkillHook;
import com.skillforge.core.llm.ClaudeProvider;
import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.OpenAiProvider;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.skills.BashSkill;
import com.skillforge.skills.FileEditSkill;
import com.skillforge.skills.FileReadSkill;
import com.skillforge.skills.FileWriteSkill;
import com.skillforge.skills.GlobSkill;
import com.skillforge.skills.GrepSkill;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.List;

@Configuration
public class SkillForgeConfig {

    @Bean
    public SkillRegistry skillRegistry() {
        SkillRegistry registry = new SkillRegistry();
        registry.register(new BashSkill());
        registry.register(new FileReadSkill());
        registry.register(new FileWriteSkill());
        registry.register(new FileEditSkill());
        registry.register(new GlobSkill());
        registry.register(new GrepSkill());
        return registry;
    }

    @Bean
    public LlmProvider llmProvider(
            @Value("${skillforge.llm.default-provider:claude}") String defaultProvider,
            @Value("${skillforge.llm.providers.claude.api-key:}") String claudeApiKey,
            @Value("${skillforge.llm.providers.claude.base-url:https://api.anthropic.com}") String claudeBaseUrl,
            @Value("${skillforge.llm.providers.claude.model:claude-sonnet-4-20250514}") String claudeModel,
            @Value("${skillforge.llm.providers.openai.api-key:}") String openaiApiKey,
            @Value("${skillforge.llm.providers.openai.base-url:https://api.openai.com}") String openaiBaseUrl,
            @Value("${skillforge.llm.providers.openai.model:gpt-4o}") String openaiModel) {
        return switch (defaultProvider) {
            case "openai" -> new OpenAiProvider(openaiApiKey, openaiBaseUrl, openaiModel);
            default -> new ClaudeProvider(claudeApiKey, claudeBaseUrl, claudeModel);
        };
    }

    @Bean
    public AgentLoopEngine agentLoopEngine(LlmProvider llmProvider, SkillRegistry skillRegistry) {
        return new AgentLoopEngine(llmProvider, skillRegistry,
                Collections.emptyList(), List.of(new SafetySkillHook()), Collections.emptyList());
    }
}
