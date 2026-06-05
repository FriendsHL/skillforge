package com.skillforge.server.tool.evolve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.entity.PromptVersionEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.BehaviorRuleVersionRepository;
import com.skillforge.server.repository.PromptVersionRepository;
import com.skillforge.server.repository.SkillDraftRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AUTOEVOLVE-CLOSE-LOOP P1 — {@link GetCandidateDiffTool} unit tests: the LCS line
 * diff and the prompt-surface before/after resolution (explicit base vs the agent's
 * active prompt).
 */
class GetCandidateDiffToolTest {

    private final ObjectMapper om = new ObjectMapper();
    private PromptVersionRepository promptRepo;
    private GetCandidateDiffTool tool;

    @BeforeEach
    void setUp() {
        promptRepo = mock(PromptVersionRepository.class);
        tool = new GetCandidateDiffTool(
                promptRepo,
                mock(BehaviorRuleVersionRepository.class),
                mock(SkillDraftRepository.class),
                mock(AgentRepository.class),
                om);
    }

    @Test
    @DisplayName("unifiedDiff: context kept, removed/added lines marked; equal → (no change)")
    void unifiedDiff() {
        assertThat(GetCandidateDiffTool.unifiedDiff("same", "same")).isEqualTo("(no change)");
        String d = GetCandidateDiffTool.unifiedDiff("a\nb\nc", "a\nx\nc");
        assertThat(d).contains("  a");
        assertThat(d).contains("- b");
        assertThat(d).contains("+ x");
        assertThat(d).contains("  c");
    }

    @Test
    @DisplayName("prompt + baseVersionId: before=base content, after=candidate content")
    void promptWithExplicitBase() throws Exception {
        // Build the mocks BEFORE stubbing the repo (nested mock-building inside a
        // when().thenReturn() argument trips Mockito's UnfinishedStubbingException).
        PromptVersionEntity candE = promptVersion("a", "new content");
        PromptVersionEntity baseE = promptVersion("a", "old content");
        when(promptRepo.findById("cand")).thenReturn(Optional.of(candE));
        when(promptRepo.findById("base")).thenReturn(Optional.of(baseE));

        SkillResult r = tool.execute(
                Map.of("candidateId", "cand", "surface", "prompt", "baseVersionId", "base"), null);

        assertThat(r.isSuccess()).isTrue();
        var node = om.readTree(r.getOutput());
        assertThat(node.path("before").asText()).isEqualTo("old content");
        assertThat(node.path("after").asText()).isEqualTo("new content");
        assertThat(node.path("surface").asText()).isEqualTo("prompt");
        assertThat(node.has("diff")).isTrue();
    }

    @Test
    @DisplayName("prompt iter-1 (no base): before resolves to the agent's active prompt")
    void promptIter1UsesActivePrompt() throws Exception {
        PromptVersionEntity cand = promptVersion("9", "candidate prompt");
        PromptVersionEntity active = promptVersion("9", "active prompt");
        when(promptRepo.findById("cand")).thenReturn(Optional.of(cand));
        when(promptRepo.findByAgentIdAndStatus("9", "active")).thenReturn(List.of(active));

        SkillResult r = tool.execute(Map.of("candidateId", "cand", "surface", "prompt"), null);

        assertThat(r.isSuccess()).isTrue();
        var node = om.readTree(r.getOutput());
        assertThat(node.path("before").asText()).isEqualTo("active prompt");
        assertThat(node.path("after").asText()).isEqualTo("candidate prompt");
    }

    @Test
    @DisplayName("missing candidate → validation error")
    void missingCandidate() {
        when(promptRepo.findById("nope")).thenReturn(Optional.empty());
        SkillResult r = tool.execute(Map.of("candidateId", "nope", "surface", "prompt"), null);
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getError()).contains("prompt version not found");
    }

    private static PromptVersionEntity promptVersion(String agentId, String content) {
        PromptVersionEntity e = mock(PromptVersionEntity.class);
        when(e.getAgentId()).thenReturn(agentId);
        when(e.getContent()).thenReturn(content);
        return e;
    }
}
