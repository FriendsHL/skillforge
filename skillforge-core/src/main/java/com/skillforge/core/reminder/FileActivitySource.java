package com.skillforge.core.reminder;

import com.skillforge.core.compact.TokenEstimator;
import com.skillforge.core.compact.recovery.FileStateCache;
import com.skillforge.core.context.ContextLifecycle;
import com.skillforge.core.context.PromptCompactPolicy;
import com.skillforge.core.context.PromptPlacement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * REMINDER-MVP: emits a short "Recent files" summary so the LLM remembers what it just looked
 * at. Reuses the {@link FileStateCache} populated by P9-5 file-tool integrations.
 *
 * <p>Default cadence: every 5 turns; emit only when the cache has at least one file older than
 * {@code min-age-seconds} (default 30s) so we don't flood the prompt right after a Read.
 */
public class FileActivitySource implements ReminderSource {

    private static final Logger log = LoggerFactory.getLogger(FileActivitySource.class);

    public static final String NAME = "file-activity";

    private final FileStateCache fileStateCache;
    private final boolean enabled;
    private final int intervalTurns;
    private final int maxFiles;
    private final long minAgeSeconds;

    public FileActivitySource(FileStateCache fileStateCache,
                              boolean enabled,
                              int intervalTurns,
                              int maxFiles,
                              long minAgeSeconds) {
        this.fileStateCache = fileStateCache;
        this.enabled = enabled;
        this.intervalTurns = Math.max(1, intervalTurns);
        this.maxFiles = Math.max(1, maxFiles);
        this.minAgeSeconds = Math.max(0, minAgeSeconds);
    }

    @Override
    public String getName() { return NAME; }

    @Override
    public boolean shouldEmit(ReminderContext ctx) {
        if (!enabled || fileStateCache == null || ctx == null) return false;
        if (ctx.getSessionId() == null || ctx.getSessionId().isBlank()) return false;
        if (!debounceElapsed(ctx)) return false;
        // W5 fix: also gate on at least one entry being older than minAgeSeconds. Otherwise
        // shouldEmit fires every turn for a freshly-touched file, only for emit() to filter
        // the entry out and return null — wasted work + the symptomless "shouldEmit=true /
        // emit=null" pair shows up in logs as confusing noise.
        try {
            List<FileStateCache.FileEntry> entries =
                    fileStateCache.snapshot(ctx.getSessionId(), maxFiles, Integer.MAX_VALUE);
            if (entries == null || entries.isEmpty()) return false;
            Instant now = Instant.now();
            for (FileStateCache.FileEntry e : entries) {
                if (e.lastReadAt() == null) continue;
                long ageSec = Duration.between(e.lastReadAt(), now).getSeconds();
                if (ageSec >= minAgeSeconds) return true;
            }
            return false;
        } catch (Exception e) {
            log.debug("FileActivitySource.shouldEmit snapshot failed (skip): {}", e.toString());
            return false;
        }
    }

    @Override
    public ReminderEntry emit(ReminderContext ctx) {
        if (ctx == null || fileStateCache == null) return null;
        List<FileStateCache.FileEntry> entries;
        try {
            entries = fileStateCache.snapshot(ctx.getSessionId(), maxFiles, Integer.MAX_VALUE);
        } catch (Exception e) {
            log.debug("FileActivitySource.emit snapshot failed (skip): {}", e.toString());
            return null;
        }
        if (entries == null || entries.isEmpty()) return null;
        Instant now = Instant.now();
        StringBuilder sb = new StringBuilder(96);
        sb.append("Recent files: ");
        boolean first = true;
        for (FileStateCache.FileEntry e : entries) {
            if (e.lastReadAt() == null) continue;
            long ageSec = Math.max(0, Duration.between(e.lastReadAt(), now).getSeconds());
            if (ageSec < minAgeSeconds) continue;
            if (!first) sb.append(", ");
            sb.append(e.path() == null ? "(unknown)" : e.path())
                    .append(" (").append(formatAgeSeconds(ageSec)).append(" ago)");
            first = false;
        }
        if (first) return null; // every entry filtered by minAgeSeconds
        // Mark debounce only when we actually render. Q2: state lives on ReminderBuilder.
        ReminderBuilder builder = ctx.getReminderBuilder();
        if (builder != null && ctx.getSessionId() != null) {
            builder.setLastEmitted(ctx.getSessionId(), NAME, ctx.getCurrentTurnIndex());
        }
        String text = sb.toString();
        return new ReminderEntry(
                NAME,
                ReminderSourceType.FILE_ACTIVITY,
                ReminderSeverity.INFO,
                ReminderReasonCode.RECENT_FILE_CONTEXT,
                text,
                TokenEstimator.estimateString(text),
                intervalTurns,
                PromptPlacement.BEFORE_NEXT_MODEL_CALL,
                ContextLifecycle.UNTIL_STATE_CHANGE,
                PromptCompactPolicy.RELOAD_BY_ID,
                null);
    }

    private boolean debounceElapsed(ReminderContext ctx) {
        ReminderBuilder builder = ctx.getReminderBuilder();
        if (builder == null || ctx.getSessionId() == null) return true;
        Integer last = builder.getLastEmitted(ctx.getSessionId(), NAME);
        if (last == null) return true;
        return ctx.getCurrentTurnIndex() - last >= intervalTurns;
    }

    /** Render seconds as "30s" / "2 min" / "3h" / "5d". */
    static String formatAgeSeconds(long sec) {
        if (sec < 60) return sec + "s";
        long min = sec / 60;
        if (min < 60) return min + " min";
        long hours = min / 60;
        if (hours < 24) return hours + "h";
        long days = hours / 24;
        return days + "d";
    }
}
