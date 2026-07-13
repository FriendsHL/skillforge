# Range Model Compaction Frontier Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent range-model compaction from re-exposing already-covered history, regressing the active summary frontier, or persisting injected summaries as user-visible NORMAL rows.

**Architecture:** Treat `t_session_summary` active ranges as the source of truth for the model view, with `compacted_by_summary_id` as a denormalized marker only. Full compact persistence must never supersede a wider active summary with a narrower range, and marker updates after a new active summary must restamp the full active range. Range-model light compact must not rewrite derived model-view summaries into `t_session_message`.

**Tech Stack:** Java 17, Spring Boot 3.2, JPA/Hibernate, Maven, JUnit 5, AssertJ, Mockito, Testcontainers/Postgres.

---

## File Map

- Modify: `skillforge-server/src/main/java/com/skillforge/server/service/SessionService.java`
  - Derive `getContextMessagesWithProvenance` from active summary ranges instead of marker runs.
  - Preserve provenance alignment and skip `SYSTEM_EVENT` rows.
- Modify: `skillforge-server/src/main/java/com/skillforge/server/service/CompactionService.java`
  - Add a monotonic active-summary frontier guard in `persistFullRangeModel`.
  - Supersede only summaries whose ranges are fully covered by the new range.
  - Restamp markers after full range-model summary persistence.
  - Prevent range-model light compact from persisting injected summary messages.
- Modify: `skillforge-server/src/main/java/com/skillforge/server/repository/SessionMessageRepository.java`
  - Add a bulk marker restamp query if needed, or reuse clear + existing mark.
- Modify tests:
  - `skillforge-server/src/test/java/com/skillforge/server/service/SessionServiceDerivedContextIT.java`
  - `skillforge-server/src/test/java/com/skillforge/server/compact/CompactionServiceRangeModelTest.java`
  - Add or extend a light-compact range-model test if needed.

## Task 1: Active Range Derivation

- [ ] **Step 1: Write failing test for stale marker rows inside active range**

Add a test to `SessionServiceDerivedContextIT`:

```java
@Test
@DisplayName("active summary range is authoritative even when row markers point at superseded summaries")
void activeRangeWinsOverStaleSupersededMarkers() {
    String sid = newSession();
    for (int i = 0; i < 8; i++) {
        appendRow(sid, Message.user("turn " + i));
    }

    SessionSummaryEntity oldS = newSummary(sid, 0, 3, "OLD SUMMARY", null);
    mark(sid, 0, 3, oldS.getId());
    SessionSummaryEntity active = newSummary(sid, 0, 5, "ACTIVE SUMMARY", null);
    sessionSummaryRepository.markSuperseded(oldS.getId(), active.getId());
    mark(sid, 4, 5, active.getId());

    SessionService.ContextWithProvenance ctx = sessionService.getContextMessagesWithProvenance(sid);

    assertThat(ctx.messages()).hasSize(3);
    assertThat(ctx.messages().get(0).getContent()).isEqualTo("ACTIVE SUMMARY");
    assertThat(ctx.messages().get(1).getTextContent()).isEqualTo("turn 6");
    assertThat(ctx.messages().get(2).getTextContent()).isEqualTo("turn 7");
    assertThat(ctx.provenance()).containsExactly(SessionService.PROVENANCE_SUMMARY, 6L, 7L);
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
mvn -pl skillforge-server -Dtest=SessionServiceDerivedContextIT#activeRangeWinsOverStaleSupersededMarkers test
```

Expected before implementation: FAIL because rows `0..3` are emitted as real rows when their marker points at a superseded summary.

- [ ] **Step 3: Implement active-range derivation**

Change `SessionService.getContextMessagesWithProvenance` to:

```java
List<SessionSummaryEntity> active = sessionSummaryRepository
        .findBySessionIdAndSupersededByIsNullOrderByStartSeqAsc(id);
// Walk records by seq_no. If record.seqNo is inside the next active summary range,
// emit exactly one summary and skip all rows through summary.endSeq.
```

The method must not require correct markers to collapse covered rows.

- [ ] **Step 4: Run the targeted test and existing derived-context IT**

Run:

```bash
mvn -pl skillforge-server -Dtest=SessionServiceDerivedContextIT test
```

Expected after implementation: PASS.

## Task 2: Monotonic Full Compact Frontier

- [ ] **Step 1: Write failing unit test for frontier regression**

Add a test to `CompactionServiceRangeModelTest`:

```java
@Test
@DisplayName("flag ON: compact with prior summary never moves the active frontier backwards")
void rangeModel_compactWithPriorSummaryDoesNotMoveFrontierBackwards() {
    seedSession("sREGRESS", 30, 0, "idle");
    seedMessages("sREGRESS");

    SessionSummaryEntity prior = new SessionSummaryEntity();
    prior.setId(summaryIdSeq.incrementAndGet());
    prior.setSessionId("sREGRESS");
    prior.setStartSeq(0L);
    prior.setEndSeq(SEQ_BASE + 20);
    prior.setSummaryText("WIDE ACTIVE SUMMARY");
    prior.setLevel("full");
    prior.setSource("engine-hard");
    summaryStore.put(prior.getId(), prior);

    CompactionEventEntity event = service.compact("sREGRESS", "full", "engine-hard", "regression");

    assertThat(event).isNotNull();
    assertThat(summaryStore).hasSize(2);
    SessionSummaryEntity priorAfter = summaryStore.get(prior.getId());
    assertThat(priorAfter.getSupersededBy()).isNotNull();
    SessionSummaryEntity activeAfter = summaryStore.get(priorAfter.getSupersededBy());
    assertThat(activeAfter.getEndSeq())
            .as("new active summary must include at least the prior frontier")
            .isGreaterThanOrEqualTo(prior.getEndSeq());
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
mvn -pl skillforge-server -Dtest=CompactionServiceRangeModelTest#rangeModel_compactWithPriorSummaryDoesNotMoveFrontierBackwards test
```

Expected before implementation: FAIL because the new active summary end seq is lower than the prior active frontier.

- [ ] **Step 3: Implement range-aware frontier mapping and scoped supersede**

In `persistFullRangeModel`:

```java
List<ModelViewFrame> modelViewFrames = sliceModelViewFrames(allRecords, priorActive);
long endSeq = modelViewFrames.get(lastWindowIdx).endSeq();
long priorActiveMaxEndSeq = priorActive.stream()
        .mapToLong(SessionSummaryEntity::getEndSeq)
        .max()
        .orElse(-1L);
if (endSeq < priorActiveMaxEndSeq) {
    log.warn("range-model fullCompact no-op (non-monotonic frontier) ...");
    return false;
}
```

Change `persistFullRangeModel` to return `boolean persisted`. If false, make `persistCompactResult` return `null` and make callers treat that as no persisted compaction event.

Supersede only summaries with:

```java
prior.getStartSeq() >= startSeq && prior.getEndSeq() <= endSeq
```

- [ ] **Step 4: Run range-model unit tests**

Run:

```bash
mvn -pl skillforge-server -Dtest=CompactionServiceRangeModelTest test
```

Expected: PASS.

## Task 3: Marker Restamp After Full Compact

- [ ] **Step 1: Write failing verification in repeated compact test**

Extend `rangeModel_repeatedCompact_supersedesPrior_noRowGrowth` to verify the second compact restamps rows from the new active range rather than only marking NULL rows:

```java
verify(messageRepository).clearCompactedMarkers(eq("sRM2"));
verify(messageRepository, org.mockito.Mockito.atLeastOnce())
        .markCompactedBySummary(eq("sRM2"), eq(0L), anyLong(), anyLong());
```

- [ ] **Step 2: Run RED**

Run:

```bash
mvn -pl skillforge-server -Dtest=CompactionServiceRangeModelTest#rangeModel_repeatedCompact_supersedesPrior_noRowGrowth test
```

Expected before implementation: FAIL because full compact only calls `markCompactedBySummary`, which leaves stale old markers in place.

- [ ] **Step 3: Restamp after saving summary**

Call `sessionService.recomputeCompactedMarkers(sessionId)` after superseding contained summaries, or add a package-visible service method if access requires it. This clears markers and stamps all active ranges consistently.

- [ ] **Step 4: Run tests**

Run:

```bash
mvn -pl skillforge-server -Dtest=CompactionServiceRangeModelTest,SessionServiceCompactedMarkerPreservationIT test
```

Expected: PASS.

## Task 4: Range-Model Light Compact Summary Leak Guard

- [ ] **Step 1: Write failing test**

Add a unit test around `persistCompactResult` behavior through `service.compact(..., "light", ...)` or a direct service test with a model-view message list containing:

```java
Message.user("[Context summary from 84 messages compacted at 2026-06-29T07:58:03Z]\nsummary body")
```

Expected behavior under range-model with active summaries: no call to `rewriteMessages` or `saveSessionMessages` may persist that derived summary as NORMAL.

- [ ] **Step 2: Run RED**

Run the targeted test and confirm it fails on the current light branch.

- [ ] **Step 3: Implement guard**

Under `rangeModelEnabled && level=light`, avoid rewriting/saving derived model-view messages. Persist only counters/event when the light compact has meaningful token reclaim, or return no-op if the only transformation is derived-view summarization. Do not change full user trace rows.

- [ ] **Step 4: Run targeted tests**

Run:

```bash
mvn -pl skillforge-server -Dtest='*LightCompact*,CompactionServiceRangeModelTest' test
```

Expected: PASS.

## Task 5: Review and Verification

- [ ] **Step 1: Run focused compaction/session tests**

```bash
mvn -pl skillforge-server -Dtest=SessionServiceDerivedContextIT,SessionServiceCompactedMarkerPreservationIT,SessionServiceRangeModelDivergenceGuardIT,CompactReconciliationDerivedViewIT,CompactionServiceRangeModelTest test
```

- [ ] **Step 2: Run broader backend compile/test slice**

```bash
mvn -pl skillforge-server -DskipTests compile
```

- [ ] **Step 3: Full pipeline review**

Review against:

- `.codex/rules/compact-review.md`
- `.codex/rules/persistence-shape-invariant.md`
- `.codex/rules/identity-column-on-rewrite.md`
- `.codex/rules/database-review.md`

Blockers:

- Active summary frontier can decrease.
- Injected summary can be written as NORMAL.
- Tool-use/tool-result pairing can be split.
- Existing user trace rows are deleted or rewritten by compaction.
- Marker drift changes model-view correctness.

- [ ] **Step 4: Final diff and verification report**

Report changed files, exact verification commands, and any skipped checks.
