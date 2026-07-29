package com.skillforge.core.reminder;

import com.skillforge.core.context.ContextAttachment;
import com.skillforge.core.context.ContextKind;
import com.skillforge.core.context.PromptAuthority;
import com.skillforge.core.context.PromptObservationHashes;
import com.skillforge.core.context.PromptSourceType;
import com.skillforge.core.context.PromptTrustLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REMINDER-MVP: orchestrator that runs every {@link ReminderSource} per turn, accumulates the
 * resulting {@link ReminderEntry}s under a shared token budget (PRD D7), and wraps the final
 * text in a {@code <system-reminder>...</system-reminder>} block.
 *
 * <p>Behavior:
 * <ul>
 *   <li>Global feature flag {@code globalEnabled=false} → {@link #build} returns {@code ""}
 *       (caller skips append).</li>
 *   <li>Iterates {@code sources} in their constructor-supplied order (D7:
 *       ContextUsage → MemoryAge → FileActivity).</li>
 *   <li>For each source, calls {@code shouldEmit} → {@code emit}; {@code null} entry skipped.</li>
 *   <li>Token budget cumulative: once {@code totalTokens + entry.estimatedTokens >
 *       totalBudgetTokens} the loop breaks (later sources skipped). The first entry is always
 *       admitted even if oversized — otherwise an oversized critical reminder (Context warning)
 *       would silently disappear.</li>
 *   <li>Empty result → return {@code ""} (no wrapper emitted).</li>
 *   <li>Source exceptions are caught and logged at WARN; the source is skipped, the build
 *       continues. Reminders must NEVER block the LLM call.</li>
 * </ul>
 *
 * <p>Q2 cache-friendly migration (2026-05-10): per-source debounce state lives here, keyed by
 * {@code sessionId → sourceName → turnIndex}. Previously kept on {@link
 * com.skillforge.core.engine.LoopContext} (per-loop). Builder lifetime is process-scope (Spring
 * singleton); restart clears state, matching the prior per-loop reset semantics for any agent
 * that wasn't actively in a long-running loop. Different sessions never share state. Single
 * session is serialised by ChatService user-msg path (DB lock), so plain {@link ConcurrentHashMap}
 * is sufficient — concurrent writes only happen across sessions.
 *
 * <p>Framework-free POJO (same model as P9-5 RecoveryPayloadBuilder): no Spring annotations.
 * Server module wires sources, budget and global flag via constructor in {@code SkillForgeConfig}.
 */
public class ReminderBuilder {

    private static final Logger log = LoggerFactory.getLogger(ReminderBuilder.class);

    public static final int DEFAULT_TOTAL_BUDGET_TOKENS = 5_000;

    private final List<ReminderSource> sources;
    private final int totalBudgetTokens;
    private final boolean globalEnabled;
    private final boolean structuredEnabled;
    private final ReminderCompatibilityRenderer compatibilityRenderer =
            new ReminderCompatibilityRenderer();

    /**
     * Q2: per-session per-source debounce state.
     * {@code sessionId → (sourceName → lastEmittedTurnIndex)}.
     * Restart wipes all state (acceptable — prior per-loop state had identical semantics for
     * any session whose loop was not currently in flight).
     */
    private final Map<String, Map<String, Integer>> debounceBySession = new ConcurrentHashMap<>();
    private final Map<String, List<ReminderObservation>> observationsBySession =
            new ConcurrentHashMap<>();

    /**
     * @param sources           ordered source list (D7); {@code null} → empty list
     * @param totalBudgetTokens shared budget in tokens (≤ 0 falls back to {@link #DEFAULT_TOTAL_BUDGET_TOKENS})
     * @param globalEnabled     master switch; false → {@link #build} short-circuits to ""
     */
    public ReminderBuilder(List<ReminderSource> sources, int totalBudgetTokens, boolean globalEnabled) {
        this(sources, totalBudgetTokens, globalEnabled, true);
    }

    public ReminderBuilder(List<ReminderSource> sources,
                           int totalBudgetTokens,
                           boolean globalEnabled,
                           boolean structuredEnabled) {
        this.sources = sources != null ? List.copyOf(sources) : Collections.emptyList();
        this.totalBudgetTokens = totalBudgetTokens > 0 ? totalBudgetTokens : DEFAULT_TOTAL_BUDGET_TOKENS;
        this.globalEnabled = globalEnabled;
        this.structuredEnabled = structuredEnabled;
    }

    public boolean isGlobalEnabled() { return globalEnabled; }
    public boolean isStructuredEnabled() { return structuredEnabled; }
    public int getTotalBudgetTokens() { return totalBudgetTokens; }

    /**
     * Q2: read the last-emitted turn index for {@code sourceName} on {@code sessionId}, or
     * {@code null} if the source has never emitted in this session. Sources call this from
     * {@code shouldEmit} to enforce Turn Count Debounce (PRD D3).
     *
     * <p>Defensive null sessionId / sourceName → returns {@code null} (always allow).
     */
    public Integer getLastEmitted(String sessionId, String sourceName) {
        if (sessionId == null || sourceName == null) return null;
        Map<String, Integer> bySource = debounceBySession.get(sessionId);
        if (bySource == null) return null;
        return bySource.get(sourceName);
    }

    /**
     * Q2: record that {@code sourceName} just emitted at {@code turnIndex} on {@code sessionId}
     * (typically {@code messages.size()} at emit time). Subsequent {@code shouldEmit} checks
     * for the same source on the same session compare against this value to enforce per-source
     * debounce intervals.
     *
     * <p>Defensive null sessionId / sourceName → no-op.
     */
    public void setLastEmitted(String sessionId, String sourceName, int turnIndex) {
        if (sessionId == null || sourceName == null) return;
        debounceBySession
                .computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
                .put(sourceName, turnIndex);
    }

    /**
     * BE-W1 fix: drop all per-source debounce state for a session. Called on session deletion
     * by SessionService so the singleton's {@code debounceBySession} doesn't accumulate dead
     * entries (without this, deleted sessions remain in memory until process restart — ~20MB
     * for 100K deletions).
     *
     * <p>Defensive null sessionId → no-op. Idempotent.
     */
    public void clearSession(String sessionId) {
        if (sessionId == null) return;
        debounceBySession.remove(sessionId);
        observationsBySession.remove(sessionId);
    }

    /**
     * Latest content-free reminder metadata for a session. This is diagnostic
     * runtime state only and intentionally disappears on restart.
     */
    public List<ReminderObservation> getLastObservations(String sessionId) {
        if (sessionId == null) return List.of();
        return observationsBySession.getOrDefault(sessionId, List.of());
    }

    /**
     * Build the reminder text for one turn.
     *
     * @param ctx per-iteration context; {@code null} returns {@code ""}
     * @return either {@code "<system-reminder>\n…\n</system-reminder>\n"} or {@code ""}
     *         when nothing was emitted / globally disabled.
     *         Q2 (cache-friendly): no leading {@code "\n"} — caller (ChatService ContentBlock)
     *         doesn't need the legacy promptSuffix-join newline. Recovery payloads handle their
     *         own framing.
     */
    public String build(ReminderContext ctx) {
        return buildResult(ctx).renderedText();
    }

    /**
     * Build typed reminder entries and their traceable ContextAttachments. When
     * structured mode is disabled, rendered bytes still come from the exact
     * legacy algorithm and the metadata lists remain empty.
     */
    public ReminderBuildResult buildResult(ReminderContext ctx) {
        if (!globalEnabled) return ReminderBuildResult.empty();
        if (ctx == null) return ReminderBuildResult.empty();

        List<ReminderEntry> entries = new ArrayList<>();
        int totalTokens = 0;

        for (ReminderSource source : sources) {
            ReminderEntry entry = invokeSafely(source, ctx);
            if (entry == null) continue;
            if (structuredEnabled) {
                entry = entry.withSourceDefaults(safeName(source));
                if (isExpired(entry)) continue;
            }
            // Budget gate: always admit the first entry so an oversized critical signal still
            // lands. Subsequent entries are gated.
            if (!entries.isEmpty() && totalTokens + entry.estimatedTokens() > totalBudgetTokens) {
                break;
            }
            entries.add(entry);
            totalTokens += entry.estimatedTokens();
        }

        String rendered = compatibilityRenderer.render(entries);
        if (!structuredEnabled || entries.isEmpty()) {
            updateObservations(ctx.getSessionId(), List.of());
            return new ReminderBuildResult(rendered, List.of(), List.of());
        }
        List<ContextAttachment> attachments =
                entries.stream().map(ReminderBuilder::toAttachment).toList();
        updateObservations(ctx.getSessionId(), entries.stream()
                .map(ReminderBuilder::toObservation)
                .toList());
        return new ReminderBuildResult(rendered, entries, attachments);
    }

    private static boolean isExpired(ReminderEntry entry) {
        return entry.expiresAt() != null && !entry.expiresAt().isAfter(Instant.now());
    }

    private static ContextAttachment toAttachment(ReminderEntry entry) {
        String content = entry.text();
        return new ContextAttachment(
                "reminder:" + entry.id(),
                ContextKind.REMINDER,
                PromptSourceType.REMINDER,
                PromptAuthority.PLATFORM,
                PromptTrustLevel.TRUSTED_RUNTIME_DATA,
                entry.placement(),
                entry.lifecycle(),
                entry.compactPolicy(),
                content,
                false,
                false,
                entry.estimatedTokens(),
                entry.expiresAt(),
                List.of(entry.source().name(), entry.reasonCode().name(),
                        entry.severity().name()),
                PromptObservationHashes.sha256(content));
    }

    private static ReminderObservation toObservation(ReminderEntry entry) {
        return new ReminderObservation(
                entry.id(),
                entry.source(),
                entry.severity(),
                entry.reasonCode(),
                entry.estimatedTokens(),
                entry.placement(),
                entry.lifecycle(),
                entry.compactPolicy(),
                entry.expiresAt(),
                PromptObservationHashes.sha256(entry.text()));
    }

    private void updateObservations(
            String sessionId, List<ReminderObservation> observations) {
        if (sessionId == null) return;
        if (observations == null || observations.isEmpty()) {
            observationsBySession.remove(sessionId);
            return;
        }
        observationsBySession.put(sessionId, List.copyOf(observations));
    }

    /** Run source.shouldEmit + emit, wrapping any throw in WARN log + null return. */
    private ReminderEntry invokeSafely(ReminderSource source, ReminderContext ctx) {
        if (source == null) return null;
        try {
            if (!source.shouldEmit(ctx)) return null;
            return source.emit(ctx);
        } catch (Exception e) {
            log.warn("ReminderSource '{}' failed (skipped): {}",
                    safeName(source), e.toString());
            return null;
        }
    }

    private static String safeName(ReminderSource source) {
        try { return source.getName(); } catch (Exception ignored) { return "?"; }
    }
}
