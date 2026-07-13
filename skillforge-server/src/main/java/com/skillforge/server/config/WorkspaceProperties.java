package com.skillforge.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "skillforge.workspace")
public class WorkspaceProperties {

    private String root = "";
    private int maxPreviewBytes = 262_144;
    private int maxEntriesPerDirectory = 500;

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        this.root = root;
    }

    public int getMaxPreviewBytes() {
        return maxPreviewBytes;
    }

    public void setMaxPreviewBytes(int maxPreviewBytes) {
        this.maxPreviewBytes = maxPreviewBytes;
    }

    public int getMaxEntriesPerDirectory() {
        return maxEntriesPerDirectory;
    }

    public void setMaxEntriesPerDirectory(int maxEntriesPerDirectory) {
        this.maxEntriesPerDirectory = maxEntriesPerDirectory;
    }
}
