package com.skillforge.core.context;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicSystemPromptAppenderTest {

    @Test
    void appendsSessionAndMemoryWithLegacyByteShape() {
        StringBuilder dynamic = new StringBuilder("## Context\n\n- current_date: 2026-07-27");

        String sessionFragment =
                DynamicSystemPromptAppender.appendSessionContext(dynamic, 7L, "s1\ninjected");
        String memoryFragment =
                DynamicSystemPromptAppender.appendUserMemories(dynamic, "remember this");

        assertThat(sessionFragment).isEqualTo("""


                ## Session Context
                - userId: 7
                - sessionId: s1 injected
                """);
        assertThat(memoryFragment).isEqualTo("""


                ## User Memories

                remember this""");
        assertThat(dynamic.toString()).isEqualTo(
                "## Context\n\n- current_date: 2026-07-27"
                        + sessionFragment + memoryFragment);
    }

    @Test
    void skipsEmptyFragments() {
        StringBuilder dynamic = new StringBuilder();

        assertThat(DynamicSystemPromptAppender.appendSessionContext(
                dynamic, null, null)).isEmpty();
        assertThat(DynamicSystemPromptAppender.appendUserMemories(
                dynamic, " \n ")).isEmpty();
        assertThat(dynamic).isEmpty();
    }
}
