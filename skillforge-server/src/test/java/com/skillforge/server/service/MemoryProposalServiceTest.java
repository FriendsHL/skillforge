package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MemoryProposalService")
class MemoryProposalServiceTest {

    @Mock private MemoryProposalRepository proposalRepository;
    @Mock private MemoryRepository memoryRepository;

    private ObjectMapper objectMapper;
    private MemoryProposalService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new MemoryProposalService(proposalRepository, memoryRepository, objectMapper);
        when(proposalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(memoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static MemoryEntity mem(long id, String status) {
        MemoryEntity m = new MemoryEntity();
        m.setId(id);
        m.setUserId(1L);
        m.setStatus(status);
        m.setTitle("title-" + id);
        m.setContent("content-" + id);
        m.setImportance("medium");
        m.setType("knowledge");
        return m;
    }

    private static MemoryProposalEntity proposal(Long id, String type, String sourceIds, Long winnerId) {
        MemoryProposalEntity p = new MemoryProposalEntity();
        p.setId(id);
        p.setUserId(1L);
        p.setSynthesisRunId("synth-test");
        p.setProposalType(type);
        p.setSourceMemoryIds(sourceIds);
        p.setWinnerMemoryId(winnerId);
        p.setStatus(MemoryProposalEntity.STATUS_PROPOSED);
        p.setCreatedAt(Instant.now());
        return p;
    }

    @Test
    @DisplayName("approve dedup: losers ARCHIVED + winner untouched + synthesisRunId stamped")
    void approve_dedup_archivesLosersKeepsWinner() {
        MemoryProposalEntity p = proposal(10L, MemoryProposalEntity.TYPE_DEDUP, "[101,102]", 101L);
        when(proposalRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(p));
        MemoryEntity winner = mem(101L, "ACTIVE");
        MemoryEntity loser = mem(102L, "ACTIVE");
        when(memoryRepository.findAllByIdForUpdate(anyList())).thenReturn(List.of(winner, loser));

        MemoryProposalService.ApproveResult r = service.approve(10L, 7L);

        assertThat(r.success()).isTrue();
        assertThat(r.appliedType()).isEqualTo("dedup");
        assertThat(winner.getStatus()).isEqualTo("ACTIVE");
        assertThat(loser.getStatus()).isEqualTo("ARCHIVED");
        assertThat(loser.getArchivedReason()).isEqualTo("llm_dedup_merge_with_101_proposal_10");
        assertThat(loser.getSynthesisRunId()).isEqualTo("synth-test");
        assertThat(p.getStatus()).isEqualTo(MemoryProposalEntity.STATUS_APPROVED);
    }

    @Test
    @DisplayName("approve reflection: new memory created, sources untouched, memory_kind=reflection")
    void approve_reflection_newMemoryCreated() {
        MemoryProposalEntity p = proposal(11L, MemoryProposalEntity.TYPE_REFLECTION, "[101,102]", null);
        p.setSuggestedTitle("insight");
        p.setSuggestedContent("user prefers PG");
        p.setSuggestedImportance("high");
        when(proposalRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(p));
        MemoryEntity s1 = mem(101L, "ACTIVE");
        MemoryEntity s2 = mem(102L, "STALE");
        when(memoryRepository.findAllByIdForUpdate(anyList())).thenReturn(List.of(s1, s2));

        MemoryProposalService.ApproveResult r = service.approve(11L, 7L);

        assertThat(r.success()).isTrue();
        ArgumentCaptor<MemoryEntity> cap = ArgumentCaptor.forClass(MemoryEntity.class);
        verify(memoryRepository).save(cap.capture());
        MemoryEntity created = cap.getValue();
        assertThat(created.getMemoryKind()).isEqualTo("reflection");
        assertThat(created.getDerivedFromMemoryIds()).isEqualTo("[101,102]");
        assertThat(created.getSynthesisRunId()).isEqualTo("synth-test");
        assertThat(created.getStatus()).isEqualTo("ACTIVE");
        // Sources untouched (STALE is allowed for reflection)
        assertThat(s1.getStatus()).isEqualTo("ACTIVE");
        assertThat(s2.getStatus()).isEqualTo("STALE");
    }

    @Test
    @DisplayName("approve transcript-backed reflection: empty sources create ACTIVE reflection")
    void approve_transcriptBackedReflection_newMemoryCreated() {
        MemoryProposalEntity p = proposal(111L, MemoryProposalEntity.TYPE_REFLECTION, "[]", null);
        p.setSuggestedTitle("implementation plans");
        p.setSuggestedContent("User prefers concrete implementation plans before code changes.");
        p.setSuggestedImportance("high");
        p.setEvidenceJson("[{\"source\":\"session\",\"sessionId\":\"sess-1\",\"seqNo\":7,\"quote\":\"plan first\"}]");
        when(proposalRepository.findByIdForUpdate(111L)).thenReturn(Optional.of(p));

        MemoryProposalService.ApproveResult r = service.approve(111L, 7L);

        assertThat(r.success()).isTrue();
        ArgumentCaptor<MemoryEntity> memoryCap = ArgumentCaptor.forClass(MemoryEntity.class);
        verify(memoryRepository).save(memoryCap.capture());
        MemoryEntity created = memoryCap.getValue();
        assertThat(created.getUserId()).isEqualTo(1L);
        assertThat(created.getTitle()).isEqualTo("implementation plans");
        assertThat(created.getContent()).isEqualTo("User prefers concrete implementation plans before code changes.");
        assertThat(created.getImportance()).isEqualTo("high");
        assertThat(created.getStatus()).isEqualTo("ACTIVE");
        assertThat(created.getMemoryKind()).isEqualTo("reflection");
        assertThat(created.getType()).isEqualTo("knowledge");
        assertThat(created.getDerivedFromMemoryIds()).isEqualTo("[]");
        assertThat(p.getStatus()).isEqualTo(MemoryProposalEntity.STATUS_APPROVED);
        assertThat(p.getReviewedByUserId()).isEqualTo(7L);
        verify(memoryRepository, never()).findAllByIdForUpdate(anyList());
    }

    @Test
    @DisplayName("approve non-reflection with empty sources still rejects")
    void approve_nonReflectionEmptySources_throws() {
        MemoryProposalEntity p = proposal(112L, MemoryProposalEntity.TYPE_OPTIMIZE, "[]", null);
        p.setSuggestedContent("rewritten");
        when(proposalRepository.findByIdForUpdate(112L)).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> service.approve(112L, 7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("proposal has no sourceMemoryIds");
        verify(memoryRepository, never()).findAllByIdForUpdate(anyList());
        verify(memoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("approve optimize: target content updated, original_content preserved")
    void approve_optimize_targetUpdated() {
        MemoryProposalEntity p = proposal(12L, MemoryProposalEntity.TYPE_OPTIMIZE, "[101]", null);
        p.setSuggestedContent("rewritten");
        when(proposalRepository.findByIdForUpdate(12L)).thenReturn(Optional.of(p));
        MemoryEntity target = mem(101L, "ACTIVE");
        target.setContent("original text");
        when(memoryRepository.findAllByIdForUpdate(anyList())).thenReturn(List.of(target));

        service.approve(12L, 7L);

        assertThat(target.getContent()).isEqualTo("rewritten");
        assertThat(target.getOriginalContent()).isEqualTo("original text");
        assertThat(target.getMemoryKind()).isEqualTo("optimized");
        assertThat(target.getSynthesisRunId()).isEqualTo("synth-test");
    }

    @Test
    @DisplayName("approve contradiction with winner: winner bumped, loser archived")
    void approve_contradiction_winnerBumped() {
        MemoryProposalEntity p = proposal(13L, MemoryProposalEntity.TYPE_CONTRADICTION, "[101,102]", 101L);
        when(proposalRepository.findByIdForUpdate(13L)).thenReturn(Optional.of(p));
        MemoryEntity winner = mem(101L, "ACTIVE");
        MemoryEntity loser = mem(102L, "ACTIVE");
        when(memoryRepository.findAllByIdForUpdate(anyList())).thenReturn(List.of(winner, loser));

        service.approve(13L, 7L);

        assertThat(winner.getImportance()).isEqualTo("high");
        assertThat(loser.getStatus()).isEqualTo("ARCHIVED");
        assertThat(loser.getArchivedReason()).isEqualTo("llm_contradiction_101_proposal_13");
    }

    @Test
    @DisplayName("approve dedup with stale source: proposal marked stale, memory not touched")
    void approve_dedupStaleSource_marksStale() {
        MemoryProposalEntity p = proposal(14L, MemoryProposalEntity.TYPE_DEDUP, "[101,102]", 101L);
        when(proposalRepository.findByIdForUpdate(14L)).thenReturn(Optional.of(p));
        MemoryEntity s1 = mem(101L, "ACTIVE");
        MemoryEntity s2 = mem(102L, "ARCHIVED");
        when(memoryRepository.findAllByIdForUpdate(anyList())).thenReturn(List.of(s1, s2));

        MemoryProposalService.ApproveResult r = service.approve(14L, 7L);

        assertThat(r.success()).isFalse();
        assertThat(r.reason()).isEqualTo("source_archived_or_stale");
        assertThat(p.getStatus()).isEqualTo(MemoryProposalEntity.STATUS_STALE);
        // s1 was not modified to ARCHIVED
        assertThat(s1.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("approve optimize with non-ACTIVE source: stale")
    void approve_optimizeStaleSource_marksStale() {
        MemoryProposalEntity p = proposal(15L, MemoryProposalEntity.TYPE_OPTIMIZE, "[101]", null);
        p.setSuggestedContent("better");
        when(proposalRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(p));
        when(memoryRepository.findAllByIdForUpdate(anyList()))
                .thenReturn(List.of(mem(101L, "STALE")));

        MemoryProposalService.ApproveResult r = service.approve(15L, 7L);

        assertThat(r.success()).isFalse();
        assertThat(r.reason()).isEqualTo("source_not_active");
    }

    @Test
    @DisplayName("approve reflection with ARCHIVED source: stale")
    void approve_reflectionArchivedSource_marksStale() {
        MemoryProposalEntity p = proposal(16L, MemoryProposalEntity.TYPE_REFLECTION, "[101,102]", null);
        p.setSuggestedContent("x");
        when(proposalRepository.findByIdForUpdate(16L)).thenReturn(Optional.of(p));
        when(memoryRepository.findAllByIdForUpdate(anyList()))
                .thenReturn(List.of(mem(101L, "ACTIVE"), mem(102L, "ARCHIVED")));

        MemoryProposalService.ApproveResult r = service.approve(16L, 7L);

        assertThat(r.success()).isFalse();
        assertThat(r.reason()).isEqualTo("source_archived");
    }

    @Test
    @DisplayName("approve dedup with size > 5: blocked at approve gate (B-3)")
    void approve_dedupTooLarge_throws() {
        MemoryProposalEntity p = proposal(17L, MemoryProposalEntity.TYPE_DEDUP,
                "[101,102,103,104,105,106]", 101L);
        when(proposalRepository.findByIdForUpdate(17L)).thenReturn(Optional.of(p));
        List<MemoryEntity> wide = new ArrayList<>();
        for (long id = 101; id <= 106; id++) wide.add(mem(id, "ACTIVE"));
        when(memoryRepository.findAllByIdForUpdate(anyList())).thenReturn(wide);

        assertThatThrownBy(() -> service.approve(17L, 7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blocked at approve gate");
    }

    @Test
    @DisplayName("approve on non-proposed status throws")
    void approve_alreadyApproved_throws() {
        MemoryProposalEntity p = proposal(18L, MemoryProposalEntity.TYPE_DEDUP, "[101,102]", 101L);
        p.setStatus(MemoryProposalEntity.STATUS_APPROVED);
        when(proposalRepository.findByIdForUpdate(18L)).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> service.approve(18L, 7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not in proposed state");
    }

    @Test
    @DisplayName("approve unknown proposal id: throws ProposalNotFoundException")
    void approve_notFound_throws() {
        when(proposalRepository.findByIdForUpdate(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.approve(99L, 7L))
                .isInstanceOf(MemoryProposalService.ProposalNotFoundException.class);
    }

    @Test
    @DisplayName("reject: status flips to rejected, memory not touched")
    void reject_flipsStatus() {
        MemoryProposalEntity p = proposal(19L, MemoryProposalEntity.TYPE_DEDUP, "[101,102]", 101L);
        when(proposalRepository.findByIdForUpdate(19L)).thenReturn(Optional.of(p));

        service.reject(19L, 7L);

        assertThat(p.getStatus()).isEqualTo(MemoryProposalEntity.STATUS_REJECTED);
        assertThat(p.getReviewedByUserId()).isEqualTo(7L);
        verify(memoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("edit: updates suggested fields without changing status")
    void edit_updatesFields() {
        MemoryProposalEntity p = proposal(20L, MemoryProposalEntity.TYPE_REFLECTION, "[101,102]", null);
        when(proposalRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(p));

        service.edit(20L, new MemoryProposalService.EditRequest(
                "new title", "new content", "high", null));

        assertThat(p.getSuggestedTitle()).isEqualTo("new title");
        assertThat(p.getSuggestedContent()).isEqualTo("new content");
        assertThat(p.getSuggestedImportance()).isEqualTo("high");
        assertThat(p.getStatus()).isEqualTo(MemoryProposalEntity.STATUS_PROPOSED);
    }

    @Test
    @DisplayName("revert optimize: restores original_content")
    void revert_optimize_restoresOriginal() {
        MemoryProposalEntity p = proposal(21L, MemoryProposalEntity.TYPE_OPTIMIZE, "[101]", null);
        p.setStatus(MemoryProposalEntity.STATUS_APPROVED);
        when(proposalRepository.findByIdForUpdate(21L)).thenReturn(Optional.of(p));
        MemoryEntity target = mem(101L, "ACTIVE");
        target.setContent("optimized text");
        target.setOriginalContent("original text");
        target.setMemoryKind("optimized");
        when(memoryRepository.findById(101L)).thenReturn(Optional.of(target));

        service.revert(21L);

        assertThat(target.getContent()).isEqualTo("original text");
        assertThat(target.getOriginalContent()).isNull();
        assertThat(target.getMemoryKind()).isNull();
    }

    @Test
    @DisplayName("revert on non-approved proposal throws")
    void revert_notApproved_throws() {
        MemoryProposalEntity p = proposal(22L, MemoryProposalEntity.TYPE_OPTIMIZE, "[101]", null);
        when(proposalRepository.findByIdForUpdate(22L)).thenReturn(Optional.of(p));
        assertThatThrownBy(() -> service.revert(22L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires approved");
    }

    @Test
    @DisplayName("contradictionPickAndApprove: F-N1 single step picks winner then approves")
    void contradictionPick_singleStep() {
        MemoryProposalEntity p = proposal(23L, MemoryProposalEntity.TYPE_CONTRADICTION, "[101,102]", null);
        when(proposalRepository.findByIdForUpdate(23L)).thenReturn(Optional.of(p));
        MemoryEntity winner = mem(101L, "ACTIVE");
        MemoryEntity loser = mem(102L, "ACTIVE");
        when(memoryRepository.findAllByIdForUpdate(anyList())).thenReturn(List.of(winner, loser));

        MemoryProposalService.ApproveResult r = service.contradictionPickAndApprove(23L, 101L, 7L);

        assertThat(r.success()).isTrue();
        assertThat(p.getWinnerMemoryId()).isEqualTo(101L);
        assertThat(p.getStatus()).isEqualTo(MemoryProposalEntity.STATUS_APPROVED);
        assertThat(loser.getStatus()).isEqualTo("ARCHIVED");
    }

    @Test
    @DisplayName("contradictionPickAndApprove: rejects winner not in sources")
    void contradictionPick_winnerNotInSources_throws() {
        MemoryProposalEntity p = proposal(24L, MemoryProposalEntity.TYPE_CONTRADICTION, "[101,102]", null);
        when(proposalRepository.findByIdForUpdate(24L)).thenReturn(Optional.of(p));
        assertThatThrownBy(() -> service.contradictionPickAndApprove(24L, 999L, 7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not in sourceMemoryIds");
    }

    @Test
    @DisplayName("autoArchiveStale: proposed older than 7d → auto_archived")
    void autoArchiveStale_flipsOldProposals() {
        MemoryProposalEntity stale = proposal(25L, MemoryProposalEntity.TYPE_DEDUP, "[101,102]", 101L);
        stale.setCreatedAt(Instant.now().minusSeconds(8 * 86400));
        when(proposalRepository.findStaleProposed(any(Instant.class))).thenReturn(List.of(stale));

        int n = service.autoArchiveStale();

        assertThat(n).isEqualTo(1);
        assertThat(stale.getStatus()).isEqualTo(MemoryProposalEntity.STATUS_AUTO_ARCHIVED);
    }
}
