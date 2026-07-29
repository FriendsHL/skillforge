package com.skillforge.core.context;

import com.skillforge.core.model.AgentDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the built-in global system prompt (SKILLFORGE-SYSTEM-PROMPT) loads from the
 * classpath resource and is placed as the first stable segment by the system prompt builder.
 */
class GlobalSystemPromptProviderTest {

    @Test
    @DisplayName("loads a concise platform prompt without project-specific implementation details")
    void loads_nonBlank_withFeatureLine() {
        String prompt = new GlobalSystemPromptProvider().get();

        assertThat(prompt).isNotBlank();
        assertThat(prompt).contains("SkillForge AI Agent 平台");
        assertThat(prompt).contains("工具调用");
        assertThat(prompt).contains("Grep", "Read", "Edit", "memory_search");
        assertThat(prompt).contains("之前", "上次");
        assertThat(prompt).contains(
                "### 任务进度管理（TodoWrite）",
                "三个或以上",
                "不要为简单问答",
                "最多只能有一个 in_progress",
                "完整任务列表");
        assertThat(prompt).contains(
                "### Web 搜索与页面读取",
                "WebSearch",
                "WebFetch",
                "### Session 历史与执行诊断",
                "GetSessionMessages",
                "GetTrace",
                "### Agent 委派与团队协作",
                "AgentDiscovery",
                "SubAgent",
                "TeamCreate",
                "### Agent 配置与 Hook",
                "GetAgentConfig",
                "GetAgentHooks",
                "### 定时任务",
                "CreateScheduledTask",
                "### 文件、应用与多媒体发布",
                "PublishChatArtifact",
                "PublishInteractiveArtifact",
                "GenerateImage",
                "EditImage",
                "GenerateVideo");
        assertThat(prompt).doesNotContain("AnySearch");
        assertThat(prompt).doesNotContain("skillforge-core");
        assertThat(prompt).doesNotContain("内嵌 PostgreSQL");
        assertThat(prompt.length()).isLessThan(4_000);
    }

    @Test
    @DisplayName("SystemPromptBuilder places the global prompt as the very first segment")
    void systemPromptBuilder_placesGlobalPromptFirst() {
        String globalPrompt = new GlobalSystemPromptProvider().get();

        AgentDefinition agent = new AgentDefinition();
        agent.setSystemPrompt("You are the test agent.");

        String built = new SystemPromptBuilder(agent, List.of(), List.of()).build(globalPrompt);

        assertThat(built).startsWith(globalPrompt.strip());
        assertThat(built.indexOf("SkillForge AI Agent 平台"))
                .as("global prompt appears before the agent's own prompt")
                .isLessThan(built.indexOf("You are the test agent."));
    }

    @Test
    @DisplayName("fails fast when the resource is missing on the classpath")
    void failsFast_whenResourceMissing() {
        assertThatThrownBy(() ->
                new GlobalSystemPromptProvider("prompts/does-not-exist.md"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("fails fast when the resource is blank")
    void failsFast_whenResourceBlank() {
        assertThatThrownBy(() ->
                new GlobalSystemPromptProvider("prompts/global-system-prompt-blank-fixture.md"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blank");
    }
}
