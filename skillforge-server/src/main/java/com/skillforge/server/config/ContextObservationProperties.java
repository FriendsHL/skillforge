package com.skillforge.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Rollback switch for read-only prompt and capability observation.
 */
@ConfigurationProperties(prefix = "skillforge.context.observation")
public class ContextObservationProperties {

    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
