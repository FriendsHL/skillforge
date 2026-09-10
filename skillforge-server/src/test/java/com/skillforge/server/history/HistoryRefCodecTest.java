package com.skillforge.server.history;

import com.skillforge.core.skill.SkillContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HistoryRefCodecTest {

    private final HistoryRefCodec codec = new HistoryRefCodec();
    private final CurrentSessionHistoryScope scope = scope(7L);

    @Test
    void roundTripsAllStableRefKindsAgainstCurrentEpoch() {
        assertThat(codec.parse("msg:e7:id42:block3", scope))
                .isEqualTo(new HistoryRefCodec.MessageRef(7, 42, 3));
        assertThat(codec.parse("summary:e7:id12", scope))
                .isEqualTo(new HistoryRefCodec.SummaryRef(7, 12));
        assertThat(codec.parse("archive:e7:id550e8400-e29b-41d4-a716-446655440000", scope))
                .isEqualTo(new HistoryRefCodec.ArchiveRef(
                        7, "550e8400-e29b-41d4-a716-446655440000"));

        assertThat(codec.format(new HistoryRefCodec.MessageRef(7, 42, 3), scope))
                .isEqualTo("msg:e7:id42:block3");
    }

    @Test
    void staleEpochIsDistinctFromMalformedOrUnknownRef() {
        assertCode(() -> codec.parse("msg:e6:id42:block3", scope), "HISTORY_STALE");
        assertCode(() -> codec.parse("msg:e7:id0:block3", scope), "INVALID_REF");
        assertCode(() -> codec.parse("msg:e7:id42:block-1", scope), "INVALID_REF");
        assertCode(() -> codec.parse("foreign:e7:id42", scope), "INVALID_REF");
        assertCode(() -> codec.parse("archive:e7:idbad:id", scope), "INVALID_REF");
    }

    private static CurrentSessionHistoryScope scope(long epoch) {
        SkillContext context = new SkillContext();
        context.setSessionId("session-current");
        context.setUserId(23L);
        context.setToolUseId("toolu_history");
        return CurrentSessionHistoryScope.from(context, epoch, 100, 90);
    }

    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOf(HistoryProtocolException.class)
                .extracting(error -> ((HistoryProtocolException) error).getCode())
                .isEqualTo(code);
    }
}
