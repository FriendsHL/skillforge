package com.skillforge.core.engine.hook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Polymorphic hook handler — the thing a hook entry actually executes.
 *
 * <p>The {@code type} discriminator determines which subtype is used:
 * <ul>
 *   <li>{@code "skill"} — {@link SkillHandler} — run a registered Skill</li>
 *   <li>{@code "script"} — {@link ScriptHandler} — run an inline script (P1)</li>
 *   <li>{@code "method"} — {@link MethodHandler} — invoke a platform-builtin method (P2)</li>
 * </ul>
 *
 * <p>Unknown {@code type} values fail deserialization at the Jackson level — callers
 * should catch the JSON parse error and treat the whole hooks config as empty (see
 * {@code AgentService.toAgentDefinition} for the defensive pattern).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", include = JsonTypeInfo.As.PROPERTY)
@JsonSubTypes({
        @JsonSubTypes.Type(value = HookHandler.SkillHandler.class, name = "skill"),
        @JsonSubTypes.Type(value = HookHandler.ScriptHandler.class, name = "script"),
        @JsonSubTypes.Type(value = HookHandler.MethodHandler.class, name = "method")
})
@JsonIgnoreProperties(ignoreUnknown = true)
public abstract class HookHandler {

    /** Static args declared at configure time. Merged with runtime input at execution. */
    protected Map<String, Object> args = new HashMap<>();

    public Map<String, Object> getArgs() {
        if (args == null) return Collections.emptyMap();
        return Collections.unmodifiableMap(args);
    }

    public void setArgs(Map<String, Object> args) {
        this.args = args != null ? args : new HashMap<>();
    }

    /** Run a registered Skill as the hook handler. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SkillHandler extends HookHandler {
        private String skillName;

        public SkillHandler() {}

        public SkillHandler(String skillName) {
            this.skillName = skillName;
        }

        public String getSkillName() {
            return skillName;
        }

        public void setSkillName(String skillName) {
            this.skillName = skillName;
        }
    }

    /**
     * Inline script handler — P1 implementation only. In P0, parsing succeeds but
     * dispatching returns "runner not implemented" and respects the entry's failurePolicy.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ScriptHandler extends HookHandler {
        /** Currently planned: {@code bash | node | python}, allow-listed via application.yml. */
        private String scriptLang;
        private String scriptBody;

        public ScriptHandler() {}

        public String getScriptLang() {
            return scriptLang;
        }

        public void setScriptLang(String scriptLang) {
            this.scriptLang = scriptLang;
        }

        public String getScriptBody() {
            return scriptBody;
        }

        public void setScriptBody(String scriptBody) {
            this.scriptBody = scriptBody;
        }
    }

    /**
     * Platform-builtin method handler — P2 implementation only. {@code methodRef} is looked
     * up in a whitelist registry. In P0 the runner is not wired; dispatching returns
     * "runner not implemented".
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MethodHandler extends HookHandler {
        /** Registered key (e.g. {@code "builtin.feishu.notify"}). Never a reflective target. */
        private String methodRef;

        public MethodHandler() {}

        public String getMethodRef() {
            return methodRef;
        }

        public void setMethodRef(String methodRef) {
            this.methodRef = methodRef;
        }
    }
}
