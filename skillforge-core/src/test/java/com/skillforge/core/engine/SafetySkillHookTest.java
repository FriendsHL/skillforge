package com.skillforge.core.engine;

import com.skillforge.core.engine.confirm.RootSessionLookup;
import com.skillforge.core.engine.confirm.SessionConfirmCache;
import com.skillforge.core.skill.SkillContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SafetySkillHookTest {

    private final SkillContext ctx = new SkillContext("/tmp", "sid1", 1L);

    @Test
    @DisplayName("install pattern with no cache → fail-closed (returns null)")
    void installNoCacheFailClosed() {
        SessionConfirmCache cache = new SessionConfirmCache();
        RootSessionLookup lookup = sid -> sid; // identity
        SafetySkillHook hook = new SafetySkillHook(cache, lookup);
        Map<String, Object> input = Map.of("command", "clawhub install obsidian");
        assertThat(hook.beforeSkillExecute("Bash", input, ctx)).isNull();
    }

    @Test
    @DisplayName("install pattern with cache hit → returns input (allow)")
    void installCacheHitAllows() {
        SessionConfirmCache cache = new SessionConfirmCache();
        cache.approve("sid1", "clawhub", "obsidian");
        RootSessionLookup lookup = sid -> sid;
        SafetySkillHook hook = new SafetySkillHook(cache, lookup);
        Map<String, Object> input = Map.of("command", "clawhub install obsidian");
        assertThat(hook.beforeSkillExecute("Bash", input, ctx)).isEqualTo(input);
    }

    @Test
    @DisplayName("dangerous (non-install) still blocked via DANGEROUS_PATTERNS")
    void dangerousBlocked() {
        SafetySkillHook hook = new SafetySkillHook(new SessionConfirmCache(), sid -> sid);
        Map<String, Object> input = Map.of("command", "sudo rm -rf /");
        assertThat(hook.beforeSkillExecute("Bash", input, ctx)).isNull();
    }

    @Test
    @DisplayName("ordinary Bash passes through")
    void ordinaryBashPasses() {
        SafetySkillHook hook = new SafetySkillHook(new SessionConfirmCache(), sid -> sid);
        Map<String, Object> input = Map.of("command", "echo hello");
        assertThat(hook.beforeSkillExecute("Bash", input, ctx)).isEqualTo(input);
    }

    @Test
    @DisplayName("legacy no-arg constructor: install pattern still fail-closed (equivalent to old behavior)")
    void legacyNoArgFailClosed() {
        SafetySkillHook hook = new SafetySkillHook();
        Map<String, Object> input = Map.of("command", "clawhub install obsidian");
        assertThat(hook.beforeSkillExecute("Bash", input, ctx)).isNull();
    }
}
