package com.skillforge.core.engine;

import com.skillforge.core.context.PromptObservationHashes;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.model.Message;
import com.skillforge.core.model.SkillDefinition;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.core.skill.view.SessionSkillView;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AgentLoopEngineSkillRecoveryTest {

    @Test
    void compactSummaryReattachesCurrentAuthorizedSkillBodyByMatchingHash() {
        AgentLoopEngine engine = engine();
        SkillDefinition skill = skill("research", "Authoritative current instructions");
        LoopContext context = context(skill);
        context.recordSkillInvocation(
                "research",
                PromptObservationHashes.sha256(skill.getPromptContent()),
                skill.getPromptContent());

        String appendix = engine.renderSkillRecoveryAppendix(
                context,
                java.util.List.of(Message.user(
                        "[Context summary from 20 messages compacted at now]\nsummary")));

        assertThat(appendix)
                .contains("source=\"skill-registry\"")
                .contains("### Skill: research")
                .contains("Authoritative current instructions");
    }

    @Test
    void changedOrUnauthorizedSkillIsNotReattached() {
        AgentLoopEngine engine = engine();
        SkillDefinition changed = skill("research", "new body");
        LoopContext context = context(changed);
        context.recordSkillInvocation("research", "old-hash", "old body");

        assertThat(engine.renderSkillRecoveryAppendix(
                context,
                java.util.List.of(Message.user(
                        "[Context summary from 20 messages compacted at now]\nsummary"))))
                .isEmpty();
    }

    @Test
    void ordinaryHistoryDoesNotDuplicatePreviouslyInvokedSkill() {
        AgentLoopEngine engine = engine();
        SkillDefinition skill = skill("research", "body");
        LoopContext context = context(skill);
        context.recordSkillInvocation(
                "research",
                PromptObservationHashes.sha256(skill.getPromptContent()),
                skill.getPromptContent());

        assertThat(engine.renderSkillRecoveryAppendix(
                context, java.util.List.of(Message.user("ordinary turn"))))
                .isEmpty();
    }

    private static AgentLoopEngine engine() {
        return new AgentLoopEngine(
                new LlmProviderFactory(),
                "unused",
                new SkillRegistry(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
    }

    private static LoopContext context(SkillDefinition skill) {
        LoopContext context = new LoopContext();
        context.setSessionId("session-1");
        context.setSkillView(new SessionSkillView(
                Map.of(skill.getName(), skill),
                Set.of(),
                Set.of(skill.getName())));
        return context;
    }

    private static SkillDefinition skill(String name, String body) {
        SkillDefinition skill = new SkillDefinition();
        skill.setName(name);
        skill.setPromptContent(body);
        return skill;
    }
}
