package com.skillforge.server.history;

import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict codec for immutable message/summary/archive History references. */
@Component
public final class HistoryRefCodec {

    private static final Pattern MESSAGE = Pattern.compile(
            "msg:e(0|[1-9][0-9]*):id([1-9][0-9]*):block(0|[1-9][0-9]*)");
    private static final Pattern SUMMARY = Pattern.compile(
            "summary:e(0|[1-9][0-9]*):id([1-9][0-9]*)");
    private static final Pattern ARCHIVE = Pattern.compile(
            "archive:e(0|[1-9][0-9]*):id([A-Za-z0-9][A-Za-z0-9._-]{0,127})");

    public HistoryRef parse(String encoded, CurrentSessionHistoryScope scope) {
        Objects.requireNonNull(scope, "scope");
        if (encoded == null || encoded.isBlank() || encoded.length() > 4096) throw invalid();
        try {
            Matcher message = MESSAGE.matcher(encoded);
            if (message.matches()) {
                MessageRef ref = new MessageRef(
                        parseLong(message.group(1)), parseLong(message.group(2)),
                        parseInt(message.group(3)));
                requireEpoch(ref.historyEpoch(), scope);
                return ref;
            }
            Matcher summary = SUMMARY.matcher(encoded);
            if (summary.matches()) {
                SummaryRef ref = new SummaryRef(
                        parseLong(summary.group(1)), parseLong(summary.group(2)));
                requireEpoch(ref.historyEpoch(), scope);
                return ref;
            }
            Matcher archive = ARCHIVE.matcher(encoded);
            if (archive.matches()) {
                ArchiveRef ref = new ArchiveRef(parseLong(archive.group(1)), archive.group(2));
                requireEpoch(ref.historyEpoch(), scope);
                return ref;
            }
        } catch (ArithmeticException e) {
            throw invalid();
        }
        throw invalid();
    }

    public String format(HistoryRef ref, CurrentSessionHistoryScope scope) {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(scope, "scope");
        requireEpoch(ref.historyEpoch(), scope);
        if (ref instanceof MessageRef message) {
            if (message.messageId() <= 0 || message.blockIndex() < 0) throw invalid();
            return "msg:e" + message.historyEpoch() + ":id" + message.messageId()
                    + ":block" + message.blockIndex();
        }
        if (ref instanceof SummaryRef summary) {
            if (summary.summaryId() <= 0) throw invalid();
            return "summary:e" + summary.historyEpoch() + ":id" + summary.summaryId();
        }
        ArchiveRef archive = (ArchiveRef) ref;
        if (!ARCHIVE.matcher("archive:e" + archive.historyEpoch() + ":id" + archive.archiveId())
                .matches()) throw invalid();
        return "archive:e" + archive.historyEpoch() + ":id" + archive.archiveId();
    }

    private static void requireEpoch(long epoch, CurrentSessionHistoryScope scope) {
        if (epoch != scope.historyEpoch()) {
            throw new HistoryProtocolException("HISTORY_STALE", "History reference is stale");
        }
    }

    private static long parseLong(String value) {
        long parsed = Long.parseLong(value);
        if (parsed < 0) throw invalid();
        return parsed;
    }

    private static int parseInt(String value) {
        return Math.toIntExact(parseLong(value));
    }

    private static HistoryProtocolException invalid() {
        return new HistoryProtocolException("INVALID_REF", "Invalid History reference");
    }

    public sealed interface HistoryRef permits MessageRef, SummaryRef, ArchiveRef {
        long historyEpoch();
    }

    public record MessageRef(long historyEpoch, long messageId, int blockIndex)
            implements HistoryRef { }

    public record SummaryRef(long historyEpoch, long summaryId) implements HistoryRef { }

    public record ArchiveRef(long historyEpoch, String archiveId) implements HistoryRef { }
}
