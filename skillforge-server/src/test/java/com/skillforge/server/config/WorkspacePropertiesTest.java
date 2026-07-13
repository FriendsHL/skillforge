package com.skillforge.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspacePropertiesTest {

    @Test
    void defaults_blankRootKeepsWorkspaceUnavailable() {
        WorkspaceProperties properties = new WorkspaceProperties();

        assertThat(properties.getRoot()).isBlank();
        assertThat(properties.getMaxPreviewBytes()).isEqualTo(262_144);
        assertThat(properties.getMaxEntriesPerDirectory()).isEqualTo(500);
    }

    @Test
    void binder_kebabCaseValues_populateProperties() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "skillforge.workspace.root", "/tmp/workspace",
                "skillforge.workspace.max-preview-bytes", "8192",
                "skillforge.workspace.max-entries-per-directory", "25")));

        WorkspaceProperties properties = binder
                .bind("skillforge.workspace", Bindable.of(WorkspaceProperties.class))
                .orElseThrow(() -> new AssertionError("workspace properties were not bound"));

        assertThat(properties.getRoot()).isEqualTo("/tmp/workspace");
        assertThat(properties.getMaxPreviewBytes()).isEqualTo(8192);
        assertThat(properties.getMaxEntriesPerDirectory()).isEqualTo(25);
    }
}
