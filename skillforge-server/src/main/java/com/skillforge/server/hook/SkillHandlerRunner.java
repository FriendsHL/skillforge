package com.skillforge.server.hook;

import com.skillforge.core.engine.hook.HandlerRunner;
import com.skillforge.core.engine.hook.HookExecutionContext;
import com.skillforge.core.engine.hook.HookHandler;
import com.skillforge.core.engine.hook.HookRunResult;
import com.skillforge.core.skill.Skill;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.core.skill.SkillResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * P0 {@link HandlerRunner} — looks up the named Skill in {@link SkillRegistry} and invokes it.
 *
 * <p>Static {@code handler.args} are merged with per-event runtime input. Runtime input keys
 * (e.g. {@code user_message}) override any matching args keys — declared defaults should not
 * shadow the live event payload.
 */
@Component
public class SkillHandlerRunner implements HandlerRunner<HookHandler.SkillHandler> {

    private static final Logger log = LoggerFactory.getLogger(SkillHandlerRunner.class);

    private final SkillRegistry skillRegistry;

    public SkillHandlerRunner(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    @Override
    public Class<HookHandler.SkillHandler> handlerType() {
        return HookHandler.SkillHandler.class;
    }

    @Override
    public HookRunResult run(HookHandler.SkillHandler handler,
                             Map<String, Object> input,
                             HookExecutionContext ctx) {
        long t0 = System.currentTimeMillis();
        String skillName = handler.getSkillName();
        if (skillName == null || skillName.isBlank()) {
            return HookRunResult.failure("skill_name_missing", System.currentTimeMillis() - t0);
        }
        Optional<Skill> skillOpt = skillRegistry.getSkill(skillName);
        if (skillOpt.isEmpty()) {
            log.warn("Lifecycle hook skill not found: '{}' (session={})", skillName, ctx.sessionId());
            return HookRunResult.failure("skill_not_found:" + skillName, System.currentTimeMillis() - t0);
        }
        Skill skill = skillOpt.get();

        // Merge static handler.args with runtime input; runtime wins on collisions.
        Map<String, Object> merged = new HashMap<>();
        // runtime args (input) take precedence over config defaults (handler.args)
        if (handler.getArgs() != null) merged.putAll(handler.getArgs());
        if (input != null) merged.putAll(input);

        SkillContext skillCtx = new SkillContext(null, ctx.sessionId(), ctx.userId());

        try {
            SkillResult result = skill.execute(merged, skillCtx);
            long dur = System.currentTimeMillis() - t0;
            if (result == null) {
                return HookRunResult.failure("skill_returned_null", dur);
            }
            return new HookRunResult(result.isSuccess(), result.getOutput(), result.getError(), dur);
        } catch (Exception e) {
            long dur = System.currentTimeMillis() - t0;
            log.warn("Lifecycle hook skill '{}' threw: {}", skillName, e.toString());
            return HookRunResult.failure("exception:" + e.getClass().getSimpleName() + ":" + e.getMessage(), dur);
        }
    }
}
