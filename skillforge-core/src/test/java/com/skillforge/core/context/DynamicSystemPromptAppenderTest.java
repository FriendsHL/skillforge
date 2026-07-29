package com.skillforge.core.context;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicSystemPromptAppenderTest {

    @Test
    void appendsRuntimeContextAsASeparateDynamicFragment() {
        StringBuilder target = new StringBuilder("existing");

        String fragment = DynamicSystemPromptAppender.appendRuntimeContext(
                target,
                "Artifact Workspace",
                "Current path: /tmp/run-1");

        assertThat(fragment)
                .startsWith("\n\n## Artifact Workspace")
                .contains("Current path: /tmp/run-1");
        assertThat(target.toString()).isEqualTo("existing" + fragment);
    }

    @Test
    void blankRuntimeContextDoesNotChangeTarget() {
        StringBuilder target = new StringBuilder("existing");

        assertThat(DynamicSystemPromptAppender.appendRuntimeContext(
                target, "Artifact Workspace", " ")).isEmpty();
        assertThat(target.toString()).isEqualTo("existing");
    }
}
