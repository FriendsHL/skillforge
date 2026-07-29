package com.skillforge.core.reminder;

import com.skillforge.core.context.ContextLifecycle;
import com.skillforge.core.context.ContextKind;
import com.skillforge.core.context.PromptAuthority;
import com.skillforge.core.context.PromptCompactPolicy;
import com.skillforge.core.context.PromptPlacement;
import com.skillforge.core.context.PromptSourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REMINDER-MVP unit tests for {@link ReminderBuilder} — covers PRD D7 ordering, budget
 * truncation, per-source disable, global disable, and empty/exception fallback.
 */
class ReminderBuilderTest {

    private ReminderContext newCtx() {
        return newCtx(null);
    }

    private ReminderContext newCtx(ReminderBuilder builderRef) {
        return new ReminderContext("s1", 1L, 0,
                Collections.emptyList(), 200_000,
                "", List.of(), 0, null, null, builderRef);
    }

    private ReminderSource always(String name, String text, int tokens) {
        return new ReminderSource() {
            @Override public String getName() { return name; }
            @Override public boolean shouldEmit(ReminderContext ctx) { return true; }
            @Override public ReminderEntry emit(ReminderContext ctx) {
                return new ReminderEntry(text, tokens);
            }
        };
    }

    private ReminderSource never(String name) {
        return new ReminderSource() {
            @Override public String getName() { return name; }
            @Override public boolean shouldEmit(ReminderContext ctx) { return false; }
            @Override public ReminderEntry emit(ReminderContext ctx) {
                throw new AssertionError("never source must not emit");
            }
        };
    }

    private ReminderSource throwing(String name) {
        return new ReminderSource() {
            @Override public String getName() { return name; }
            @Override public boolean shouldEmit(ReminderContext ctx) { return true; }
            @Override public ReminderEntry emit(ReminderContext ctx) {
                throw new RuntimeException("boom");
            }
        };
    }

    @Test
    @DisplayName("globalEnabled=false → returns empty string regardless of sources")
    void globalDisabled_returnsEmpty() {
        ReminderBuilder builder = new ReminderBuilder(
                List.of(always("a", "X", 10)), 5000, false);
        assertThat(builder.build(newCtx(builder))).isEmpty();
    }

    @Test
    @DisplayName("all sources skip → returns empty string (no wrapper emitted)")
    void allSkip_returnsEmpty() {
        ReminderBuilder builder = new ReminderBuilder(
                List.of(never("a"), never("b")), 5000, true);
        assertThat(builder.build(newCtx(builder))).isEmpty();
    }

    @Test
    @DisplayName("D7 source order is preserved verbatim in output")
    void preservesSourceOrder() {
        ReminderBuilder builder = new ReminderBuilder(
                List.of(always("a", "Context: 70%", 5),
                        always("b", "Memory: 3 stale", 5),
                        always("c", "Recent files: x", 5)),
                5000, true);
        String out = builder.build(newCtx(builder));
        int idxA = out.indexOf("Context: 70%");
        int idxB = out.indexOf("Memory: 3 stale");
        int idxC = out.indexOf("Recent files: x");
        assertThat(idxA).isPositive();
        assertThat(idxB).isGreaterThan(idxA);
        assertThat(idxC).isGreaterThan(idxB);
    }

    @Test
    @DisplayName("output is wrapped in <system-reminder>...</system-reminder>")
    void outputIsWrapped() {
        ReminderBuilder builder = new ReminderBuilder(
                List.of(always("a", "hello", 3)), 5000, true);
        String out = builder.build(newCtx(builder));
        assertThat(out).contains("<system-reminder>");
        assertThat(out).contains("</system-reminder>");
        assertThat(out).contains("hello");
        // Q2: no leading newline — block is now used as ContentBlock.text directly so it
        // must start with the literal tag (FE filter expects exact prefix).
        assertThat(out).startsWith("<system-reminder>\n");
        assertThat(out).endsWith("</system-reminder>\n");
    }

    @Test
    @DisplayName("structured result keeps compatibility bytes and exposes content-free authority metadata")
    void structuredResult_preservesBytesAndExposesMetadata() {
        ReminderSource source = new ReminderSource() {
            @Override public String getName() { return "permission"; }
            @Override public boolean shouldEmit(ReminderContext ctx) { return true; }
            @Override public ReminderEntry emit(ReminderContext ctx) {
                return new ReminderEntry(
                        "permission:42",
                        ReminderSourceType.PERMISSION,
                        ReminderSeverity.WARNING,
                        ReminderReasonCode.PERMISSION_STATE_CHANGED,
                        "Permission is waiting",
                        5,
                        2,
                        PromptPlacement.AFTER_TOOL_RESULT,
                        ContextLifecycle.UNTIL_STATE_CHANGE,
                        PromptCompactPolicy.KEEP_METADATA,
                        null);
            }
        };
        ReminderBuilder structured = new ReminderBuilder(List.of(source), 5000, true, true);
        ReminderBuilder legacy = new ReminderBuilder(List.of(source), 5000, true, false);

        ReminderBuildResult result = structured.buildResult(newCtx(structured));

        assertThat(result.renderedText()).isEqualTo(legacy.build(newCtx(legacy)));
        assertThat(result.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.reasonCode()).isEqualTo(ReminderReasonCode.PERMISSION_STATE_CHANGED);
            assertThat(entry.placement()).isEqualTo(PromptPlacement.AFTER_TOOL_RESULT);
            assertThat(entry.lifecycle()).isEqualTo(ContextLifecycle.UNTIL_STATE_CHANGE);
        });
        assertThat(result.attachments()).singleElement().satisfies(attachment -> {
            assertThat(attachment.kind()).isEqualTo(ContextKind.REMINDER);
            assertThat(attachment.sourceType()).isEqualTo(PromptSourceType.REMINDER);
            assertThat(attachment.authority()).isEqualTo(PromptAuthority.PLATFORM);
            assertThat(attachment.sourceIds()).containsExactly(
                    "PERMISSION", "PERMISSION_STATE_CHANGED", "WARNING");
        });
        assertThat(structured.getLastObservations("s1")).singleElement()
                .satisfies(observation -> {
                    assertThat(observation.contentHash()).hasSize(64);
                    assertThat(observation.reasonCode())
                            .isEqualTo(ReminderReasonCode.PERMISSION_STATE_CHANGED);
                });
        assertThat(legacy.buildResult(newCtx(legacy)).attachments()).isEmpty();
        assertThat(legacy.getLastObservations("s1")).isEmpty();
    }

    @Test
    @DisplayName("expired entries add zero text and zero attachments")
    void expiredEntry_isDropped() {
        ReminderSource expired = new ReminderSource() {
            @Override public String getName() { return "expired"; }
            @Override public boolean shouldEmit(ReminderContext ctx) { return true; }
            @Override public ReminderEntry emit(ReminderContext ctx) {
                return new ReminderEntry(
                        "expired", ReminderSourceType.OTHER, ReminderSeverity.INFO,
                        ReminderReasonCode.UNSPECIFIED, "must not render", 3, 0,
                        PromptPlacement.BEFORE_NEXT_MODEL_CALL,
                        ContextLifecycle.SINGLE_TURN,
                        PromptCompactPolicy.DROP_ON_COMPACT,
                        Instant.EPOCH);
            }
        };
        ReminderBuilder builder = new ReminderBuilder(List.of(expired), 5000, true, true);

        ReminderBuildResult result = builder.buildResult(newCtx(builder));

        assertThat(result.renderedText()).isEmpty();
        assertThat(result.entries()).isEmpty();
        assertThat(result.attachments()).isEmpty();

        ReminderBuilder rollbackBuilder =
                new ReminderBuilder(List.of(expired), 5000, true, false);
        assertThat(rollbackBuilder.build(newCtx(rollbackBuilder)))
                .contains("must not render");
    }

    @Test
    @DisplayName("state change that stops emission clears prior runtime observations")
    void stateChange_stopsEmissionAndClearsObservation() {
        java.util.concurrent.atomic.AtomicBoolean active =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        ReminderSource stateful = new ReminderSource() {
            @Override public String getName() { return "stateful"; }
            @Override public boolean shouldEmit(ReminderContext ctx) { return active.get(); }
            @Override public ReminderEntry emit(ReminderContext ctx) {
                return new ReminderEntry(
                        "stateful", ReminderSourceType.OTHER, ReminderSeverity.INFO,
                        ReminderReasonCode.UNSPECIFIED, "active state", 3, 0,
                        PromptPlacement.BEFORE_NEXT_MODEL_CALL,
                        ContextLifecycle.UNTIL_STATE_CHANGE,
                        PromptCompactPolicy.DROP_ON_COMPACT,
                        null);
            }
        };
        ReminderBuilder builder = new ReminderBuilder(List.of(stateful), 5000, true, true);
        ReminderContext ctx = newCtx(builder);
        builder.build(ctx);
        assertThat(builder.getLastObservations("s1")).hasSize(1);

        active.set(false);
        assertThat(builder.build(ctx)).isEmpty();

        assertThat(builder.getLastObservations("s1")).isEmpty();
    }

    @Test
    @DisplayName("budget truncates later sources once exceeded; first entry always admitted")
    void budgetTruncation() {
        ReminderBuilder builder = new ReminderBuilder(
                List.of(
                        always("first", "AAAA", 80),
                        always("second", "BBBB", 30),
                        always("third", "CCCC", 5)
                ),
                100, true);
        String out = builder.build(newCtx(builder));
        assertThat(out).contains("AAAA");
        assertThat(out).doesNotContain("BBBB");
        assertThat(out).doesNotContain("CCCC");
    }

    @Test
    @DisplayName("first entry always admitted even when oversized (don't drop critical signal)")
    void oversizedFirstEntry_stillAdmitted() {
        ReminderBuilder builder = new ReminderBuilder(
                List.of(always("first", "OVER", 9999),
                        always("second", "NEXT", 1)),
                100, true);
        String out = builder.build(newCtx(builder));
        assertThat(out).contains("OVER");
        assertThat(out).doesNotContain("NEXT");
    }

    @Test
    @DisplayName("source exception is swallowed; subsequent sources still emit")
    void exceptionSource_isolated() {
        ReminderBuilder builder = new ReminderBuilder(
                List.of(throwing("bad"),
                        always("good", "still works", 5)),
                5000, true);
        String out = builder.build(newCtx(builder));
        assertThat(out).contains("still works");
    }

    @Test
    @DisplayName("null context returns empty (defensive)")
    void nullContext_returnsEmpty() {
        ReminderBuilder builder = new ReminderBuilder(
                List.of(always("a", "X", 5)), 5000, true);
        assertThat(builder.build(null)).isEmpty();
    }

    @Test
    @DisplayName("source returning null entry is skipped without error")
    void nullEntry_skipped() {
        ReminderSource nullEmit = new ReminderSource() {
            @Override public String getName() { return "n"; }
            @Override public boolean shouldEmit(ReminderContext ctx) { return true; }
            @Override public ReminderEntry emit(ReminderContext ctx) { return null; }
        };
        ReminderBuilder builder = new ReminderBuilder(
                List.of(nullEmit, always("good", "ok", 1)),
                5000, true);
        String out = builder.build(newCtx(builder));
        assertThat(out).contains("ok");
    }

    @Test
    @DisplayName("null sources list is treated as empty (defensive)")
    void nullSources_returnsEmpty() {
        ReminderBuilder builder = new ReminderBuilder(null, 5000, true);
        assertThat(builder.build(newCtx(builder))).isEmpty();
    }

    @Test
    @DisplayName("Q2: per-session debounce state is isolated between sessions")
    void perSessionDebounce_isolated() {
        ReminderBuilder builder = new ReminderBuilder(List.of(), 5000, true);
        builder.setLastEmitted("s1", "context-usage", 7);
        assertThat(builder.getLastEmitted("s1", "context-usage")).isEqualTo(7);
        // Different session must NOT share state
        assertThat(builder.getLastEmitted("s2", "context-usage")).isNull();
        // Different source on the same session must NOT share state
        assertThat(builder.getLastEmitted("s1", "memory-age")).isNull();
        // Updating one session does not perturb the other
        builder.setLastEmitted("s2", "context-usage", 99);
        assertThat(builder.getLastEmitted("s1", "context-usage")).isEqualTo(7);
        assertThat(builder.getLastEmitted("s2", "context-usage")).isEqualTo(99);
    }

    @Test
    @DisplayName("Q2: lastEmitted defensive null sessionId / sourceName → no-op + null read")
    void perSessionDebounce_nullArgs() {
        ReminderBuilder builder = new ReminderBuilder(List.of(), 5000, true);
        // null sessionId → setter no-op, getter returns null
        builder.setLastEmitted(null, "context-usage", 1);
        assertThat(builder.getLastEmitted(null, "context-usage")).isNull();
        // null sourceName → setter no-op, getter returns null
        builder.setLastEmitted("s1", null, 1);
        assertThat(builder.getLastEmitted("s1", null)).isNull();
    }

    @Test
    @DisplayName("BE-W1: clearSession drops every per-source debounce entry for that session")
    void reminderBuilder_clearSession_removesDebounceEntries() {
        ReminderBuilder builder = new ReminderBuilder(List.of(), 5000, true);
        builder.setLastEmitted("sid-a", "context-usage", 1);
        builder.setLastEmitted("sid-a", "memory-age", 2);
        builder.setLastEmitted("sid-a", "file-activity", 3);
        builder.setLastEmitted("sid-b", "context-usage", 9);

        builder.clearSession("sid-a");

        // All sources for sid-a are gone
        assertThat(builder.getLastEmitted("sid-a", "context-usage")).isNull();
        assertThat(builder.getLastEmitted("sid-a", "memory-age")).isNull();
        assertThat(builder.getLastEmitted("sid-a", "file-activity")).isNull();
        // Other session is untouched
        assertThat(builder.getLastEmitted("sid-b", "context-usage")).isEqualTo(9);

        // clearSession is idempotent + null-safe
        builder.clearSession("sid-a");
        builder.clearSession(null);
        assertThat(builder.getLastEmitted("sid-b", "context-usage")).isEqualTo(9);
    }
}
