package com.skillforge.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** P4 ToolCatalog, ToolSearch, and deferred-schema rollout switches. */
@ConfigurationProperties(prefix = "skillforge.context.capability")
public class ContextCapabilityProperties {

    private boolean catalogEnabled = true;
    private boolean toolSearchEnabled = true;
    private boolean deferredSchemasEnabled = false;

    public boolean isCatalogEnabled() {
        return catalogEnabled;
    }

    public void setCatalogEnabled(boolean catalogEnabled) {
        this.catalogEnabled = catalogEnabled;
    }

    public boolean isToolSearchEnabled() {
        return toolSearchEnabled;
    }

    public void setToolSearchEnabled(boolean toolSearchEnabled) {
        this.toolSearchEnabled = toolSearchEnabled;
    }

    public boolean isDeferredSchemasEnabled() {
        return deferredSchemasEnabled;
    }

    public void setDeferredSchemasEnabled(boolean deferredSchemasEnabled) {
        this.deferredSchemasEnabled = deferredSchemasEnabled;
    }
}
