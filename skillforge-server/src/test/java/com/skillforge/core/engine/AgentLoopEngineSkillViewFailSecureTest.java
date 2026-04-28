package com.skillforge.core.engine;

import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.core.skill.view.SessionSkillResolver;
import com.skillforge.core.skill.view.SessionSkillView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan r2 W-BE-3 — verify {@link AgentLoopEngine#ensureSkillViewResolved} is fail-secure:
 * resolver throwing → view becomes {@link SessionSkillView#EMPTY}, NOT null (which would
 * fall back to the registry-wide list and bypass agent.disabled_system_skills authorisation).
 */
class AgentLoopEngineSkillViewFailSecureTest {

    private AgentLoopEngine newEngine(SessionSkillResolver resolver) {
        AgentLoopEngine engine = new AgentLoopEngine(
                new LlmProviderFactory(),
                "unused",
                new SkillRegistry(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
        engine.setSessionSkillResolver(resolver);
        return engine;
    }

    @Test
    @DisplayName("resolver throws → view set to EMPTY (fail-secure, not null)")
    void resolverThrows_setsEmptyView() {
        SessionSkillResolver throwingResolver = agentDef -> {
            throw new RuntimeException("DB down");
        };
        AgentLoopEngine engine = newEngine(throwingResolver);

        LoopContext ctx = new LoopContext();
        AgentDefinition agentDef = new AgentDefinition();

        engine.ensureSkillViewResolved(ctx, agentDef);

        assertThat(ctx.getSkillView())
                .as("fail-secure: resolver exception must produce EMPTY view, not leave null")
                .isSameAs(SessionSkillView.EMPTY);
    }

    @Test
    @DisplayName("resolver returns null → view set to EMPTY (defensive)")
    void resolverReturnsNull_setsEmptyView() {
        SessionSkillResolver nullResolver = agentDef -> null;
        AgentLoopEngine engine = newEngine(nullResolver);

        LoopContext ctx = new LoopContext();
        engine.ensureSkillViewResolved(ctx, new AgentDefinition());

        assertThat(ctx.getSkillView()).isSameAs(SessionSkillView.EMPTY);
    }

    @Test
    @DisplayName("resolver returns view → view stored as-is")
    void resolverReturnsView_storedAsIs() {
        SessionSkillView custom = SessionSkillView.EMPTY;
        SessionSkillResolver resolver = agentDef -> custom;
        AgentLoopEngine engine = newEngine(resolver);

        LoopContext ctx = new LoopContext();
        engine.ensureSkillViewResolved(ctx, new AgentDefinition());

        assertThat(ctx.getSkillView()).isSameAs(custom);
    }

    @Test
    @DisplayName("ensureSkillViewResolved is idempotent: pre-set view is not overwritten")
    void preSetView_isNotOverwritten() {
        SessionSkillResolver throwingResolver = agentDef -> {
            throw new RuntimeException("would-be-EMPTY");
        };
        AgentLoopEngine engine = newEngine(throwingResolver);

        LoopContext ctx = new LoopContext();
        ctx.setSkillView(SessionSkillView.EMPTY);

        engine.ensureSkillViewResolved(ctx, new AgentDefinition());

        // resolver should not have been called; view unchanged.
        assertThat(ctx.getSkillView()).isSameAs(SessionSkillView.EMPTY);
    }
}
