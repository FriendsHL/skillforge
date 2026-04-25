package com.skillforge.server.hook;

import com.skillforge.core.engine.hook.FailurePolicy;
import com.skillforge.core.engine.hook.HookEntry;
import com.skillforge.core.engine.hook.HookEvent;
import com.skillforge.core.engine.hook.HookHandler;
import com.skillforge.core.engine.hook.LifecycleHooksConfig;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.server.entity.AgentAuthoredHookEntity;
import com.skillforge.server.service.AgentAuthoredHookService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LifecycleHookCompositionServiceTest {

    @Test
    void dispatchableHooks_mergesSystemUserAgentInOrder() {
        SystemHookRegistry registry = new SystemHookRegistry(List.of((event, agentDef) ->
                List.of(new SystemHookDescriptor("system.test", event, entry("system"), "test"))));
        FakeAgentHookService agentHookService = new FakeAgentHookService(entry("agent"));
        LifecycleHookCompositionService service = new LifecycleHookCompositionService(registry, agentHookService);

        AgentDefinition def = new AgentDefinition();
        def.setId("42");
        LifecycleHooksConfig cfg = new LifecycleHooksConfig();
        cfg.putEntries(HookEvent.USER_PROMPT_SUBMIT, List.of(entry("user")));
        def.setLifecycleHooks(cfg);

        List<EffectiveHook> hooks = service.dispatchableHooks(def, HookEvent.USER_PROMPT_SUBMIT);

        assertThat(hooks).extracting(EffectiveHook::source)
                .containsExactly(HookSource.SYSTEM, HookSource.USER, HookSource.AGENT);
        assertThat(hooks).extracting(EffectiveHook::sourceId)
                .containsExactly("system.test", "user:UserPromptSubmit:0", "agent:7");
    }

    @Test
    void dispatchableHooks_doesNotRequireUserLifecycleConfig() {
        SystemHookRegistry registry = new SystemHookRegistry(List.of());
        FakeAgentHookService agentHookService = new FakeAgentHookService(entry("agent"));
        LifecycleHookCompositionService service = new LifecycleHookCompositionService(registry, agentHookService);

        AgentDefinition def = new AgentDefinition();
        def.setId("42");
        def.setLifecycleHooks(null);

        List<EffectiveHook> hooks = service.dispatchableHooks(def, HookEvent.USER_PROMPT_SUBMIT);

        assertThat(hooks).hasSize(1);
        assertThat(hooks.get(0).source()).isEqualTo(HookSource.AGENT);
    }

    private static HookEntry entry(String skillName) {
        HookEntry entry = new HookEntry();
        entry.setHandler(new HookHandler.SkillHandler(skillName));
        entry.setFailurePolicy(FailurePolicy.CONTINUE);
        entry.setTimeoutSeconds(5);
        return entry;
    }

    private static final class FakeAgentHookService extends AgentAuthoredHookService {
        private final HookEntry entry;

        private FakeAgentHookService(HookEntry entry) {
            super(null, null);
            this.entry = entry;
        }

        @Override
        public List<AgentAuthoredHookEntity> findDispatchable(Long targetAgentId, HookEvent event) {
            AgentAuthoredHookEntity row = new AgentAuthoredHookEntity();
            row.setId(7L);
            row.setAuthorAgentId(99L);
            row.setReviewState(AgentAuthoredHookEntity.STATE_APPROVED);
            return List.of(row);
        }

        @Override
        public HookEntry toHookEntry(AgentAuthoredHookEntity entity) {
            return entry;
        }
    }
}
