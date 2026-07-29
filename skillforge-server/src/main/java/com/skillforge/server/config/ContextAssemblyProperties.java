package com.skillforge.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** P1 rollout and rollback switch for typed context assembly boundaries. */
@ConfigurationProperties(prefix = "skillforge.context.assembly")
public class ContextAssemblyProperties {

    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
