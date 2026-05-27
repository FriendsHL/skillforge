package com.skillforge.server.tool.memorysynth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.entity.MemoryEntity;
import com.skillforge.server.entity.MemoryProposalEntity;
import com.skillforge.server.repository.MemoryProposalRepository;
import com.skillforge.server.repository.MemoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CreateMemoryProposalTool")
class CreateMemoryProposalToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private MemoryProposalRepository proposalRepository;

    @Mock
    private MemoryRepository memoryRepository;

    private CreateMemoryProposalTool tool;

    @BeforeEach
    void setUp() {
        tool = new CreateMemoryProposalTool(proposalRepository, memoryRepository, objectMapper);
        // Cross-run dedup native query is best-effort; default mock returns empty so it's a no-op.
        lenient().when(proposalRepository.findReferencingMemoryId(anyLong(), anyString()))
                .thenReturn(List.of());
        // Default: saveAll returns the input (with synthetic IDs) — overridden per-test.
        lenient().when(proposalRepository.saveAll(anyList())).thenAnswer(inv -> {
            List<MemoryProposalEntity> in = inv.getArgument(0);
            AtomicLong seq = new AtomicLong(100L);
            List<MemoryProposalEntity> out = new ArrayList<>();
            for (MemoryProposalEntity e : in) {
                e.setId(seq.getAndIncrement());
                out.add(e);
            }
            return out;
        });
    }

    private static MemoryEntity memOf(long id, long userId) {
        MemoryEntity m = new MemoryEntity();
        m.setId(id);
        m.setUserId(userId);
        m.setType("knowledge");
        m.setTitle("t-" + id);
        m.setContent("c-" + id);
        m.setStatus("ACTIVE");
        m.setImportance("medium");
        return m;
    }

    private void stubMemoryFindAllById(long userId, long... ids) {
        List<MemoryEntity> rows = new ArrayList<>();
        for (long id : ids) rows.add(memOf(id, userId));
        when(memoryRepository.findAllById(anyIterable())).thenReturn(rows);
    }

    @Test
    @DisplayName("dedup + reflection + optimize + contradiction all persist successfully")
    void execute_fourTypes_allPersist() throws Exception {
        // Stub each call to findAllById to return whatever IDs were asked for, mapping to userId=42.
        when(memoryRepository.findAllById(anyIterable())).thenAnswer(inv -> {
            Iterable<Long> ids = inv.getArgument(0);
            List<MemoryEntity> out = new ArrayList<>();
            for (Long id : ids) out.add(memOf(id, 42L));
            return out;
        });

        Map<String, Object> dedup = Map.of(
                "type", "dedup",
                "sourceMemoryIds", List.of(1L, 2L, 3L),
                "winnerMemoryId", 1L,
                "reasoning", "all three say the same thing");
        Map<String, Object> reflection = Map.of(
                "type", "reflection",
                "sourceMemoryIds", List.of(10L, 11L),
                "suggestedTitle", "Pattern observation",
                "suggestedContent", "User consistently prefers SQL over ORM for analytics.",
                "suggestedImportance", "high",
                "reasoning", "cross-memory trend");
        Map<String, Object> optimize = Map.of(
                "type", "optimize",
                "sourceMemoryIds", List.of(20L),
                "suggestedContent", "shorter version",
                "reasoning", "trimmed redundancy");
        Map<String, Object> contradiction = Map.of(
                "type", "contradiction",
                "sourceMemoryIds", List.of(30L, 31L),
                "reasoning", "facts disagree");

        SkillResult result = tool.execute(Map.of(
                "synthesisRunId", "synth-abc",
                "proposals", List.of(dedup, reflection, optimize, contradiction)
        ), new SkillContext(null, "s1", 42L));

        assertThat(result.isSuccess()).isTrue();
        JsonNode root = objectMapper.readTree(result.getOutput());
        assertThat(root.path("createdCount").asInt()).isEqualTo(4);
        assertThat(root.path("skippedDuplicates").asInt()).isEqualTo(0);
        assertThat(root.path("rejectedByValidation").asInt()).isEqualTo(0);

        ArgumentCaptor<List<MemoryProposalEntity>> cap =
                ArgumentCaptor.forClass(List.class);
        verify(proposalRepository).saveAll(cap.capture());
        List<MemoryProposalEntity> saved = cap.getValue();
        assertThat(saved).hasSize(4);
        assertThat(saved.stream().map(MemoryProposalEntity::getProposalType).toList())
                .containsExactly("dedup", "reflection", "optimize", "contradiction");
        // userId is resolved from memory rows.
        assertThat(saved.stream().map(MemoryProposalEntity::getUserId).distinct().toList())
                .containsExactly(42L);
        assertThat(saved.stream().map(MemoryProposalEntity::getSynthesisRunId).distinct().toList())
                .containsExactly("synth-abc");
        // dedup winner is preserved.
        MemoryProposalEntity dedupEntity = saved.get(0);
        assertThat(dedupEntity.getWinnerMemoryId()).isEqualTo(1L);
        // reflection writes suggestedTitle/content/importance.
        MemoryProposalEntity reflEntity = saved.get(1);
        assertThat(reflEntity.getSuggestedTitle()).isEqualTo("Pattern observation");
        assertThat(reflEntity.getSuggestedImportance()).isEqualTo("high");
    }

    @Test
    @DisplayName("dedup with sourceMemoryIds.size > 5 is rejected (mass-delete guard)")
    void execute_dedupOversizeSources_rejected() throws Exception {
        // Type-specific size check fires before existence check, so memoryRepository.findAllById
        // is never invoked — leave the mock un-stubbed.

        SkillResult result = tool.execute(Map.of(
                "synthesisRunId", "run-x",
                "proposals", List.of(Map.of(
                        "type", "dedup",
                        "sourceMemoryIds", List.of(1L, 2L, 3L, 4L, 5L, 6L),
                        "winnerMemoryId", 1L,
                        "reasoning", "too many"
                ))
        ), new SkillContext(null, "s1", 42L));

        assertThat(result.isSuccess()).isTrue();
        JsonNode root = objectMapper.readTree(result.getOutput());
        assertThat(root.path("createdCount").asInt()).isEqualTo(0);
        assertThat(root.path("rejectedByValidation").asInt()).isEqualTo(1);
        assertThat(root.path("rejections").get(0).path("error").asText())
                .contains("mass-delete guard");
        verify(proposalRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("illegal type (delete, unknown) is rejected without DB write")
    void execute_illegalType_rejected() throws Exception {
        // Even with memory rows present, illegal type must short-circuit before existence check.
        SkillResult result = tool.execute(Map.of(
                "synthesisRunId", "run-y",
                "proposals", List.of(
                        Map.of("type", "delete", "sourceMemoryIds", List.of(1L, 2L), "reasoning", "no"),
                        Map.of("type", "wat", "sourceMemoryIds", List.of(3L, 4L), "reasoning", "huh"))
        ), new SkillContext(null, "s1", 42L));

        assertThat(result.isSuccess()).isTrue();
        JsonNode root = objectMapper.readTree(result.getOutput());
        assertThat(root.path("createdCount").asInt()).isEqualTo(0);
        assertThat(root.path("rejectedByValidation").asInt()).isEqualTo(2);
        verify(proposalRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("sourceMemoryIds referencing missing rows are rejected")
    void execute_missingMemoryRow_rejected() throws Exception {
        // Caller asks for 1, 2, 3 but the repo only knows about 1 and 2.
        when(memoryRepository.findAllById(anyIterable())).thenReturn(List.of(
                memOf(1L, 42L), memOf(2L, 42L)));

        SkillResult result = tool.execute(Map.of(
                "synthesisRunId", "run-z",
                "proposals", List.of(Map.of(
                        "type", "dedup",
                        "sourceMemoryIds", List.of(1L, 2L, 3L),
                        "winnerMemoryId", 1L,
                        "reasoning", "ok"
                ))
        ), new SkillContext(null, "s1", 42L));

        JsonNode root = objectMapper.readTree(result.getOutput());
        assertThat(root.path("createdCount").asInt()).isEqualTo(0);
        assertThat(root.path("rejectedByValidation").asInt()).isEqualTo(1);
        assertThat(root.path("rejections").get(0).path("error").asText()).contains("missing memory rows");
    }

    @Test
    @DisplayName("reasoning longer than 200 chars is truncated (UTF-16 surrogate-safe)")
    void execute_reasoningTruncated_to200() throws Exception {
        stubMemoryFindAllById(42L, 1L, 2L);

        String longReasoning = "x".repeat(500);
        SkillResult result = tool.execute(Map.of(
                "synthesisRunId", "run-trunc",
                "proposals", List.of(Map.of(
                        "type", "contradiction",
                        "sourceMemoryIds", List.of(1L, 2L),
                        "reasoning", longReasoning
                ))
        ), new SkillContext(null, "s1", 42L));

        assertThat(result.isSuccess()).isTrue();
        ArgumentCaptor<List<MemoryProposalEntity>> cap =
                ArgumentCaptor.forClass(List.class);
        verify(proposalRepository).saveAll(cap.capture());
        assertThat(cap.getValue().get(0).getReasoning()).hasSize(200);
    }

    @Test
    @DisplayName("cross-run duplicate (same type + same sourceIds set in proposed state) is skipped")
    void execute_crossRunDuplicate_skipped() throws Exception {
        stubMemoryFindAllById(42L, 1L, 2L);

        // Existing proposal already covers the same {1,2} set in proposed state.
        MemoryProposalEntity existing = new MemoryProposalEntity();
        existing.setId(900L);
        existing.setUserId(42L);
        existing.setProposalType("contradiction");
        existing.setSourceMemoryIds("[2,1]"); // order-insensitive equality
        existing.setStatus(MemoryProposalEntity.STATUS_PROPOSED);
        when(proposalRepository.findReferencingMemoryId(anyLong(), anyString()))
                .thenReturn(List.of(existing));

        SkillResult result = tool.execute(Map.of(
                "synthesisRunId", "run-skip",
                "proposals", List.of(Map.of(
                        "type", "contradiction",
                        "sourceMemoryIds", List.of(1L, 2L),
                        "reasoning", "facts disagree"
                ))
        ), new SkillContext(null, "s1", 42L));

        JsonNode root = objectMapper.readTree(result.getOutput());
        assertThat(root.path("createdCount").asInt()).isEqualTo(0);
        assertThat(root.path("skippedDuplicates").asInt()).isEqualTo(1);
        assertThat(root.path("rejectedByValidation").asInt()).isEqualTo(0);
        verify(proposalRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("missing synthesisRunId is a validation error before any DB work")
    void execute_missingRunId_validationError() {
        SkillResult result = tool.execute(Map.of(
                "proposals", List.of(Map.of(
                        "type", "dedup",
                        "sourceMemoryIds", List.of(1L, 2L),
                        "winnerMemoryId", 1L))
        ), new SkillContext(null, "s1", 42L));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        assertThat(result.getError()).contains("synthesisRunId");
        org.mockito.Mockito.verifyNoInteractions(memoryRepository, proposalRepository);
    }

    @Test
    @DisplayName("dedup winner not in sourceMemoryIds is rejected")
    void execute_dedupWinnerNotInSources_rejected() throws Exception {
        stubMemoryFindAllById(42L, 1L, 2L);

        SkillResult result = tool.execute(Map.of(
                "synthesisRunId", "run-w",
                "proposals", List.of(Map.of(
                        "type", "dedup",
                        "sourceMemoryIds", List.of(1L, 2L),
                        "winnerMemoryId", 99L, // not in sources
                        "reasoning", "ok"
                ))
        ), new SkillContext(null, "s1", 42L));

        JsonNode root = objectMapper.readTree(result.getOutput());
        assertThat(root.path("rejectedByValidation").asInt()).isEqualTo(1);
        assertThat(root.path("rejections").get(0).path("error").asText())
                .contains("not in sourceMemoryIds");
        verify(proposalRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("sourceMemoryIds spanning multiple users is rejected (user isolation)")
    void execute_crossUserSources_rejected() throws Exception {
        // Memory 1 belongs to user 42, memory 2 belongs to user 99 — cross-user leak attempt.
        when(memoryRepository.findAllById(anyIterable())).thenReturn(List.of(
                memOf(1L, 42L), memOf(2L, 99L)));

        SkillResult result = tool.execute(Map.of(
                "synthesisRunId", "run-cross",
                "proposals", List.of(Map.of(
                        "type", "dedup",
                        "sourceMemoryIds", List.of(1L, 2L),
                        "winnerMemoryId", 1L
                ))
        ), new SkillContext(null, "s1", 42L));

        JsonNode root = objectMapper.readTree(result.getOutput());
        assertThat(root.path("rejectedByValidation").asInt()).isEqualTo(1);
        assertThat(root.path("rejections").get(0).path("error").asText())
                .contains("multiple users");
        verify(proposalRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("optimize requires exactly 1 sourceMemoryId — 2 is rejected")
    void execute_optimizeMultipleSources_rejected() throws Exception {
        // Type-specific size check fires before existence check; no stub needed.

        SkillResult result = tool.execute(Map.of(
                "synthesisRunId", "run-opt",
                "proposals", List.of(Map.of(
                        "type", "optimize",
                        "sourceMemoryIds", List.of(1L, 2L),
                        "suggestedContent", "x"
                ))
        ), new SkillContext(null, "s1", 42L));

        JsonNode root = objectMapper.readTree(result.getOutput());
        assertThat(root.path("rejectedByValidation").asInt()).isEqualTo(1);
        assertThat(root.path("rejections").get(0).path("error").asText())
                .contains("optimize requires exactly 1");
    }

    @Test
    @DisplayName("Gap-2: SYSTEM context (userId=0) bypasses cross-user gate (dogfood fan-out)")
    void execute_systemContext_acrossUsers_allowed() throws Exception {
        // sub-session creator is SYSTEM (userId=0), processing memories belonging to userId=1.
        // The cross-user gate must NOT fire here — this is the legitimate dogfood pattern.
        when(memoryRepository.findAllById(anyIterable())).thenReturn(List.of(
                memOf(1L, 1L), memOf(2L, 1L)));

        SkillResult result = tool.execute(Map.of(
                "synthesisRunId", "run-sys",
                "proposals", List.of(Map.of(
                        "type", "dedup",
                        "sourceMemoryIds", List.of(1L, 2L),
                        "winnerMemoryId", 1L,
                        "reasoning", "same fact"
                ))
        ), new SkillContext(null, "sub-session", 0L));

        JsonNode root = objectMapper.readTree(result.getOutput());
        assertThat(root.path("createdCount").asInt()).isEqualTo(1);
        assertThat(root.path("rejectedByValidation").asInt()).isZero();
        verify(proposalRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("reflection can be backed only by transcript evidence when userId is explicit")
    void execute_reflectionWithTranscriptEvidence_persistsEvidenceWithoutSourceMemories() throws Exception {
        SkillResult result = tool.execute(Map.of(
                "synthesisRunId", "dream-abc",
                "userId", 42L,
                "proposals", List.of(Map.of(
                        "type", "reflection",
                        "sourceMemoryIds", List.of(),
                        "suggestedTitle", "Prefers implementation plans",
                        "suggestedContent", "User prefers concrete implementation plans before code changes.",
                        "suggestedImportance", "high",
                        "reasoning", "observed from recent transcript",
                        "evidence", List.of(Map.of(
                                "source", "session",
                                "sessionId", "sess-1",
                                "seqNo", 7,
                                "quote", "先整理个具体方案"
                        ))
                ))
        ), new SkillContext(null, "curator-session", 0L));

        assertThat(result.isSuccess()).isTrue();
        JsonNode root = objectMapper.readTree(result.getOutput());
        assertThat(root.path("createdCount").asInt()).isEqualTo(1);

        ArgumentCaptor<List<MemoryProposalEntity>> cap = ArgumentCaptor.forClass(List.class);
        verify(proposalRepository).saveAll(cap.capture());
        MemoryProposalEntity saved = cap.getValue().get(0);
        assertThat(saved.getUserId()).isEqualTo(42L);
        assertThat(saved.getSourceMemoryIds()).isEqualTo("[]");
        assertThat(saved.getEvidenceJson()).contains("\"sessionId\":\"sess-1\"");
        assertThat(saved.getEvidenceJson()).contains("\"quote\":\"先整理个具体方案\"");
    }

    @Test
    @DisplayName("transcript-backed reflection requires positive explicit userId")
    void execute_transcriptReflectionMissingOrInvalidUserId_rejected() throws Exception {
        Map<String, Object> proposal = Map.of(
                "type", "reflection",
                "sourceMemoryIds", List.of(),
                "suggestedContent", "User prefers implementation plans.",
                "evidence", List.of(Map.of(
                        "source", "session",
                        "sessionId", "sess-1",
                        "seqNo", 7,
                        "quote", "plan first"
                )));

        SkillResult missing = tool.execute(Map.of(
                "synthesisRunId", "dream-missing-user",
                "proposals", List.of(proposal)
        ), new SkillContext(null, "curator-session", 0L));
        SkillResult invalid = tool.execute(Map.of(
                "synthesisRunId", "dream-invalid-user",
                "userId", 0L,
                "proposals", List.of(proposal)
        ), new SkillContext(null, "curator-session", 0L));

        JsonNode missingRoot = objectMapper.readTree(missing.getOutput());
        JsonNode invalidRoot = objectMapper.readTree(invalid.getOutput());
        assertThat(missingRoot.path("createdCount").asInt()).isZero();
        assertThat(missingRoot.path("rejections").get(0).path("error").asText())
                .contains("userId is required");
        assertThat(invalidRoot.path("createdCount").asInt()).isZero();
        assertThat(invalidRoot.path("rejections").get(0).path("error").asText())
                .contains("userId is required");
        verify(proposalRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("transcript-backed reflection requires seqNo in session evidence")
    void execute_transcriptReflectionMissingSeqNo_rejected() throws Exception {
        SkillResult result = tool.execute(Map.of(
                "synthesisRunId", "dream-no-seq",
                "userId", 42L,
                "proposals", List.of(Map.of(
                        "type", "reflection",
                        "sourceMemoryIds", List.of(),
                        "suggestedContent", "User prefers implementation plans.",
                        "evidence", List.of(Map.of(
                                "source", "session",
                                "sessionId", "sess-1",
                                "quote", "plan first"
                        ))
                ))
        ), new SkillContext(null, "curator-session", 0L));

        JsonNode root = objectMapper.readTree(result.getOutput());
        assertThat(root.path("createdCount").asInt()).isZero();
        assertThat(root.path("rejections").get(0).path("error").asText())
                .contains("requires session evidence");
        verify(proposalRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("transcript-backed reflection rejects malformed or non-session evidence")
    void execute_transcriptReflectionMalformedOrNonSessionEvidence_rejected() throws Exception {
        SkillResult nonSession = tool.execute(Map.of(
                "synthesisRunId", "dream-non-session",
                "userId", 42L,
                "proposals", List.of(Map.of(
                        "type", "reflection",
                        "sourceMemoryIds", List.of(),
                        "suggestedContent", "User prefers implementation plans.",
                        "evidence", List.of(Map.of(
                                "source", "memory",
                                "sessionId", "sess-1",
                                "seqNo", 7,
                                "quote", "plan first"
                        ))
                ))
        ), new SkillContext(null, "curator-session", 0L));
        SkillResult malformed = tool.execute(Map.of(
                "synthesisRunId", "dream-malformed",
                "userId", 42L,
                "proposals", List.of(Map.of(
                        "type", "reflection",
                        "sourceMemoryIds", List.of(),
                        "suggestedContent", "User prefers implementation plans.",
                        "evidence", "not-an-array"
                ))
        ), new SkillContext(null, "curator-session", 0L));

        JsonNode nonSessionRoot = objectMapper.readTree(nonSession.getOutput());
        JsonNode malformedRoot = objectMapper.readTree(malformed.getOutput());
        assertThat(nonSessionRoot.path("createdCount").asInt()).isZero();
        assertThat(nonSessionRoot.path("rejections").get(0).path("error").asText())
                .contains("requires session evidence");
        assertThat(malformedRoot.path("createdCount").asInt()).isZero();
        assertThat(malformedRoot.path("rejections").get(0).path("error").asText())
                .contains("requires serializable evidence array");
        verify(proposalRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("transcript-backed reflection rejects mixed valid and invalid evidence")
    void execute_transcriptReflectionMixedEvidence_rejected() throws Exception {
        SkillResult result = tool.execute(Map.of(
                "synthesisRunId", "dream-mixed-evidence",
                "userId", 42L,
                "proposals", List.of(Map.of(
                        "type", "reflection",
                        "sourceMemoryIds", List.of(),
                        "suggestedContent", "User prefers implementation plans.",
                        "evidence", List.of(
                                Map.of(
                                        "source", "session",
                                        "sessionId", "sess-1",
                                        "seqNo", 7,
                                        "quote", "plan first"
                                ),
                                Map.of(
                                        "source", "memory",
                                        "sessionId", "sess-2",
                                        "seqNo", 8,
                                        "quote", "not valid transcript evidence"
                                )
                        )
                ))
        ), new SkillContext(null, "curator-session", 0L));

        JsonNode root = objectMapper.readTree(result.getOutput());
        assertThat(root.path("createdCount").asInt()).isZero();
        assertThat(root.path("rejections").get(0).path("error").asText())
                .contains("requires only valid session evidence");
        verify(proposalRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("transcript-backed reflection requires textual sessionId")
    void execute_transcriptReflectionNonStringSessionId_rejected() throws Exception {
        SkillResult result = tool.execute(Map.of(
                "synthesisRunId", "dream-object-session-id",
                "userId", 42L,
                "proposals", List.of(Map.of(
                        "type", "reflection",
                        "sourceMemoryIds", List.of(),
                        "suggestedContent", "User prefers implementation plans.",
                        "evidence", List.of(Map.of(
                                "source", "session",
                                "sessionId", List.of("sess-1"),
                                "seqNo", 7,
                                "quote", "plan first"
                        ))
                ))
        ), new SkillContext(null, "curator-session", 0L));

        JsonNode root = objectMapper.readTree(result.getOutput());
        assertThat(root.path("createdCount").asInt()).isZero();
        assertThat(root.path("rejections").get(0).path("error").asText())
                .contains("requires only valid session evidence");
        verify(proposalRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("transcript-backed reflection requires textual quote")
    void execute_transcriptReflectionNonStringQuote_rejected() throws Exception {
        SkillResult result = tool.execute(Map.of(
                "synthesisRunId", "dream-object-quote",
                "userId", 42L,
                "proposals", List.of(Map.of(
                        "type", "reflection",
                        "sourceMemoryIds", List.of(),
                        "suggestedContent", "User prefers implementation plans.",
                        "evidence", List.of(Map.of(
                                "source", "session",
                                "sessionId", "sess-1",
                                "seqNo", 7,
                                "quote", Map.of("x", "y")
                        ))
                ))
        ), new SkillContext(null, "curator-session", 0L));

        JsonNode root = objectMapper.readTree(result.getOutput());
        assertThat(root.path("createdCount").asInt()).isZero();
        assertThat(root.path("rejections").get(0).path("error").asText())
                .contains("requires only valid session evidence");
        verify(proposalRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("Gap-2 regression: non-SYSTEM context still rejected on cross-user mismatch")
    void execute_regularContext_acrossUsers_stillDenied() throws Exception {
        // Regular user 2 context trying to author a proposal on user 1's memories — denied.
        when(memoryRepository.findAllById(anyIterable())).thenReturn(List.of(
                memOf(1L, 1L), memOf(2L, 1L)));

        SkillResult result = tool.execute(Map.of(
                "synthesisRunId", "run-bad",
                "proposals", List.of(Map.of(
                        "type", "dedup",
                        "sourceMemoryIds", List.of(1L, 2L),
                        "winnerMemoryId", 1L
                ))
        ), new SkillContext(null, "sub-session", 2L));

        JsonNode root = objectMapper.readTree(result.getOutput());
        assertThat(root.path("rejectedByValidation").asInt()).isEqualTo(1);
        assertThat(root.path("rejections").get(0).path("error").asText())
                .contains("refusing cross-user proposal");
        verify(proposalRepository, never()).saveAll(anyList());
    }
}
