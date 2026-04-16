package com.skillforge.server.eval;

import com.skillforge.core.engine.AgentLoopEngine;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.config.LlmProperties;
import org.springframework.stereotype.Component;

import java.util.Collections;

@Component
public class EvalEngineFactory {

    private final LlmProviderFactory llmProviderFactory;
    private final ChatEventBroadcaster broadcaster;
    private final String defaultProviderName;

    public EvalEngineFactory(LlmProviderFactory llmProviderFactory,
                             ChatEventBroadcaster broadcaster,
                             LlmProperties llmProperties) {
        this.llmProviderFactory = llmProviderFactory;
        this.broadcaster = broadcaster;
        this.defaultProviderName = llmProperties.getDefaultProvider() != null
                ? llmProperties.getDefaultProvider() : "claude";
    }

    public AgentLoopEngine buildEvalEngine(SkillRegistry sandboxRegistry) {
        AgentLoopEngine engine = new AgentLoopEngine(
                llmProviderFactory,
                defaultProviderName,
                sandboxRegistry,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList()
        );
        engine.setBroadcaster(broadcaster);
        // CRITICAL: Do NOT set compactorCallback (eval engine must not compact)
        // CRITICAL: Do NOT set pendingAskRegistry (ask_user auto-fails in eval)
        return engine;
    }
}
