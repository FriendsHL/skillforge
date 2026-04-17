package com.skillforge.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Config for the {@code ScriptHandlerRunner}. Bound from {@code lifecycle.hooks.script} in
 * {@code application.yml}. Defaults are chosen so out-of-the-box dev usage works (bash + node
 * allowed, 64KB stdout cap) while production deployments can restrict {@code allowedLangs}
 * to an empty list to disable script hooks entirely.
 */
@ConfigurationProperties("lifecycle.hooks.script")
public class LifecycleHooksScriptProperties {

    /** Interpreter names accepted by the runner. Empty list = script hooks disabled. */
    private List<String> allowedLangs = List.of("bash", "node");

    /** Stdout/stderr capture cap in bytes. Anything past this is read and discarded. */
    private int maxOutputBytes = 65_536;

    /** Hard cap on {@code scriptBody} char length. Paired with AgentService save-time validation. */
    private int maxScriptBodyChars = 4_096;

    public List<String> getAllowedLangs() {
        return allowedLangs;
    }

    public void setAllowedLangs(List<String> allowedLangs) {
        this.allowedLangs = allowedLangs != null ? List.copyOf(allowedLangs) : List.of();
    }

    public int getMaxOutputBytes() {
        return maxOutputBytes;
    }

    public void setMaxOutputBytes(int maxOutputBytes) {
        this.maxOutputBytes = maxOutputBytes;
    }

    public int getMaxScriptBodyChars() {
        return maxScriptBodyChars;
    }

    public void setMaxScriptBodyChars(int maxScriptBodyChars) {
        this.maxScriptBodyChars = maxScriptBodyChars;
    }
}
