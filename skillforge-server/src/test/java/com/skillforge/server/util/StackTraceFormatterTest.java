package com.skillforge.server.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StackTraceFormatterTest {

    @Test
    @DisplayName("format(null, n) returns empty string")
    void format_null_returnsEmpty() {
        assertThat(StackTraceFormatter.format(null, 10)).isEmpty();
    }

    @Test
    @DisplayName("single throwable with frames <= maxFrames keeps all frames without omission footer")
    void format_framesUnderLimit_noOmissionFooter() {
        RuntimeException e = buildException("boom", 3);

        String out = StackTraceFormatter.format(e, 10);

        assertThat(out).startsWith("java.lang.RuntimeException: boom");
        assertThat(out).doesNotContain("more frames");
        // Each frame line prefixed with tab + "at "
        long atLines = out.lines().filter(l -> l.startsWith("\tat ")).count();
        assertThat(atLines).isEqualTo(3);
    }

    @Test
    @DisplayName("frames > maxFrames emits '... N more frames' footer")
    void format_framesOverLimit_emitsOmissionFooter() {
        RuntimeException e = buildException("toomany", 15);

        String out = StackTraceFormatter.format(e, 10);

        long atLines = out.lines().filter(l -> l.startsWith("\tat ")).count();
        assertThat(atLines).isEqualTo(10);
        assertThat(out).contains("\t... 5 more frames");
    }

    @Test
    @DisplayName("null message omits the colon on the header line")
    void format_nullMessage_noColon() {
        RuntimeException e = new RuntimeException((String) null);
        e.setStackTrace(syntheticFrames(1));

        String out = StackTraceFormatter.format(e, 10);

        String header = out.lines().findFirst().orElse("");
        assertThat(header).isEqualTo("java.lang.RuntimeException");
        assertThat(header).doesNotContain(":");
    }

    @Test
    @DisplayName("Caused-by chain emits all levels with per-level frame counting")
    void format_causedByChain_emitsAllLevels() {
        RuntimeException c = buildException("cMsg", 12);
        RuntimeException b = buildException("bMsg", 3);
        b.initCause(c);
        RuntimeException a = buildException("aMsg", 5);
        a.initCause(b);

        String out = StackTraceFormatter.format(a, 10);

        assertThat(out).contains("java.lang.RuntimeException: aMsg");
        assertThat(out).contains("Caused by: java.lang.RuntimeException: bMsg");
        assertThat(out).contains("Caused by: java.lang.RuntimeException: cMsg");
        // Only the deepest cause (12 frames) should trigger the omission footer
        assertThat(out).containsOnlyOnce("more frames");
        assertThat(out).contains("\t... 2 more frames");
    }

    @Test
    @DisplayName("cyclic cause chain does not loop forever")
    void format_cyclicCause_terminates() {
        // Build a custom throwable whose getCause() returns another throwable whose
        // getCause() returns the first — simulates A <-> B cause cycle without
        // requiring reflection into java.base (which is blocked on JDK 17+).
        CycleThrowable a = new CycleThrowable("A");
        CycleThrowable b = new CycleThrowable("B");
        a.setStackTrace(syntheticFrames(1));
        b.setStackTrace(syntheticFrames(1));
        a.linkTo(b);
        b.linkTo(a);

        String out = StackTraceFormatter.format(a, 10);

        // Both throwables visited exactly once; cycle breaks after second visit attempt
        assertThat(out).contains(CycleThrowable.class.getName() + ": A");
        assertThat(out).contains("Caused by: " + CycleThrowable.class.getName() + ": B");
        // A appears only once (as root header, not re-emitted as a Caused by)
        assertThat(out).doesNotContain("Caused by: " + CycleThrowable.class.getName() + ": A");
    }

    /** Throwable whose {@link #getCause()} is wired post-hoc to allow mutual cycles. */
    private static final class CycleThrowable extends RuntimeException {
        private Throwable linked;

        CycleThrowable(String message) {
            super(message);
        }

        void linkTo(Throwable other) {
            this.linked = other;
        }

        @Override
        public synchronized Throwable getCause() {
            return linked;
        }
    }

    @Test
    @DisplayName("maxFramesPerCause = 0 keeps header only, emits N more frames")
    void format_zeroFrames_headerOnly() {
        RuntimeException e = buildException("zero", 4);

        String out = StackTraceFormatter.format(e, 0);

        long atLines = out.lines().filter(l -> l.startsWith("\tat ")).count();
        assertThat(atLines).isZero();
        assertThat(out).contains("\t... 4 more frames");
        assertThat(out.lines().findFirst().orElse("")).isEqualTo("java.lang.RuntimeException: zero");
    }

    // -- helpers --

    private static RuntimeException buildException(String msg, int frameCount) {
        RuntimeException e = new RuntimeException(msg);
        e.setStackTrace(syntheticFrames(frameCount));
        return e;
    }

    private static StackTraceElement[] syntheticFrames(int n) {
        StackTraceElement[] frames = new StackTraceElement[n];
        for (int i = 0; i < n; i++) {
            frames[i] = new StackTraceElement(
                    "com.example.Fake" + i,
                    "method" + i,
                    "Fake" + i + ".java",
                    100 + i);
        }
        return frames;
    }
}
