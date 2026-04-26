package com.skillforge.server.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.confirm.ToolApprovalRegistry;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.entity.AgentAuthoredHookEntity;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.service.AgentAuthoredHookService;
import com.skillforge.server.service.AgentMutationService;
import com.skillforge.server.service.AgentTargetResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UpdateAgentToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ToolApprovalRegistry approvalRegistry;
    private FakeTargetResolver targetResolver;
    private FakeMutationService mutationService;
    private UpdateAgentTool tool;

    @BeforeEach
    void setUp() {
        approvalRegistry = new ToolApprovalRegistry();
        targetResolver = new FakeTargetResolver();
        mutationService = new FakeMutationService();
        tool = new UpdateAgentTool(targetResolver, mutationService, objectMapper, approvalRegistry);
    }

    @Test
    void executeRequiresApprovalToken() {
        SkillResult result = tool.execute(Map.of(
                "targetAgentId", 42,
                "patch", Map.of("description", "Updated")
        ), context("s1", "tu-1", null));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("requires explicit user approval");
        assertThat(mutationService.calls).isZero();
    }

    @Test
    void executePatchesConfigAndCreatesPendingHookProposals() throws Exception {
        String token = approvalRegistry.issue("s1", "UpdateAgent", "tu-1", Duration.ofMinutes(1));
        AgentEntity target = new AgentEntity();
        target.setId(42L);
        target.setName("Session Analyzer");
        targetResolver.target = target;
        targetResolver.authorAgentId = 1L;

        AgentEntity saved = new AgentEntity();
        saved.setId(42L);
        saved.setName("Session Analyzer");
        saved.setDescription("Updated analyzer");
        saved.setModelId("deepseek-chat");
        saved.setPublic(false);
        saved.setStatus("active");
        saved.setExecutionMode("ask");

        AgentAuthoredHookEntity proposed = new AgentAuthoredHookEntity();
        proposed.setId(99L);
        proposed.setTargetAgentId(42L);
        proposed.setAuthorAgentId(1L);
        proposed.setEvent("Stop");
        proposed.setMethodKind(AgentAuthoredHookEntity.METHOD_KIND_COMPILED);
        proposed.setMethodId(5L);
        proposed.setMethodRef("agent.session.summary");
        proposed.setReviewState(AgentAuthoredHookEntity.STATE_PENDING);
        proposed.setEnabled(true);

        mutationService.result = new AgentMutationService.UpdateResult(saved, List.of(proposed));

        SkillResult result = tool.execute(Map.of(
                "targetAgentId", 42,
                "patch", Map.of(
                        "description", "Updated analyzer",
                        "modelId", "deepseek-chat",
                        "skills", List.of("GetTrace", "GetSessionMessages"),
                        "config", Map.of("temperature", 0.2),
                        "visibility", "private",
                        "executionMode", "ask"
                ),
                "hookChanges", Map.of(
                        "userLifecycleHooks", Map.of("version", 1, "hooks", Map.of()),
                        "agentAuthoredProposals", List.of(Map.of(
                                "event", "Stop",
                                "methodTarget", "compiled:5",
                                "displayName", "Summarize session",
                                "description", "Capture session quality signals"
                        ))
                )
        ), context("s1", "tu-1", token));

        assertThat(result.isSuccess()).isTrue();
        JsonNode json = objectMapper.readTree(result.getOutput());
        assertThat(json.path("id").asLong()).isEqualTo(42L);
        assertThat(json.path("proposedAgentAuthoredHooks").get(0).path("reviewState").asText())
                .isEqualTo("PENDING");
        assertThat(json.path("requiresSeparateHookReview").asBoolean()).isTrue();

        assertThat(mutationService.calls).isEqualTo(1);
        assertThat(mutationService.targetAgentId).isEqualTo(42L);
        AgentEntity patch = mutationService.patch;
        assertThat(patch.getDescription()).isEqualTo("Updated analyzer");
        assertThat(patch.getSkillIds()).contains("GetTrace", "GetSessionMessages");
        assertThat(patch.getConfig()).contains("temperature");
        assertThat(patch.getLifecycleHooks()).contains("\"version\":1");
        assertThat(patch.isPublic()).isFalse();

        AgentAuthoredHookService.ProposeRequest proposal = mutationService.proposals.get(0);
        assertThat(proposal.targetAgentId()).isEqualTo(42L);
        assertThat(proposal.authorAgentId()).isEqualTo(1L);
        assertThat(proposal.authorSessionId()).isEqualTo("s1");
        assertThat(proposal.event()).isEqualTo("Stop");
        assertThat(proposal.methodTarget()).isEqualTo("compiled:5");
        assertThat(approvalRegistry.size()).isZero();
    }

    @Test
    void rejectsDirectAgentAuthoredHookInjection() {
        String token = approvalRegistry.issue("s1", "UpdateAgent", "tu-1", Duration.ofMinutes(1));

        SkillResult result = tool.execute(Map.of(
                "hookChanges", Map.of("agentAuthoredHooks", List.of(Map.of("reviewState", "APPROVED")))
        ), context("s1", "tu-1", token));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("agentAuthoredHooks is not supported here");
        assertThat(mutationService.calls).isZero();
    }

    private static SkillContext context(String sessionId, String toolUseId, String approvalToken) {
        SkillContext context = new SkillContext(null, sessionId, 7L);
        context.setToolUseId(toolUseId);
        context.setApprovalToken(approvalToken);
        return context;
    }

    private static final class FakeTargetResolver extends AgentTargetResolver {
        AgentEntity target;
        Long authorAgentId;

        FakeTargetResolver() {
            super(null, null);
        }

        @Override
        public AgentEntity resolveEditableTarget(String sessionId, Long userId, Object targetAgentId, Object targetAgentName) {
            return target;
        }

        @Override
        public Long authorAgentIdForSession(String sessionId) {
            return authorAgentId;
        }
    }

    private static final class FakeMutationService extends AgentMutationService {
        int calls;
        Long targetAgentId;
        AgentEntity patch;
        List<AgentAuthoredHookService.ProposeRequest> proposals = new ArrayList<>();
        UpdateResult result;

        FakeMutationService() {
            super(null, null);
        }

        @Override
        public UpdateResult updateAgent(Long targetAgentId,
                                        AgentEntity patch,
                                        List<AgentAuthoredHookService.ProposeRequest> hookProposals) {
            this.calls++;
            this.targetAgentId = targetAgentId;
            this.patch = patch;
            this.proposals = hookProposals;
            return result;
        }
    }
}
