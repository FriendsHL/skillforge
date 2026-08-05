package com.skillforge.core.compact.recovery;

import com.skillforge.core.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.ArrayList;

/**
 * P9-5: build the post-compact recovery payload (a single user-role {@link Message}) from
 * the per-session {@link FileStateCache} snapshot.
 *
 * <p>Format (REMINDER-MVP D6: wrapped in {@code <system-reminder>} tag once the framework
 * landed; PRD/MRD set Phase A as the migration point):
 *
 * <pre>
 * &lt;system-reminder&gt;
 * [Recovery payload — N most recently accessed files at &lt;HH:mm:ss UTC&gt;]
 *
 * ### /abs/path/file1 (lastRead 14:32, lines 128)
 * ```
 * &lt;head, ≤ max-tokens-per-file&gt;
 * ```
 *
 * ### /abs/path/file2 ...
 * &lt;/system-reminder&gt;
 * </pre>
 *
 * <p>Returns {@code null} when:
 * <ul>
 *   <li>{@code skillforge.compact.recovery.enabled=false} (feature flag off)</li>
 *   <li>The cache for the session is empty (nothing to recover)</li>
 *   <li>{@code FileStateCache.snapshot} throws (logged at WARN, callers must continue compact)</li>
 * </ul>
 *
 * <p>Framework-free: this class has no Spring annotations because skillforge-core does not
 * depend on Spring.  It is wired as a {@code @Bean} in skillforge-server (SkillForgeConfig)
 * which injects values from {@code application.yml} via setter calls at bean construction.
 */
public class RecoveryPayloadBuilder {

    private static final Logger log = LoggerFactory.getLogger(RecoveryPayloadBuilder.class);

    public static final int DEFAULT_MAX_FILES = 5;
    public static final int DEFAULT_MAX_TOKENS_PER_FILE = 5_000;

    private static final DateTimeFormatter HEADER_TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss 'UTC'").withZone(ZoneId.of("UTC"));
    private static final DateTimeFormatter ENTRY_TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("UTC"));

    private final FileStateCache fileStateCache;
    private volatile List<RecoveryPayloadContributor> contributors = List.of();

    private boolean enabled = true;
    private int maxFiles = DEFAULT_MAX_FILES;
    private int maxTokensPerFile = DEFAULT_MAX_TOKENS_PER_FILE;

    public RecoveryPayloadBuilder(FileStateCache fileStateCache) {
        this.fileStateCache = fileStateCache;
    }

    /**
     * Build a single recovery {@link Message} for {@code sessionId}, or null if no payload
     * should be emitted.  Does not throw — any internal failure degrades to null + WARN log
     * so the surrounding compact path is never blocked by a recovery failure.
     */
    public Message build(String sessionId) {
        if (!enabled) {
            return null;
        }
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        List<FileStateCache.FileEntry> files = List.of();
        try {
            int budget = (int) Math.min((long) maxFiles * maxTokensPerFile, Integer.MAX_VALUE);
            files = fileStateCache.snapshot(sessionId, maxFiles, budget);
        } catch (Exception ex) {
            log.warn("RecoveryPayloadBuilder snapshot failed for sessionId={} — skipping recovery payload",
                    sessionId, ex);
            // File recovery is best-effort; persistent contributors may still have state.
        }
        if (files == null) files = List.of();
        List<String> contributed = new ArrayList<>();
        for (RecoveryPayloadContributor contributor : contributors) {
            try {
                String text = contributor.contribute(sessionId);
                if (text != null && !text.isBlank()) contributed.add(text.trim());
            } catch (Exception ex) {
                log.warn("Recovery payload contributor failed for sessionId={}; skipping contributor",
                        sessionId, ex);
            }
        }
        if (files.isEmpty() && contributed.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder(2048);
        // REMINDER-MVP D6: wrap recovery payload in <system-reminder> so it joins the same
        // framework as Phase A sources.  CompactionService still owns the trigger (4 paths:
        // B2 / preemptive / post-overflow / session-memory) and persistence; we only changed
        // the rendered string here.
        sb.append("<system-reminder>\n");
        if (!files.isEmpty()) {
            sb.append("[Recovery payload — ")
                    .append(files.size())
                    .append(files.size() == 1 ? " most recently accessed file at " : " most recently accessed files at ")
                    .append(HEADER_TIME_FMT.format(Instant.now()))
                    .append("]\n\n");
        } else {
            sb.append("[Recovery payload at ").append(HEADER_TIME_FMT.format(Instant.now())).append("]\n\n");
        }

        for (FileStateCache.FileEntry e : files) {
            sb.append("### ")
              .append(e.path() == null ? "(unknown)" : e.path())
              .append(" (lastRead ")
              .append(e.lastReadAt() == null ? "?" : ENTRY_TIME_FMT.format(e.lastReadAt()))
              .append(", lines ")
              .append(e.lineCount())
              .append(")\n");
            sb.append("```\n");
            // append head as-is; never strip trailing newline (LLMs benefit from clear EOL)
            String head = e.headContent() == null ? "" : e.headContent();
            sb.append(head);
            if (!head.endsWith("\n")) {
                sb.append('\n');
            }
            sb.append("```\n\n");
        }
        for (String text : contributed) {
            sb.append("## Restored persistent state\n").append(text).append("\n\n");
        }
        sb.append("</system-reminder>\n");
        return Message.user(sb.toString());
    }

    // ============= config knobs (Spring wires via setters in SkillForgeConfig) =============

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setMaxFiles(int maxFiles) {
        if (maxFiles > 0) {
            this.maxFiles = maxFiles;
        }
    }

    public void setMaxTokensPerFile(int maxTokensPerFile) {
        if (maxTokensPerFile > 0) {
            this.maxTokensPerFile = maxTokensPerFile;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getMaxFiles() {
        return maxFiles;
    }

    public int getMaxTokensPerFile() {
        return maxTokensPerFile;
    }

    public void setContributors(List<RecoveryPayloadContributor> contributors) {
        this.contributors = contributors == null ? List.of()
                : contributors.stream().filter(java.util.Objects::nonNull).toList();
    }
}
