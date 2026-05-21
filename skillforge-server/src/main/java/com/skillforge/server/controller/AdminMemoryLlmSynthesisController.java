package com.skillforge.server.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.dto.MemoryProposalDto;
import com.skillforge.server.dto.MemorySourceSummary;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.MemoryEntity;
import com.skillforge.server.entity.MemoryProposalEntity;
import com.skillforge.server.entity.ScheduledTaskEntity;
import com.skillforge.server.bootstrap.SystemAgentNames;
import com.skillforge.server.memory.llmsynth.LlmMemorySynthesisScheduler;
import com.skillforge.server.memory.llmsynth.LlmMemorySynthesisScheduler.SchedulerSummary;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.MemoryRepository;
import com.skillforge.server.repository.ScheduledTaskRepository;
import com.skillforge.server.service.MemoryProposalService;
import com.skillforge.server.service.scheduling.ScheduledTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MEMORY-LLM-SYNTHESIS (V68): admin endpoints to manually trigger a synthesis run + review
 * the resulting {@code t_memory_proposal} entries (approve / reject / edit / revert / pick-winner).
 *
 * <p>Mirrors {@link AdminMemoryConsolidationController}'s pattern. The manual run-once path
 * bypasses the {@code scheduled-enabled} yaml gate (per PRD flow B); the daily cron is the
 * only path gated by that flag.
 */
@RestController
@RequestMapping("/api/admin/memory")
public class AdminMemoryLlmSynthesisController {

    private static final Logger log = LoggerFactory.getLogger(AdminMemoryLlmSynthesisController.class);

    private final LlmMemorySynthesisScheduler scheduler;
    private final MemoryProposalService proposalService;
    private final MemoryRepository memoryRepository;
    private final ObjectMapper objectMapper;
    private final AgentRepository agentRepository;
    private final ScheduledTaskRepository scheduledTaskRepository;
    private final ScheduledTaskExecutor scheduledTaskExecutor;

    public AdminMemoryLlmSynthesisController(LlmMemorySynthesisScheduler scheduler,
                                             MemoryProposalService proposalService,
                                             MemoryRepository memoryRepository,
                                             ObjectMapper objectMapper,
                                             AgentRepository agentRepository,
                                             ScheduledTaskRepository scheduledTaskRepository,
                                             ScheduledTaskExecutor scheduledTaskExecutor) {
        this.scheduler = scheduler;
        this.proposalService = proposalService;
        this.memoryRepository = memoryRepository;
        this.objectMapper = objectMapper;
        this.agentRepository = agentRepository;
        this.scheduledTaskRepository = scheduledTaskRepository;
        this.scheduledTaskExecutor = scheduledTaskExecutor;
    }

    /**
     * FU-1: enrich a batch of proposal DTOs with inline source-memory previews.
     * Single repository fetch across all proposals' source ids — O(1) DB roundtrip.
     */
    private List<MemoryProposalDto> toDtoListWithSources(List<MemoryProposalEntity> rows) {
        if (rows.isEmpty()) return List.of();

        // 1) Collect every referenced source id across all proposals.
        List<Long> allIds = new ArrayList<>();
        Map<Long, List<Long>> perProposal = new HashMap<>(rows.size());
        for (MemoryProposalEntity p : rows) {
            List<Long> ids = parseSourceIds(p.getSourceMemoryIds());
            perProposal.put(p.getId(), ids);
            allIds.addAll(ids);
        }

        // 2) Single DB fetch — fall back to per-id resolution if list-fetch fails.
        Map<Long, MemoryEntity> byId = new HashMap<>();
        if (!allIds.isEmpty()) {
            for (MemoryEntity m : memoryRepository.findAllById(allIds)) {
                byId.put(m.getId(), m);
            }
        }

        // 3) Build DTOs.
        List<MemoryProposalDto> out = new ArrayList<>(rows.size());
        for (MemoryProposalEntity p : rows) {
            List<Long> ids = perProposal.getOrDefault(p.getId(), List.of());
            List<MemorySourceSummary> summaries = new ArrayList<>(ids.size());
            for (Long id : ids) {
                MemoryEntity m = byId.get(id);
                if (m != null) {
                    summaries.add(MemorySourceSummary.from(m));
                }
                // Missing rows silently skipped: source may have been deleted; FE shows
                // count mismatch via sourceMemoryIds vs sourceMemories.size().
            }
            // R2-2 fix: pass injected ObjectMapper so sourceMemoryIds is emitted as
            // List<Long> not raw jsonb string.
            out.add(MemoryProposalDto.from(p, summaries, objectMapper));
        }
        return out;
    }

    private List<Long> parseSourceIds(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Long> ids = objectMapper.readValue(json, new TypeReference<List<Long>>() {});
            return ids != null ? ids : List.of();
        } catch (Exception e) {
            log.warn("Admin endpoint: failed to parse sourceMemoryIds={}: {}", json, e.getMessage());
            return List.of();
        }
    }

    /**
     * V69 dogfood: manual trigger of the memory-curator ScheduledTask.
     *
     * <p>Returns 202 Accepted with the run + session ids so the FE can jump to the
     * live session trace. The actual synthesis happens inside the agent loop —
     * dedupCount / reflectionCount / etc. are no longer known synchronously
     * (they materialize over the loop's lifetime as the agent calls
     * CreateMemoryProposal). The FE shape (MemorySynthesisRunResult) carries
     * those as {@code 0} on the dogfood path; the operator inspects via the
     * Pending Reflections tab after the session finishes (SESSION_END hook
     * triggers a WS broadcast).
     *
     * <p>If the migration-seeded memory-curator agent / ScheduledTask is missing
     * (admin somehow deleted it, or migration hasn't applied) we fall back to
     * the legacy {@link LlmMemorySynthesisScheduler#runOnce} path so the
     * endpoint never 500s on a fresh / partial environment.
     *
     * <p>{@code userId} is currently accepted for backward compat with the FE
     * but ignored on the dogfood path — the agent's workflow picks its own
     * active users via {@code ListActiveUsersTool}. We log the filter for
     * future re-introduction (sub-agent gating).
     */
    @PostMapping("/llm-synthesis/run-once")
    public ResponseEntity<?> runOnce(@RequestParam(value = "userId", required = false) Long userId) {
        log.info("Admin manual trigger: memory-llm-synthesis run-once (userId filter={})", userId);

        // 1. Try the dogfood path first.
        Optional<ScheduledTaskEntity> taskOpt = findMemoryCuratorTask();
        if (taskOpt.isPresent()) {
            ScheduledTaskEntity task = taskOpt.get();
            try {
                Optional<ScheduledTaskExecutor.FireOutcome> outcome =
                        scheduledTaskExecutor.fireForResult(task.getId(), /* manual */ true);
                if (outcome.isEmpty()) {
                    // skip-if-running collision — task is already firing from cron or another admin click.
                    return ResponseEntity.status(409).body(Map.of(
                            "ok", false,
                            "error", "memory-curator task already in flight (skip-if-running)"));
                }
                ScheduledTaskExecutor.FireOutcome o = outcome.get();
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("ok", true);
                body.put("ran", "memory-curator-scheduled-task");
                body.put("at", Instant.now().toString());
                body.put("userIdFilter", userId);
                body.put("taskId", o.taskId());
                body.put("runId", o.runId());
                body.put("sessionId", o.sessionId());
                // Counts unknown at trigger time on the dogfood path — agent emits them
                // over the lifetime of the loop. FE keeps these as 0 to satisfy the
                // existing MemorySynthesisRunResult shape; the SESSION_END WS broadcast
                // triggers the actual refresh.
                body.put("status", "queued");
                body.put("dedupCount", 0);
                body.put("reflectionCount", 0);
                body.put("optimizeCount", 0);
                body.put("contradictionCount", 0);
                body.put("inputTokens", 0);
                body.put("outputTokens", 0);
                body.put("estimatedUsd", 0.0);
                return ResponseEntity.accepted().body(body);
            } catch (Exception e) {
                log.error("Admin dogfood trigger fired but executor threw: {}", e.getMessage(), e);
                return ResponseEntity.internalServerError().body(Map.of(
                        "ok", false,
                        "error", e.getMessage() == null ? "unknown" : e.getMessage()));
            }
        }

        // 2. Legacy fallback — memory-curator seed not present (test profile bypassing
        //    V69 migration, or operator deleted the row). Keeps the endpoint useful so
        //    we don't hard-fail on fresh dev databases.
        log.warn("memory-curator agent / scheduled-task not found — falling back to legacy "
                + "LlmMemorySynthesisScheduler.runOnce path");
        try {
            // R2-1 fix: admin manual trigger bypasses the scheduled-enabled gate so the
            // legacy fallback path is usable even when default-off.
            SchedulerSummary summary = scheduler.runOnce(userId, true);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("ran", "memory-llm-synthesis-legacy");
            body.put("at", Instant.now().toString());
            body.put("userIdFilter", userId);
            body.put("eligible", summary.eligible());
            body.put("succeeded", summary.succeeded());
            body.put("failed", summary.failed());
            body.put("dedupCount", summary.dedupProposals());
            body.put("reflectionCount", summary.reflectionProposals());
            body.put("optimizeCount", summary.optimizeProposals());
            body.put("contradictionCount", summary.contradictionProposals());
            body.put("inputTokens", summary.inputTokens());
            body.put("outputTokens", summary.outputTokens());
            body.put("estimatedUsd", summary.estimatedUsd());
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            log.error("Legacy fallback trigger failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("ok", false, "error", e.getMessage() == null ? "unknown" : e.getMessage()));
        }
    }

    /**
     * Locate the V69-seeded {@code memory-curator nightly} ScheduledTask row. Resolves
     * by name (deterministic per V69 migration) — agent-id join would also work but
     * resolving in two steps keeps the failure mode legible in logs.
     */
    private Optional<ScheduledTaskEntity> findMemoryCuratorTask() {
        Optional<AgentEntity> agentOpt = agentRepository.findFirstByName(SystemAgentNames.MEMORY_CURATOR);
        if (agentOpt.isEmpty()) {
            return Optional.empty();
        }
        Long agentId = agentOpt.get().getId();
        // Linear scan over (typically 0-2) system-owned tasks is fine vs. introducing a
        // dedicated findByAgentId repository method just for this single caller.
        return scheduledTaskRepository.findAll().stream()
                .filter(t -> agentId.equals(t.getAgentId()))
                .filter(t -> Long.valueOf(0L).equals(t.getCreatorUserId()))
                .findFirst();
    }

    /**
     * E2E-1 fix: return a raw JSON array (not an envelope object) so the FE can
     * iterate the result directly. Other admin endpoints in this codebase return
     * arrays too; the previous {@code {ok, proposals, count}} shape was an
     * outlier and broke the FE TanStack Query consumer.
     */
    @GetMapping("/proposals")
    public ResponseEntity<List<MemoryProposalDto>> listProposals(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {
        List<MemoryProposalEntity> rows = proposalService.list(userId, status, limit);
        return ResponseEntity.ok(toDtoListWithSources(rows));
    }

    @PostMapping("/proposals/{id}/approve")
    public ResponseEntity<?> approve(@PathVariable("id") Long proposalId,
                                     @RequestParam(value = "reviewerUserId", required = false)
                                     Long reviewerUserId) {
        try {
            MemoryProposalService.ApproveResult result = proposalService.approve(proposalId, reviewerUserId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", result.success());
            body.put("appliedType", result.appliedType());
            body.put("reason", result.reason());
            return ResponseEntity.ok(body);
        } catch (MemoryProposalService.ProposalNotFoundException e) {
            return ResponseEntity.status(404).body(Map.of("ok", false, "error", e.getMessage()));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", e.getMessage()));
        } catch (Exception e) {
            log.error("approve failed id={}: {}", proposalId, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("ok", false, "error", e.getMessage() == null ? "unknown" : e.getMessage()));
        }
    }

    @PostMapping("/proposals/{id}/reject")
    public ResponseEntity<?> reject(@PathVariable("id") Long proposalId,
                                    @RequestParam(value = "reviewerUserId", required = false)
                                    Long reviewerUserId) {
        try {
            MemoryProposalEntity p = proposalService.reject(proposalId, reviewerUserId);
            return ResponseEntity.ok(Map.of("ok", true,
                    "proposal", toDtoListWithSources(List.of(p)).get(0)));
        } catch (MemoryProposalService.ProposalNotFoundException e) {
            return ResponseEntity.status(404).body(Map.of("ok", false, "error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", e.getMessage()));
        }
    }

    @PatchMapping("/proposals/{id}")
    public ResponseEntity<?> edit(@PathVariable("id") Long proposalId,
                                  @RequestBody MemoryProposalService.EditRequest req) {
        try {
            MemoryProposalEntity p = proposalService.edit(proposalId, req == null
                    ? new MemoryProposalService.EditRequest(null, null, null, null) : req);
            return ResponseEntity.ok(Map.of("ok", true,
                    "proposal", toDtoListWithSources(List.of(p)).get(0)));
        } catch (MemoryProposalService.ProposalNotFoundException e) {
            return ResponseEntity.status(404).body(Map.of("ok", false, "error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/proposals/auto-archive-stale")
    public ResponseEntity<?> autoArchive() {
        int n = proposalService.autoArchiveStale();
        return ResponseEntity.ok(Map.of("ok", true, "archived", n));
    }

    @PostMapping("/proposals/{id}/revert")
    public ResponseEntity<?> revert(@PathVariable("id") Long proposalId) {
        try {
            MemoryProposalEntity p = proposalService.revert(proposalId);
            return ResponseEntity.ok(Map.of("ok", true,
                    "proposal", toDtoListWithSources(List.of(p)).get(0)));
        } catch (MemoryProposalService.ProposalNotFoundException e) {
            return ResponseEntity.status(404).body(Map.of("ok", false, "error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", e.getMessage()));
        }
    }

    /**
     * F-N1: contradiction picker + approve fused into a single endpoint.
     * Body: {@code { "winnerMemoryId": ..., "reviewerUserId": ... }}.
     */
    @PostMapping("/proposals/{id}/contradiction-pick")
    public ResponseEntity<?> contradictionPick(@PathVariable("id") Long proposalId,
                                                @RequestBody ContradictionPickRequest body) {
        if (body == null || body.winnerMemoryId() == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("ok", false, "error", "winnerMemoryId required"));
        }
        try {
            MemoryProposalService.ApproveResult result = proposalService.contradictionPickAndApprove(
                    proposalId, body.winnerMemoryId(), body.reviewerUserId());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", result.success());
            out.put("appliedType", result.appliedType());
            out.put("reason", result.reason());
            return ResponseEntity.ok(out);
        } catch (MemoryProposalService.ProposalNotFoundException e) {
            return ResponseEntity.status(404).body(Map.of("ok", false, "error", e.getMessage()));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", e.getMessage()));
        }
    }

    public record ContradictionPickRequest(Long winnerMemoryId, Long reviewerUserId) {
    }
}
