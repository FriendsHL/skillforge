package com.skillforge.server.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.confirm.ToolApprovalRegistry;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.service.AgentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreateAgentToolTest {

    @Mock
    private AgentService agentService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ToolApprovalRegistry approvalRegistry;
    private CreateAgentTool tool;

    @BeforeEach
    void setUp() {
        approvalRegistry = new ToolApprovalRegistry();
        tool = new CreateAgentTool(agentService, objectMapper, approvalRegistry);
    }

    @Test
    void executeRequiresApprovalToken() {
        SkillResult result = tool.execute(Map.of("name", "No Approval"), context("s1", "tu-1", null));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("requires explicit user approval");
        verify(agentService, never()).createAgent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void executeCreatesAgentAfterApproval() throws Exception {
        String token = approvalRegistry.issue("s1", "CreateAgent", "tu-1", Duration.ofMinutes(1));
        AgentEntity saved = new AgentEntity();
        saved.setId(42L);
        saved.setName("Session Analyzer");
        saved.setDescription("Analyzes sessions");
        saved.setRole("analyzer");
        saved.setModelId("deepseek:deepseek-chat");
        saved.setPublic(false);
        saved.setStatus("active");
        saved.setOwnerId(7L);
        when(agentService.createAgent(org.mockito.ArgumentMatchers.any())).thenReturn(saved);

        SkillResult result = tool.execute(Map.of(
                "name", "Session Analyzer",
                "description", "Analyzes sessions",
                "role", "analyzer",
                "modelId", "deepseek:deepseek-chat",
                "systemPrompt", "Analyze tool choice quality.",
                "tools", List.of("GetTrace", "GetSessionMessages"),
                "visibility", "private",
                "executionMode", "ask",
                "maxLoops", 8
        ), context("s1", "tu-1", token));

        assertThat(result.isSuccess()).isTrue();
        JsonNode json = objectMapper.readTree(result.getOutput());
        assertThat(json.path("id").asLong()).isEqualTo(42L);
        assertThat(json.path("visibility").asText()).isEqualTo("private");

        ArgumentCaptor<AgentEntity> captor = ArgumentCaptor.forClass(AgentEntity.class);
        verify(agentService).createAgent(captor.capture());
        AgentEntity created = captor.getValue();
        assertThat(created.getName()).isEqualTo("Session Analyzer");
        assertThat(created.getOwnerId()).isEqualTo(7L);
        assertThat(created.isPublic()).isFalse();
        assertThat(created.getExecutionMode()).isEqualTo("ask");
        assertThat(created.getMaxLoops()).isEqualTo(8);
        assertThat(created.getToolIds()).contains("GetTrace", "GetSessionMessages");
        assertThat(approvalRegistry.size()).isZero();
    }

    @Test
    void invalidVisibilityIsValidationErrorAndDoesNotConsumeApproval() {
        String token = approvalRegistry.issue("s1", "CreateAgent", "tu-1", Duration.ofMinutes(1));

        SkillResult result = tool.execute(Map.of(
                "name", "Bad Visibility",
                "visibility", "internal"
        ), context("s1", "tu-1", token));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("visibility must be public or private");
        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        assertThat(approvalRegistry.size()).isEqualTo(1);
        verify(agentService, never()).createAgent(org.mockito.ArgumentMatchers.any());
    }

    private static SkillContext context(String sessionId, String toolUseId, String approvalToken) {
        SkillContext context = new SkillContext(null, sessionId, 7L);
        context.setToolUseId(toolUseId);
        context.setApprovalToken(approvalToken);
        return context;
    }
}
