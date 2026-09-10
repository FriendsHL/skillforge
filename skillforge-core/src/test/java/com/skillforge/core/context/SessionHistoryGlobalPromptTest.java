package com.skillforge.core.context;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionHistoryGlobalPromptTest {

    @Test
    void explainsFactRecoveryWithoutMechanicalHistoryReplay() {
        String prompt = new GlobalSystemPromptProvider().get();

        assertThat(prompt).contains(
                "SessionHistorySearch",
                "SessionHistoryRead",
                "summary 与未覆盖尾部",
                "精确事实",
                "原始证据",
                "TaskList",
                "FileRead",
                "unknown");
        assertThat(prompt).contains("工具列表提供");
        assertThat(prompt).doesNotContain("每轮都调用 SessionHistorySearch");
    }
}
